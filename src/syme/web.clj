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
  ;; TODO: heroku config:add REPL_USER=[...] REPL_PASSWORD=[...]
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
  (ANY "/repl" {:as req}
       (drawbridge req))
  (GET "/oauth" [code]
       (if code
         (let [token (get-token code)
               username (get-username token)]
           (assoc (res/redirect "/")
             :session {:token token :username username}))
         {:status 403}))
  (GET "/logout" []
       (assoc (res/redirect "/") :session nil))
  (GET "/project" {{:keys [username]} :session {:keys [project]} :params}
       {:headers {"Content-Type" "text/html"}
        :status 200
        :body (if username
                (html/project username project)
                (res/redirect "/"))})
  (POST "/project" {{:keys [username]} :session
                    params :params}
        (instance/launch username params))
  (GET "/" {{:keys [username]} :session}
       {:headers {"Content-Type" "text/html"}
        :status 200
        :body (if username
                (html/dash username)
                (html/splash))})
  (ANY "*" []
       (route/not-found (slurp (io/resource "404.html")))))

(defn wrap-error-page [handler]
  (fn [req]
    (try (handler req)
         (catch Exception e
           {:status (:status (ex-data e) 500)
            :headers {"Content-Type" "text/html"}
            :body (:body (ex-data e) (slurp (io/resource "500.html")))}))))

(defn -main [& [port]]
  (let [port (Integer. (or port (env :port) 5000))
        store (cookie/cookie-store {:key (env :session-secret)})]
    (jetty/run-jetty (-> #'app
                         (resource/wrap-resource "static")
                         ((if (env :production)
                            wrap-error-page
                            trace/wrap-stacktrace))
                         (site {:session {:store store}}))
                     {:port port :join? false})))

;; For interactive development:
;; (.stop server)
;; (def server (-main))
