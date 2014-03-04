(ns syme.instance
  (:require [clojure.java.io :as io]
            [clojure.java.jdbc :as sql]
            [syme.db :as db]
            [syme.dns :as dns]
            [tentacles.repos :as repos]
            [tentacles.orgs :as orgs]
            [tentacles.users :as users]
            [environ.core :refer [env]])
  (:import (com.amazonaws.auth BasicAWSCredentials)
           (com.amazonaws.services.ec2 AmazonEC2Client)
           (com.amazonaws.services.ec2.model AuthorizeSecurityGroupIngressRequest
                                             CreateSecurityGroupRequest
                                             DescribeInstancesRequest
                                             IpPermission
                                             RunInstancesRequest
                                             TerminateInstancesRequest
                                             DescribeInstancesRequest
                                             TerminateInstancesRequest
                                             CreateTagsRequest
                                             Tag)
           (com.amazonaws AmazonServiceException)
           (org.apache.commons.codec.binary Base64)))

(def ami-by-region
  {:sa-east-1 "ami-3bec3426",
   :ap-northeast-1 "ami-b8a813b9",
   :ap-southeast-2 "ami-f57ee9cf",
   :eu-west-1 "ami-26090552",
   :ap-southeast-1 "ami-4923611b",
   :us-west-2 "ami-162ea626",
   :us-west-1 "ami-44745401",
   :us-east-1 "ami-9b3db0f2"})

(defn default-ami-id [region]
  (get ami-by-region (keyword region)))

(def default-region "us-west-2")

(defn make-endpoint-url [region]
  (str "ec2." region ".amazonaws.com"))

(defn make-client [identity credential region]
  (doto (AmazonEC2Client. (BasicAWSCredentials. identity credential))
    (.setEndpoint (make-endpoint-url region))))

(defn subdomain-for [owner instance-id]
  (format (:subdomain env) owner instance-id))

(defn create-security-group [client security-group-name]
  (let [group-request (-> (CreateSecurityGroupRequest.)
                          (.withGroupName security-group-name)
                          (.withDescription "For Syme instances."))
        ip-permission (-> (IpPermission.)
                          (.withIpProtocol "tcp")
                          (.withIpRanges (into-array ["0.0.0.0/0"]))
                          ;; no longs? you can't be serious.
                          (.withToPort (Integer. 22))
                          (.withFromPort (Integer. 22)))
        auth-request (-> (AuthorizeSecurityGroupIngressRequest.)
                         (.withGroupName security-group-name)
                         (.withIpPermissions [ip-permission]))]
    (try (.createSecurityGroup client group-request)
         (.authorizeSecurityGroupIngress client auth-request)
         (catch Exception e
           (when-not (and (instance? AmazonServiceException e)
                          (= "InvalidGroup.Duplicate" (.getErrorCode e)))
             (throw e))))
    security-group-name))

(defn usernames-for [invitees]
  (let [invitees (.split invitees ",? +")
        [orgs users] ((juxt filter remove) #(.startsWith % "+") invitees)
        ;; TODO: is this paginated?
        orgs-users (for [org orgs]
                    (map :login (orgs/members (subs org 1))))]
    (apply concat users orgs-users)))

(defn primary-language [project]
  (if-let [langs (seq (apply repos/languages (.split project "/")))]
    (-> (apply max-key second langs) first name)))

(defn user-data [username project invitees]
  (let [language (primary-language project)
        {:keys [name email]} (users/user username)
        {:keys [shutdown_token]} (db/find username project)
        language-script (io/resource (str "languages/" language ".sh"))]
    (format (slurp (io/resource "userdata.sh"))
            username project (clojure.string/join " " invitees) name email
            (and (:canonical-url env)
                 (str (:canonical-url env) "/status?token=" shutdown_token))
            (if language-script (slurp language-script) ""))))

(defn run-instance [client security-group user-data-script ami-id]
  (.runInstances client (-> (RunInstancesRequest.)
                            (.withImageId ami-id)
                            (.withInstanceType "m1.small")
                            (.withMinCount (Integer. 1))
                            (.withMaxCount (Integer. 1))
                            (.withSecurityGroups [security-group])
                            (.withUserData (String.
                                            (Base64/encodeBase64
                                             (.getBytes user-data-script)))))))

(defn set-instance-name [client id name]
  (let [tag-name-request (-> (CreateTagsRequest.)
                         (.withResources [id])
                         (.withTags [(Tag. "Name" name)]))]
    (try (.createTags client tag-name-request)
         ;; this is a convenience thing; non-critical
         (catch Exception e
           (println (.getMessage e))))))

(defn poll-for-ip [client id tries]
  (let [describe-request (-> (DescribeInstancesRequest.)
                             (.withInstanceIds [id]))]
    (if (> tries 60)
      (throw (ex-info "Timed out waiting for IP." {:status "timeout"}))
      (Thread/sleep 5000))
    (if-let [ip (-> client
                    (.describeInstances describe-request)
                    .getReservations first
                    .getInstances first
                    .getPublicIpAddress)]
      ip
      (recur client id (inc tries)))))

;; TODO: break this into several defns
(defn launch [username {:keys [project invite identity credential ami-id region]}]
  (let [region (if (empty? region) default-region region)]
    (db/create username project region)
    (future
      (try
        (let [client (make-client identity credential region)
              ami-id (if (empty? ami-id) (default-ami-id region) ami-id)
              invitees (cons username (if-not (= invite "users to invite")
                                        (usernames-for invite)))
              security-group-name (str "syme/" username)]
          (sql/with-connection db/db
            (doseq [invitee invitees]
              (db/invite username project invitee)))
          (db/status username project "bootstrapping")
          (println "Setting up security group for" project "...")
          (create-security-group client security-group-name)
          (println "launching" project "...")
          (let [result (run-instance client security-group-name
                                     (user-data username project invitees)
                                     ami-id)
                instance-id (-> result .getReservation .getInstances
                                first .getInstanceId)]
            (println "setting instance name to" project)
            (set-instance-name client instance-id project)
            (println "waiting for IP...")
            (let [ip (poll-for-ip client instance-id 0)
                  {:keys [id]} (db/find username project)
                  dns (subdomain-for username id)]
              (println "got IP:" ip)
              (db/status username project "configuring"
                         {:ip ip :instance_id instance-id :dns dns})
              (dns/register-hostname dns ip))))
        (catch Exception e
          (.printStackTrace e)
          (db/status username project
                     (if (and (instance? AmazonServiceException e)
                              (= "AuthFailure" (.getErrorCode e)))
                       "unauthorized"
                       (:status (ex-data e) "error"))))))))

(defn halt [username {:keys [project identity credential region]}]
  (let [client (make-client identity credential region)
        {:keys [instance_id]} (db/find username project)]
    (.terminateInstances client (TerminateInstancesRequest. [instance_id]))
    (db/status username project "halting")))
