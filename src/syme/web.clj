(ns syme.web
  (:require [compojure.core :refer [defroutes GET PUT POST DELETE ANY]]
            [compojure.handler :refer [site]]
            [compojure.route :as route]
            [clojure.java.io :as io]
            [clojure.java.jdbc :as sql]
            [ring.middleware.stacktrace :as trace]
            [ring.middleware.session :as session]
            [ring.middleware.resource :as resource]
            [ring.middleware.session.cookie :as cookie]
            [ring.adapter.jetty :as jetty]
            [ring.util.response :as res]
            [ring.middleware.basic-authentication :as basic]
            [cemerick.drawbridge :as drawbridge]
            [environ.core :refer [env]]
            [clj-http.client :as http]
            [cheshire.core :as json]
            [syme.html :as html]
            [syme.db :as db]
            [syme.instance :as instance]))

(defn- authenticated? [user pass]
  (= [user pass] [(env :repl-user false) (env :repl-password false)]))

(def ^:private drawbridge
  (-> (drawbridge/ring-handler)
      (session/wrap-session)
      (basic/wrap-basic-authentication authenticated?)))

(defn get-token [code]
  (-> (http/post "https://github.com/login/oauth/access_token"
                 {:form-params {:client_id (env :oauth-client-id)
                                :client_secret (env :oauth-client-secret)
                                :code code}
                  :headers {"Accept" "application/json"}})
      (:body) (json/decode true) :access_token))

(defn get-username [token]
  (-> (http/get (str "https://api.github.com/user?access_token=" token)
                {:headers {"accept" "application/json"}})
      (:body) (json/decode true) :login))

(defroutes app
  (GET "/" {{:keys [username]} :session}
       {:headers {"Content-Type" "text/html"}
        :status 200
        :body (html/splash username)})
  (GET "/launch" {{:keys [username] :as session} :session
                  {:keys [project]} :params}
       (if-let [instance (db/find username project)]
         (res/redirect (str "/project/" project))
         (if username
           {:headers {"Content-Type" "text/html"}
            :status 200
            :body (html/launch username (or project (:project session))
                               (:identity session) (:credential session))}
           (assoc (res/redirect (str "https://github.com/login/oauth/authorize?"
                                     "client_id=" (env :oauth-client-id)))
             :session (merge session {:project project})))))
  (POST "/launch" {{:keys [username] :as session} :session
                   {:keys [project] :as params} :params}
        (when-not username
          (throw (ex-info "Must be logged in." {:status 401})))
        (when (db/find username project)
          (throw (ex-info "Already launched" {:status 409})))
        (db/create username project)
        (future (instance/launch username params))
        (assoc (res/redirect (str "/project/" project))
          :session (merge session (select-keys params
                                               [:identity :credential]))))
  (GET "/project/:gh-user/:project" {{:keys [username]} :session
                                     instance :instance}
       (html/instance username instance))
  ;; for polling from JS on instance page
  (GET "/project/:gh-user/:project/status" {instance :instance}
       {:status (if (:ip instance) 200 202)
        :headers {"Content-Type" "application/json"}
        :body (json/encode instance)})
  (DELETE "/project/:gh-user/:project" {{:keys [gh-user project]} :params
                                        {:keys [username identity credential]
                                         :as session} :session
                                         instance :instance}
          (do (instance/halt username {:project (str gh-user "/" project)
                                       :identity identity
                                       :credential credential})
              {:status 200
               :headers {"Content-Type" "application/json"}
               :body (json/encode instance)
               :session (dissoc session :project)}))
  (GET "/oauth" {{:keys [code]} :params session :session}
       (if code
         (let [token (get-token code)
               username (get-username token)]
           (assoc (res/redirect "/launch")
             :session (merge session {:token token :username username})))
         {:status 403}))
  (GET "/logout" []
       (assoc (res/redirect "/") :session nil))
  (ANY "/repl" {:as req}
       (drawbridge req))
  (ANY "*" []
       (route/not-found (slurp (io/resource "404.html")))))

(defn wrap-error-page [handler]
  (fn [req]
    (try (handler req)
         (catch Exception e
           (.printStackTrace e)
           {:status (:status (ex-data e) 500)
            :headers {"Content-Type" "text/html"}
            :body (:body (ex-data e) (slurp (io/resource "500.html")))}))))

(defn wrap-login [handler]
  (fn [req]
    ;; must be authorized or authorizing (or drawbridge)
    (if (or (#{"/" "/launch" "/oauth" "/repl"} (:uri req))
            (:username (:session req)))
      (handler req)
      (throw (ex-info "Must be logged in." {:status 401})))))

(defn wrap-find-instance [handler]
  (fn [req]
    (handler (if-let [project (second (re-find #"/project/(\w+/\w+)" (:uri req)))]
               (if-let [inst (db/find (:username (:session req)) project true)]
                 (assoc req :instance inst)
                 (throw (ex-info "Instance not found" {:status 404})))
               req))))

(defn log [req]
  (println (:request-method req) (:uri req) :session (keys (:session req))))

(defn wrap-logging [handler]
  #(handler (doto % log)))

(defn -main [& [port]]
  (let [port (Integer. (or port (env :port) 5000))
        store (cookie/cookie-store {:key (env :session-secret)})]
    (jetty/run-jetty (-> #'app
                         (wrap-find-instance)
                         (wrap-login)
                         (resource/wrap-resource "static")
                         ((if (env :production)
                            wrap-error-page
                            trace/wrap-stacktrace))
                         wrap-logging
                         (site {:session {:store store}}))
                     {:port port :join? false})))

;; For interactive development:
;; (.stop server)
;; (def server (-main))
