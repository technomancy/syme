(ns syme.db
  (:refer-clojure :exclude [find])
  (:require [clojure.java.jdbc :as sql]
            [tentacles.repos :as repos]
            [environ.core :as env])
  (:import (java.util UUID)))

(def db (env/env :database-url "postgres://localhost:5432/syme"))

(defn create [owner project]
  (let [{:keys [description]} (apply repos/specific-repo (.split project "/"))]
    (sql/with-connection db
      (sql/insert-record :instances {:project project :owner owner
                                     :status "starting"
                                     :shutdown_token (str (UUID/randomUUID))
                                     :description description}))))

(defn status [owner project status & [args]]
  (sql/with-connection db
    (sql/with-query-results [last]
      ["SELECT * FROM instances WHERE owner = ? AND project = ? ORDER BY at DESC"
       owner project]
      (sql/update-values :instances
                         ["owner = ? AND project = ? AND at = ?"
                          owner project (:at last)]
                         (merge {:status status} args)))))

(defn invite [owner project invitee]
  (sql/with-query-results [instance]
    ["SELECT * FROM instances WHERE project = ? and owner = ? ORDER BY at DESC"
     project owner]
    (sql/insert-record :invites {:instance_id (:id instance)
                                 :invitee invitee})))

(defn find [username project-name & [include-halted?]]
  (sql/with-connection db
    (sql/with-query-results [instance]
      [(str "SELECT * FROM instances WHERE owner = ? AND project = ?"
            (if-not include-halted? (str " AND status <> 'halted'"
                                         " AND status <> 'halting'"
                                         " AND status <> 'failed'"
                                         " AND status <> 'error'"
                                         " AND status <> 'timeout'"
                                         " AND status <> 'unconfigured'"
                                         " AND status <> 'unauthorized'"))
            " ORDER BY at DESC") username project-name]
      ;; whatever I suck at sql
      (if instance
        (sql/with-query-results invitees
          ["SELECT * FROM invites WHERE instance_id = ?" (:id instance)]
          (assoc instance
            :invitees (mapv :invitee invitees)))))))

(defn by-token [shutdown-token]
  (sql/with-connection db
    (sql/with-query-results [instance]
      ["SELECT * FROM instances WHERE shutdown_token = ?" shutdown-token]
      instance)))

;; migrations

(defn initial-schema []
  (sql/create-table "instances"
                    [:id :serial "PRIMARY KEY"]
                    [:owner :varchar "NOT NULL"]
                    [:project :varchar "NOT NULL"]
                    [:ip :varchar]
                    [:description :text]
                    [:status :varchar]
                    [:at :timestamp "NOT NULL" "DEFAULT CURRENT_TIMESTAMP"])
  (sql/create-table "invites"
                    [:id :serial "PRIMARY KEY"]
                    [:invitee :varchar "NOT NULL"]
                    [:instance_id :integer "NOT NULL"]
                    [:at :timestamp "NOT NULL" "DEFAULT CURRENT_TIMESTAMP"]))

(defn add-instance-id []
  (sql/do-commands "ALTER TABLE instances ADD COLUMN instance_id VARCHAR"))

(defn add-shutdown-token []
  (sql/do-commands "ALTER TABLE instances ADD COLUMN shutdown_token VARCHAR"))

;; migrations mechanics

(defn run-and-record [migration]
  (println "Running migration:" (:name (meta migration)))
  (migration)
  (sql/insert-values "migrations" [:name :created_at]
                     [(str (:name (meta migration)))
                      (java.sql.Timestamp. (System/currentTimeMillis))]))

(defn migrate [& migrations]
  (sql/with-connection db
    (try (sql/create-table "migrations"
                           [:name :varchar "NOT NULL"]
                           [:created_at :timestamp
                            "NOT NULL"  "DEFAULT CURRENT_TIMESTAMP"])
         (catch Exception _))
    (sql/transaction
     (let [has-run? (sql/with-query-results run ["SELECT name FROM migrations"]
                      (set (map :name run)))]
       (doseq [m migrations
               :when (not (has-run? (str (:name (meta m)))))]
         (run-and-record m))))))

(defn -main []
  (migrate #'initial-schema
           #'add-instance-id
           #'add-shutdown-token))