(ns syme.db
  (:refer-clojure :exclude [find])
  (:require [clojure.java.jdbc :as sql]
            [clojure.java.io :as io]
            [environ.core :as env]))

(def db (env/env :database-url "postgres://localhost:5432/syme"))

(defn project [owner name description ip]
  (sql/insert-record :projects {:id (str owner "/" name)
                                :description description :ip ip}))

(defn invite [owner project invitee]
  (sql/insert-record :invites {:project (str owner "/" project)
                               :invitee invitee}))

(defn find [username project-name]
  (sql/with-connection db
    (sql/with-query-results project
      [(str "SELECT projects.*, invites.* FROM projects, invites"
            " WHERE projects.id = ?"
            " AND invites.project = projects.id")
       (str username "/" project-name)]
      ;; whatever I suck at sql
      {:name (str username "/" project-name)
       :description (:description (first project))
       :ip (:ip (first project))
       :invitees (mapv :invitee project)})))

;; migrations

(defn initial-schema []
  (sql/create-table "projects"
                    [:id :varchar "PRIMARY KEY"]
                    [:description :text]
                    [:ip :varchar]
                    [:at :timestamp "NOT NULL" "DEFAULT CURRENT_TIMESTAMP"])
  (sql/create-table "invites"
                    [:id :serial "PRIMARY KEY"]
                    [:invitee :varchar "NOT NULL"]
                    [:project :varchar "NOT NULL"]
                    [:at :timestamp "NOT NULL" "DEFAULT CURRENT_TIMESTAMP"]))

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
  (migrate #'initial-schema))