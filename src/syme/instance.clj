(ns syme.instance
  (:require [pallet.core :as pallet]
            [pallet.api :as api]
            [pallet.actions :as actions]
            [pallet.action :as action]
            [pallet.compute :as compute]
            [pallet.node :as node]
            [pallet.crate :as crate]
            [pallet.core.session :as session]
            [pallet.crate.automated-admin-user :as admin]
            [pallet.phase :as phase]
            [clj-http.client :as http]
            [environ.core :refer [env]]
            [tentacles.users :as users]
            [clojure.java.io :as io]
            [clojure.java.jdbc :as sql]
            [syme.db :as db]))

(def pubkey (str (io/file (System/getProperty "user.dir") "keys" "syme.pub")))

(def privkey (str (io/file (System/getProperty "user.dir") "keys" "syme")))

(def admin-user (api/make-user "syme"
                               :public-key-path pubkey
                               :private-key-path privkey))

(def write-key-pair
  (delay
   (.mkdirs (.getParentFile (io/file pubkey)))
   (io/copy (.getBytes (.replaceAll (env :private-key) "\\\\n" "\n"))
            (io/file privkey))
   (io/copy (.getBytes (env :public-key))
            (io/file pubkey))))

(defn get-keys [username]
  (let [keys (-> (http/get (format "https://github.com/%s.keys" username))
                 (:body) (.split "\n"))]
    (map (memfn getBytes) keys)))

(defn bootstrap-phase [username project users]
  (let [ip (node/primary-ip (crate/target-node))]
    (db/status username project "bootstrapping" {:ip ip})
    (apply admin/automated-admin-user
           "syme" (cons (.getBytes (:public-key env))
                        (mapcat get-keys users)))))

(defn configure-phase [username project gh-user]
  (actions/package "git")
  (action/with-action-options {:sudo-user "syme"}
    (actions/exec-checked-script
     "Project clone"
     ~(format "git clone git://github.com/%s.git" project))
    (actions/exec-checked-script
     "gitconfig"
     ~(format "git config --global %s '%s'; git config --global %s '%s'"
              "user.email" (:email @gh-user) "user.name" (:name @gh-user))))
  (actions/remote-file "/etc/motd" :literal true
                       :content (slurp (io/resource "motd")))
  (actions/remote-file "/etc/tmux.conf" :literal true
                       :content (slurp (io/resource "tmux.conf")))
  (actions/remote-file "/usr/local/bin/add-github-user" :mode "0755" :literal true
                       :content (slurp (io/resource "add-github-user")))
  (actions/package "tmux"))

(defn launch [username {:keys [project invite identity credential]}]
  (alter-var-root #'pallet.core.user/*admin-user* (constantly admin-user))
  (force write-key-pair)
  (let [group (str username "/" project)
        gh-user (future (users/user username))
        users (cons username (if (= invite "users to invite")
                               []
                               (.split invite ",? +")))]
    (sql/with-connection db/db
      (doseq [invitee users]
        (db/invite username project invitee)))
    (println "Converging" group "...")
    (let [result (pallet/converge
                  (pallet/group-spec
                   group, :count 1
                   :node-spec (pallet/node-spec
                               :image {:os-family :ubuntu
                                       :image-id "us-east-1/ami-3c994355"})
                   :phases {:bootstrap (partial bootstrap-phase username
                                                project users)
                            :configure (partial configure-phase username
                                                project gh-user)})
                  :user admin-user
                  :compute (compute/compute-service "aws-ec2"
                                                    :identity identity
                                                    :credential credential))]
      @result
      (if (pallet.algo.fsmop/failed? result)
        (do
          (if-let [e (:exception @result)]
            (clojure.stacktrace/print-cause-trace e)
            (println @result))
          (println "converge failed")
          (db/status username project "failed"))
        ;; if we want more granular status:
        ;; <hugod> pallet.event - the log-publisher is what logs the phase fns
        (db/status username project "ready")))))

(defn halt [username {:keys [project identity credential]}]
  (db/status username project "halting")
  (let [group (str username "/" project)]
    (println "Destroying" group "...")
    @(pallet/converge
      (pallet/group-spec
       group, :count 0)
      :compute (compute/compute-service "aws-ec2"
                                        :identity identity
                                        :credential credential))
    (db/status username project "halted")))

(defn nodes [identity credential]
  (pallet.compute/nodes (compute/compute-service "aws-ec2"
                                                 :identity identity
                                                 :credential credential)))