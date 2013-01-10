(ns syme.instance
  (:require [pallet.core :as pallet]
            [pallet.api :as api]
            [pallet.actions :as actions]
            [pallet.configure :as config]
            [pallet.crate.automated-admin-user :as admin]
            [pallet.phase :as phase]
            [clj-http.client :as http]
            [environ.core :refer [env]]
            [clojure.java.io :as io]))

(def pubkey (str (io/file (System/getProperty "user.dir")
                          "data" "keys" "syme.pub")))

(def privkey (str (io/file (System/getProperty "user.dir")
                           "data" "keys" "syme")))

(def write-key-pair
  (delay
   (.mkdirs (io/file "data" "keys"))
   (io/copy (.getBytes (env :private-key)) (io/file privkey))
   (io/copy (.getBytes (env :public-key)) (io/file pubkey))))

(defn get-keys [username]
  (let [keys (-> (http/get (format "https://github.com/%s.keys" username))
                 (:body) (.split "\n"))]
    (map (memfn getBytes) keys)))

(defn launch [username {:keys [project invite identity credential]}]
  (force write-key-pair)
  (pallet/converge
   (pallet/group-spec
    (str username "/" project)
    :count 1
    :node-spec (pallet/node-spec
                :image {:os-family :ubuntu
                        :image-id "us-east-1/ami-3c994355"})
    :phases {:bootstrap #(admin/automated-admin-user
                          "syme" (.getBytes (:public-key env)))
             :configure (fn []
                          (doseq [u (cons username (.split invite ",? +"))]
                            (apply admin/automated-admin-user username
                                   (get-keys username)))
                          ;; TODO: need to do these as primary user
                          (actions/exec-script
                           (format "git clone git://github.com/%s/%s.git"
                                   username project)))})
   ;; apparently :user is broken in 0.8.0-alpha
   :user (api/make-user "syme"
                        :public-key-path pubkey
                        :private-key-path privkey)
   :compute (config/compute-service "aws-ec2"
                                    :identity identity
                                    :credential credential)))
