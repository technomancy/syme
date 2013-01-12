(ns syme.html
  (:require [hiccup.page :refer [html5 doctype include-css]]
            [environ.core :refer [env]]
            [tentacles.repos :as repos]
            [tentacles.users :as users]
            [syme.db :as db]
            [clojure.java.jdbc :as sql]))

(defn layout [body username & [project]]
  (html5
   [:head
    [:meta {:charset "utf-8"}]
    [:title (if project (str project " - Syme") "Syme")]
    (include-css "/stylesheets/style.css"
                 "/stylesheets/base.css"
                 "/stylesheets/skeleton.css")
    (include-css "http://fonts.googleapis.com/css?family=Passion+One:700")]
   [:body
    [:div#header
     [:h1.container "Syme"]]
    [:div#content.container body
     [:div#footer
      [:p (if username
            [:span [:a {:href "/logout"} "Log out"] " | "])
        "Get " [:a {:href "https://github.com/technomancy/syme"}
                     "the source"] "."]]]]))

(defn splash [username]
  (layout
   [:div
    [:form {:action "/launch" :method :get :id "splash"}
     [:input {:type :submit :value "Collaborate on a GitHub project"
              :style "float: right; margin-top: 2px;"}]
     [:input {:type :text :name "project" :value "user/project"
              :style "width: 220px; font-size: 100%; font-weight: bold;"
              :onfocus "if(this.value==this.defaultValue) this.value='';"
              :onblur "if(this.value=='') this.value='user/project';"}]]
    ;; TODO: display active instances
    ] username))

(defn launch [username repo-name identity credential]
  (let [repo (apply repos/specific-repo (.split repo-name "/"))]
    (if (:name repo)
      (layout
       [:div
        [:h3.project [:a {:href (:html_url repo)} repo-name]]
        [:p {:id "desc"} (:description repo)]
        [:hr]
        [:form {:action "/launch" :method :post}
         [:input {:type :hidden :name "project" :value repo-name}]
         [:input {:type :text :name "invite" :id "invite"
                  :value "users to invite"
                  :onfocus "if(this.value==this.defaultValue) this.value='';"
                  :onblur "if(this.value=='') this.value='users to invite';"}]
         [:input {:type :text :name "identity" :id "identity"
                  :value (or identity "AWS Access Key")
                  :onfocus (if-not identity
                             "if(this.value==this.defaultValue) this.value='';")
                  :onblur "if(this.value=='') this.value='AWS Access Key';"}]
         [:input {:type :text :style "width: 320px"
                  :name "credential" :id "credential"
                  :value (or credential "AWS Secret Key")
                  :onfocus (if-not credential
                             "if(this.value==this.defaultValue) this.value='';")
                  :onblur "if(this.value=='') this.value='AWS Secret Key';"}]
         [:hr]
         [:input {:type :submit :value "Launch!"}]]]
       username repo-name)
      (throw (ex-info "Repository not found" {:status 404})))))

(defn status-style [status]
  (str "color: " ({"bootstrapping" "orange"
                   "ready" "green"
                   "halted" "red"} status "yellow")))

(defonce icon (memoize (comp :avatar_url users/user)))

(defn instance [username gh-user project-name]
  (sql/with-connection db/db
    (if-let [instance (db/find username (str gh-user "/" project-name))]
      (layout
       [:div
        [:p {:id "status" :style (status-style  (:status instance))}
         (:status instance)]
        [:h3.project [:a {:href (format "https://github.com/%s/%s"
                                        gh-user project-name)}
                      (str gh-user "/" project-name)]]
        [:p {:id "desc"} (:description instance)]
        [:hr]
        (if (:ip instance)
          [:p {:id "ip" :class (:status instance)}
           [:tt "ssh syme@" (:ip instance)]]
          ;; TODO: JS to update status here periodically.
          [:p "Waiting to boot... refresh in a minute or two."])
        [:hr]
        [:ul {:id "users"}
         (for [u (cons username (:invitees instance))]
           [:li [:a {:href (str "https://github.com/" u)}
                 [:img {:src (icon u) :alt u :title u}]]])]]
       username (str gh-user "/" project-name))
      (throw (ex-info "Repository not found" {:status 404})))))
