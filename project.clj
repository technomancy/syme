(defproject syme "1.0.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://syme.herokuapp.com"
  :license "Eclipse Public License 1.0"
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [compojure "1.1.1"]
                 [ring/ring-jetty-adapter "1.1.6"]
                 [ring/ring-devel "1.1.0"]
                 [ring-basic-authentication "1.0.1"]
                 [com.cemerick/drawbridge "0.0.6"]
                 [hiccup "1.0.2"]
                 [tentacles "0.2.4"]
                 [clj-http "0.6.3"]
                 [cheshire "5.0.1"]
                 [environ "0.2.1"]
                 [org.cloudhoist/pallet "0.8.0-alpha.8"]
                 [org.cloudhoist/pallet-jclouds "1.5.1"]
                 [org.jclouds/jclouds-allcompute "1.5.2"]
                 [org.jclouds.driver/jclouds-slf4j "1.5.2"
                  ;; the declared version is old and can overrule the
                  ;; resolved version
                  :exclusions [org.slf4j/slf4j-api]]
                 [ch.qos.logback/logback-classic "1.0.0"]
                 [org.jclouds.driver/jclouds-sshj "1.5.2"]]
  :repositories {"sonatype"
                 "http://oss.sonatype.org/content/repositories/releases"}
  :min-lein-version "2.0.0"
  :plugins [[environ/environ.lein "0.2.1"]]
  :hooks [environ.leiningen.hooks]
  :profiles {:production {:env {:production true}
                          :offline true}})