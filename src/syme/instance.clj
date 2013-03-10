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
            [tentacles.orgs :as orgs]
            [tentacles.repos :as repos]
            [clojure.java.io :as io]
            [clojure.java.jdbc :as sql]
            [syme.db :as db]
            [syme.dns :as dns]))

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

(declare get-keys)

(defn get-org-keys [org-name]
  (apply concat (for [member (orgs/members org-name)]
                  (get-keys (:login member) true))))

(defn get-keys [username & [in-org]]
  (try
    (let [keys (-> (http/get (format "https://github.com/%s.keys" username))
                   (:body) (.split "\n"))]
      (if (and (not in-org) (every? empty? keys))
        (get-org-keys username)
        (map (memfn getBytes) keys)))))

(defn subdomain-for [{:keys [owner id]}]
  (format (:subdomain env) owner id))

(defn bootstrap-phase [username project users]
  (let [ip (node/primary-ip (crate/target-node))
        subdomain (subdomain-for (db/find username project))]
    (db/status username project "bootstrapping" {:ip ip})
    (when (:subdomain env)
      (dns/register-hostname subdomain ip))
    (apply admin/automated-admin-user
           "syme" (cons (.getBytes (:public-key env))
                        (mapcat get-keys users)))))

(defn configure-language [language]
  (if-let [language-script (io/resource (str "languages/" language ".sh"))]
    (actions/exec-checked-script "language"
                                 ~(slurp language-script))))

(defn configure-phase [username project gh-user gh-repo]
  (actions/package "git")
  (action/with-action-options {:sudo-user "syme"}
    (actions/exec-checked-script
     "Project clone"
     ~(format "git clone https://github.com/%s.git" project))
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
  (actions/package "tmux")
  (configure-language (:language @gh-repo))
  (actions/exec-checked-script "project-dot-symerc"
                               ~(str "cd ~/" (second (.split project "/")))
                               "[ -r .symerc ] && ./.symerc")
  (let [user-symerc-url (format "https://github.com/%s/.symerc" username)]
    (if (-> user-symerc-url http/get :status (= 200))
      (actions/exec-checked-script "user-dot-symerc"
                                   ~(str "git clone --depth=1" user-symerc-url)
                                   ~(str "cd ~ && .symerc/bootstrap "
                                         project " " (:language @gh-repo))))))

(defn unregister-dns [username project]
  (when-let [{:keys [ip] :as record} (db/find username project)]
    (when (:subdomain env)
      (dns/make-request [(dns/make-change "DELETE" (subdomain-for record) ip)]))))

(defn handle-failure [username project result]
  (if-let [e (:exception @result)]
    (clojure.stacktrace/print-cause-trace e)
    (println "Convergence failure:" @result))
  (unregister-dns username project)
  (println "converge failed")
  (db/status username project "failed"))

(defn launch [username {:keys [project invite identity credential]}]
  (alter-var-root #'pallet.core.user/*admin-user* (constantly admin-user))
  (force write-key-pair)
  (let [group (str username "/" project)
        gh-repo (future (repos/specific-repo username project))
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
                                       :image-id "us-west-2/ami-162ea626"})
                   :phases {:bootstrap (partial bootstrap-phase username
                                                project users)
                            :configure (partial configure-phase username
                                                project gh-user gh-repo)})
                  :user admin-user
                  :compute (compute/instantiate-provider
                            "aws-ec2"
                            :identity identity
                            :credential credential))]
      @result
      (if (pallet.algo.fsmop/failed? result)
        (handle-failure username project result)
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
      :compute (compute/instantiate-provider "aws-ec2"
                                             :identity identity
                                             :credential credential))
    (unregister-dns username project)
    (db/status username project "halted")))
