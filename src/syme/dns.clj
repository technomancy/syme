(ns syme.dns
  (:require [environ.core :refer [env]])
  (:import (com.amazonaws.auth BasicAWSCredentials)
           (com.amazonaws.services.route53 AmazonRoute53Client)
           (com.amazonaws.services.route53.model GetHostedZoneRequest
                                                 ChangeResourceRecordSetsRequest
                                                 ChangeBatch
                                                 Change
                                                 ListResourceRecordSetsRequest
                                                 ResourceRecordSet
                                                 ResourceRecord)))

(defonce client (AmazonRoute53Client. (BasicAWSCredentials.
                                       (env :aws-access-key-id)
                                       (env :aws-secret-key))))

(defn make-request [change]
  (let [zone-req (GetHostedZoneRequest. (env :zone-id))
        zone (.getHostedZone (.getHostedZone client zone-req))
        req (ChangeResourceRecordSetsRequest. (.getId zone) change)]
    (.changeResourceRecordSets client req)))

(defn make-change [change-type hostname ip]
  (Change. change-type
           (doto (ResourceRecordSet. hostname "A")
             (.setTTL 5)
             (.setSetIdentifier "syme1")
             (.setWeight 10)
             (.setResourceRecords [(ResourceRecord. ip)]))))

(defn register-hostname [hostname old-ip new-ip]
  (let [delete (make-change "DELETE" hostname old-ip)
        create (make-change "CREATE" hostname new-ip)]
    (make-request (ChangeBatch. (if old-ip [delete create] [create])))))