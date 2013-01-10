(ns syme.instance
  (:require [pallet.core :as pallet]
            [pallet.api :as api]
            [pallet.actions :as actions]
            [pallet.action :as action]
            [pallet.compute :as compute]
            [pallet.crate :as crate]
            [pallet.crate.automated-admin-user :as admin]
            [pallet.phase :as phase]
            [clj-http.client :as http]
            [environ.core :refer [env]]
            [clojure.java.io :as io]))

(def pubkey (str (io/file (System/getProperty "user.dir")
                          "data" "keys" "syme.pub")))

(def privkey (str (io/file (System/getProperty "user.dir")
                           "data" "keys" "syme")))

(def user (api/make-user "syme"
                         :public-key-path pubkey
                         :private-key-path privkey))

(def write-key-pair
  (delay
   (.mkdirs (io/file "data" "keys"))
   (io/copy (.getBytes (env :private-key)) (io/file privkey))
   (io/copy (.getBytes (env :public-key)) (io/file pubkey))))

(defn get-keys [username]
  (let [keys (-> (http/get (format "https://github.com/%s.keys" username))
                 (:body) (.split "\n"))]
    (map (memfn getBytes) keys)))

(defn configure-phase [username project invite packages]
  (admin/automated-admin-user
   "syme" (.getBytes (:public-key env)))
  (doseq [u (cons username (.split invite ",? +"))]
    (println "Adding admin" u)
    (apply admin/automated-admin-user u (get-keys u)))
  (doseq [p (cons "git" (.split packages ",? +"))]
    (actions/package p))
  (action/with-action-options {:sudo-user username
                               :script-prefix :no-prefix}
    (actions/exec-checked-script
     "Project clone"
     ~(format "sudo -iu %s git clone git://github.com/%s/%s.git"
              username username project))))

(defn launch [username {:keys [project invite identity credential
                               packages compute] :as opts}]
  (force write-key-pair)
  (alter-var-root #'pallet.core.user/*admin-user* (constantly user))
  (let [group (str username "/" project)]
    (pallet/converge
     (pallet/group-spec
      group, :count 1
      :node-spec (pallet/node-spec :image {:os-family :ubuntu
                                           :image-id "us-east-1/ami-3c994355"})
      :phases {:bootstrap (fn []
                            ;; (crate/primary-ip (first (crate/nodes-for-group group)))
                            (admin/automated-admin-user
                             "syme" (.getBytes (:public-key env))))
               :configure (partial configure-phase username project
                                   invite packages)})
     :compute (compute/compute-service (or compute "aws-ec2")
                                       :identity identity
                                       :credential credential))))
