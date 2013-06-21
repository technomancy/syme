(ns syme.web
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [compojure.route :as route]
            [noir.util.middleware :as noir]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.file-info :as file-info]
            [ring.middleware.resource :as resource]
            [ring.middleware.session.cookie :as cookie]
            [ring.middleware.stacktrace :as trace]
            [ring.util.response :as res]
            [syme.db :as db]
            [syme.dns :as dns]
            [syme.html :as html]
            [syme.instance :as instance]
            [compojure.core :refer [ANY DELETE GET POST routes]]
            [compojure.handler :refer [site]]
            [environ.core :refer [env]]))

;; pre-registered for http://localhost:5000
(def dev-oauth {:client_id "49f1d4f840b69779374c"
                :client_secret "67b9a9fcaffd0c5c47b5dec85223a357ddf3fc46"})

(defn get-token [code]
  (-> (http/post "https://github.com/login/oauth/access_token"
                 {:form-params (merge dev-oauth
                                      {:client_id (env :oauth-client-id)
                                       :client_secret (env :oauth-client-secret)
                                       :code code})
                  :headers {"Accept" "application/json"}})
      (:body) (json/decode true) :access_token))

(defn get-username [token]
  (-> (http/get (str "https://api.github.com/user?access_token=" token)
                {:headers {"accept" "application/json"}})
      (:body) (json/decode true) :login))

(def app
  (routes
   (GET "/" {{:keys [username]} :session}
        {:headers {"Content-Type" "text/html"}
         :status 200
         :body (html/splash username)})
   (GET "/all" {{:keys [username]} :session}
        (html/all username (db/find-all username)))
   (GET "/launch" {{:keys [username] :as session} :session
                   {:keys [project]} :params}
        (if-let [instance (db/find username project)]
          (res/redirect (str "/project/" project))
          (if username
            {:headers {"Content-Type" "text/html"}
             :status 200
             :body (html/launch username (or project (:project session))
                                (:identity session) (:credential session))}
            (assoc (res/redirect html/login-url)
              :session (merge session {:project project})))))
   (POST "/launch" {{:keys [username] :as session} :session
                    {:keys [project] :as params} :params}
         (when-not username
           (throw (ex-info "Must be logged in." {:status 401})))
         (when (db/find username project)
           (throw (ex-info "Already launched." {:status 409})))
         (instance/launch username params)
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
   (POST "/status" {{:keys [token status]} :params}
         (when-let [{:keys [id dns ip]} (db/by-token token)]
           (db/update-status id {:status status})
           (when (= "shutdown" status)
             (dns/deregister-hostname dns ip))
           {:status 200
            :headers {"Content-Type" "text/plain"}
            :body "OK"}))
   (DELETE "/project/:gh-user/:project" {{:keys [gh-user project]} :params
                                         {:keys [username identity credential]
                                          :as session} :session
                                          instance :instance}
           (do (instance/halt username {:project (str gh-user "/" project)
                                        :identity identity
                                        :credential credential
                                        :region (:region instance)})
               {:status 200
                :headers {"Content-Type" "application/json"}
                :body (json/encode instance)
                :session (dissoc session :project)}))
   (GET "/oauth" {{:keys [code]} :params session :session}
        (if code
          (let [token (get-token code)
                username (get-username token)]
            (assoc (res/redirect (if (:project session) "/launch" "/"))
              :session (merge session {:token token :username username})))
          {:status 403}))
   (GET "/logout" []
        (assoc (res/redirect "/") :session nil))
   (GET "/faq" {{:keys [username]} :session}
        {:headers {"Content-Type" "text/html"}
         :status 200
         :body (html/faq username)})
   (ANY "*" []
        (route/not-found
         (html/layout "<h3>404</h3><p>Couldn't find that; sorry.</p>" nil)))))

(defn wrap-error-page [handler]
  (fn [req]
    (try (handler req)
         (catch Exception e
           (.printStackTrace e)
           (let [{:keys [status] :as data :or {status 500}} (ex-data e)
                 m (or (.getMessage e) "Oops; ran into a problem; sorry.")]
             {:status status
              :headers {"Content-Type" "text/html"}
              :body (html/layout (format "<h3>%s</h3><p>%s</p>"
                                         status m) nil)})))))

(defn wrap-login [handler]
  (fn [req]
    (if (or (#{"/" "/launch" "/oauth" "/faq" "/all" "/status"} (:uri req))
            (:username (:session req)))
      (handler req)
      (throw (ex-info "Must be logged in." {:status 401})))))

(defn wrap-find-instance [handler]
  (fn [req]
    (handler (if-let [project (second (re-find #"/project/([^/]+/[^/]+)"
                                               (:uri req)))]
               (if-let [inst (db/find (:username (:session req)) project true)]
                 (assoc req :instance inst)
                 (throw (ex-info "Instance not found." {:status 404})))
               req))))

(defn -main [& [port]]
  (try (db/-main)
       (catch Exception e
         (println (.getMessage e))))
  (let [port (Integer. (or port (env :port) 5000))
        store (cookie/cookie-store {:key (env :session-secret)})]
    (jetty/run-jetty (-> #'app
                         (wrap-find-instance)
                         (wrap-login)
                         (resource/wrap-resource "static")
                         (file-info/wrap-file-info)
                         ((if (env :production)
                            wrap-error-page
                            trace/wrap-stacktrace))
                         ((if (env :production)
                            noir/wrap-force-ssl
                            identity))
                         (site {:session {:store store}}))
                     {:port port :join? false})))

;; For interactive development:
;; (.stop server)
;; (def server (-main))
