(defproject syme "1.1.0"
  :description "Instant collaboration on GitHub projects over tmux."
  :url "http://syme.herokuapp.com"
  :license "Eclipse Public License 1.0"
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [com.amazonaws/aws-java-sdk "1.3.33"
                  :exclusions [org.apache.httpcomponents/httpclient
                               commons-codec]]
                 [compojure "1.1.1"]
                 [ring/ring-core "1.2.1"]
                 [ring/ring-jetty-adapter "1.2.0"]
                 [hiccup "1.0.2"]
                 [tentacles "0.2.4"]
                 [clj-http "0.6.3" :exclusions [commons-logging]]
                 [cheshire "5.0.1"]
                 [environ "0.2.1"]
                 [lib-noir "0.3.4"]
                 [postgresql "9.1-901-1.jdbc4"]
                 [org.clojure/java.jdbc "0.2.1"]]
  :uberjar-name "syme-standalone.jar"
  :target-path "target/%s/"
  :min-lein-version "2.0.0"
  :plugins [[environ/environ.lein "0.2.1"]]
  :hooks [environ.leiningen.hooks]
  :profiles {:production {:env {:production true}}})
