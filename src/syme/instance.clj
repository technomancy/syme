(ns syme.instance
  (:require [clj-http.client :as http]
            [environ.core :refer [env]]
            [tentacles.users :as users]
            [tentacles.orgs :as orgs]
            [tentacles.repos :as repos]
            [clojure.java.io :as io]
            [clojure.java.jdbc :as sql]
            [clojure.java.shell :as sh]
            [clojure.string :as string]
            [syme.db :as db]
            [syme.dns :as dns])
  (:import (com.amazonaws.auth BasicAWSCredentials)
           (com.amazonaws.services.ec2 AmazonEC2Client)
           (com.amazonaws.services.ec2.model CreateSecurityGroupRequest
                                             AuthorizeSecurityGroupIngressRequest
                                             IpPermission
                                             ImportKeyPairRequest
                                             RunInstancesRequest
                                             DescribeInstancesRequest
                                             TerminateInstancesRequest)
           (org.apache.commons.codec.binary Base64)))

(defn make-client [identity credential]
  (doto (AmazonEC2Client. (BasicAWSCredentials. identity credential))
    (.setEndpoint "ec2.us-west-2.amazonaws.com")))

(def syme-pubkey
  (str (io/file (System/getProperty "user.dir") "keys" "syme.pub")))

(def syme-privkey
  (str (io/file (System/getProperty "user.dir") "keys" "syme")))

(def write-key-pair
  (delay (when (env :private-key)
           (.mkdirs (.getParentFile (io/file syme-pubkey)))
           (io/copy (.getBytes (.replaceAll (env :private-key) "\\\\n" "\n"))
                    (io/file syme-privkey))
           (io/copy (.getBytes (env :public-key))
                    (io/file syme-pubkey)))))

(defn subdomain-for [{:keys [owner id]}]
  (format (:subdomain env) owner id))

(defn unregister-dns [username project]
  (when-let [{:keys [ip] :as record} (db/find username project)]
    (when (:subdomain env)
      (dns/make-request [(dns/make-change "DELETE" (subdomain-for record) ip)]))))

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
           (when-not (= "InvalidGroup.Duplicate" (.getErrorCode e))
             (throw e))))
    security-group-name))

(defn import-key-pair [client pubkey]
  (try (.importKeyPair client (ImportKeyPairRequest. "syme-keys" pubkey))
       (catch Exception e
         (when-not (= "InvalidKeyPair.Duplicate" (.getErrorCode e))))))

(defn user-data [username project invitees]
  (let [{:keys [language]} (apply repos/specific-repo (.split project "/"))
        {:keys [name email]} (users/user username)
        {:keys [shutdown_token]} (db/find username project)
        ;; TODO: expand orgs into member usernames
        invitees (clojure.string/join " " invitees)
        language-script (io/resource (str "languages/" language ".sh"))]
    (format (slurp (io/resource "userdata.sh"))
            username project invitees name email
            (if language-script (slurp language-script) "")
            (str (:canonical-url env) "/shutdown?token=" shutdown_token))))

(defn run-instance [client security-group user-data-script]
  (.runInstances client (-> (RunInstancesRequest.)
                            (.withImageId "ami-162ea626")
                            (.withInstanceType "m1.small")
                            (.withMinCount (Integer. 1))
                            (.withMaxCount (Integer. 1))
                            (.withKeyName "syme-keys")
                            (.withSecurityGroups [security-group])
                            (.withUserData (String.
                                            (Base64/encodeBase64
                                             (.getBytes user-data-script)))))))

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

(defn poll-for-bootstrapped [username project ip tries]
  (let [{:keys [exit]} (sh/sh "ssh" "-i" syme-privkey
                              "-o" "StrictHostKeyChecking=no"
                              (str "ubuntu@" ip)
                              "ls" "/home/ubuntu/bootstrapped")]
    (Thread/sleep 5000)
    (if (pos? exit)
      (if (> tries 60)
        (throw (ex-info "Timed out bootstrapping." {:status "unconfigured"}))
        (recur username project ip (inc tries)))
      (db/status username project "ready"))))

;; TODO: break this into several defns
(defn launch [username {:keys [project invite identity credential]}]
  (force write-key-pair)
  (db/create username project)
  (future
    (try
      (let [client (make-client identity credential)
            invitees (cons username (if-not (= invite "users to invite")
                                      (.split invite ",? +")))
            security-group-name (str "syme/" username)]
        (sql/with-connection db/db
          (doseq [invitee invitees]
            (db/invite username project invitee)))
        (db/status username project "bootstrapping")
        (println "Setting up security group and key for" project "...")
        (create-security-group client security-group-name)
        (import-key-pair client (slurp syme-pubkey))
        (println "launching" project "...")
        (let [result (run-instance client security-group-name
                                   (user-data username project invitees))
              id (-> result .getReservation .getInstances first .getInstanceId)]
          (println "waiting for IP...")
          (let [ip (poll-for-ip client id 0)]
            (println "got IP:" ip)
            ;; TODO: register subdomain
            (db/status username project "configuring" {:ip ip :instance_id id})
            (poll-for-bootstrapped username project ip 0))))
      (catch Exception e
        (.printStackTrace e)
        (db/status username project
                   (if (and (instance? com.amazonaws.AmazonServiceException e)
                            (= "AuthFailure" (.getErrorCode e)))
                     "unauthorized"
                     (:status (ex-data e) "error")))))))

(defn halt [username {:keys [project identity credential]}]
  (let [client (make-client identity credential)
        {:keys [instance_id]} (db/find username project)]
    (.terminateInstances client (TerminateInstancesRequest. [instance_id]))
    (db/status username project "halting")))
