(ns syme.html
  (:require [hiccup.page :refer [html5 doctype include-css]]
            [environ.core :refer [env]]
            [tentacles.repos :as repos]
            [syme.db :as db]
            [clojure.java.jdbc :as sql]))

(defn layout [body]
  (html5
   [:head
    [:meta {:charset "utf-8"}]
    [:title "Syme"]
    (include-css "/stylesheets/style.css"
                 "/stylesheets/base.css"
                 "/stylesheets/skeleton.css")
    (include-css "http://fonts.googleapis.com/css?family=Electrolize")]
   [:body
    [:div#header
     [:h1.container
      [:a {:href "/"} "Syme"]]]
    [:div#content.container body]]))

(defn splash []
  (layout
   [:div
    ;; TODO: state argument here?
    [:a {:href (str"https://github.com/login/oauth/authorize?client_id="
                   (env :oauth-client-id))} "Log in"]]))

(defn dash [username]
  (layout
   [:div
    [:form {:action "/launch" :method :get}
     [:input {:type :text :name "project"}]
     [:input {:type :submit :value "Go"}]]]))

(defn launch-project [username repo-name]
  (let [repo (repos/specific-repo username repo-name)]
    (if (:name repo)
      (layout
       [:div
        [:h3 (:name repo)]
        [:p {:id "desc"} (:description repo)]
        [:form {:action "/launch" :method :post}
         [:input {:type :hidden :name "project" :value repo-name}]
         [:p [:label {:for "invite"}
              "space-separated list of GitHub usernames to invite:"]]
         [:input {:type :text :size 20 :name "invite" :id "invite"}]
         [:p [:label {:for "packages"}
              "space-separated list debian packages to install:"]]
         [:input {:type :text :size 20 :name "packages" :id "packages"}]
         [:p [:label {:for "identity"}
              "AWS Access Key ID"]]
         [:input {:type :text :size 20 :name "identity" :id "identity"}]
         [:p [:label {:for "credential"}
              "AWS Access Secret Key"]]
         [:input {:type :text :size 20 :name "credential" :id "credential"}]
         [:input {:type :submit :value "Launch!"}]]])
      (throw (ex-info "Repository not found" {:status 404})))))

(defn project [username project-name]
  (sql/with-connection db/db
    (if-let [project (db/find username project-name)]
      (layout
       [:div
        [:h3 (:name project)]
        [:p {:id "desc"} (:description project)]
        (if (:ip project)
          [:p {:id "ip"} "IP: " (:ip project)]
          [:p {:id "ip"} "Waiting to boot..."])
        [:p "Invited:"]
        [:ul {:id "invitations"}
         (for [i (:invitees project)]
           [:li [:a {:href (str "https://github.com/" i)} i]])]])
      (throw (ex-info "Repository not found" {:status 404})))))