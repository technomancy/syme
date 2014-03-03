(ns syme.dns
  (:require [environ.core :refer [env]])
  (:import (com.amazonaws.auth BasicAWSCredentials)
           (com.amazonaws.services.route53 AmazonRoute53Client)
           (com.amazonaws.services.route53.model Change ChangeBatch
                                                 ChangeResourceRecordSetsRequest
                                                 GetHostedZoneRequest
                                                 ResourceRecord
                                                 ResourceRecordSet)))

(def client (delay (AmazonRoute53Client. (BasicAWSCredentials.
                                          (env :aws-access-key)
                                          (env :aws-secret-key)))))

(defn make-request [changes]
  (when (:aws-access-key env)
    (let [zone-req (GetHostedZoneRequest. (env :zone-id))
          zone (.getHostedZone (.getHostedZone @client zone-req))
          changes (ChangeBatch. changes)
          req (ChangeResourceRecordSetsRequest. (.getId zone) changes)]
      (.changeResourceRecordSets @client req))))

(defn make-change [change-type hostname ip]
  (Change. change-type
           (doto (ResourceRecordSet. hostname "A")
             (.setTTL 5)
             (.setResourceRecords [(ResourceRecord. ip)]))))

(defn register-hostname [hostname new-ip]
  (make-request [(make-change "CREATE" hostname new-ip)]))

(defn deregister-hostname [hostname ip]
  (make-request [(make-change "DELETE" hostname ip)]))
