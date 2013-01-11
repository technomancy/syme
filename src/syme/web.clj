(ns syme.web
  (:require [compojure.core :refer [defroutes GET PUT POST DELETE ANY]]
            [compojure.handler :refer [site]]
            [compojure.route :as route]
            [clojure.java.io :as io]
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
       (if username
         {:headers {"Content-Type" "text/html"}
          :status 200
          :body (html/launch username (or project (:project session))
                             (:identity session) (:credential session))}
         (assoc (res/redirect (str "https://github.com/login/oauth/authorize?"
                                   "client_id=" (env :oauth-client-id)))
           :session (merge session {:project project}))))
  (POST "/launch" {{:keys [username] :as session} :session
                   params :params}
        (def ppp params)
        (future (instance/launch username params))
        (assoc (res/redirect (str "/project/" (:project params)))
          :session (merge session (select-keys params
                                               [:identity :credential]))))
  (GET "/project/:gh-user/:project" {{:keys [username]} :session
                                     {:keys [gh-user project]} :params}
       (html/instance username gh-user project))
  ;; TODO: status endpoint
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

(defn log [req]
  #_(println (:request-method req) (:uri req) :session (keys (:session req))))

(defn wrap-logging [handler]
  #(handler (doto % log)))

(defn -main [& [port]]
  (let [port (Integer. (or port (env :port) 5000))
        store (cookie/cookie-store {:key (env :session-secret)})]
    (jetty/run-jetty (-> #'app
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
