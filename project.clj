(def clj-version "1.7.0")
(def ks-version "1.3.0")
(def tk-version "1.3.1")

(defproject puppetlabs/ring-middleware "1.0.0"
  :dependencies [[org.clojure/clojure ~clj-version]

                 ;; begin version conflict resolution dependencies
                 [clj-time "0.11.0"]
                 ;; end version conflict resolution dependencies

                 [cheshire "5.6.1"]
                 [org.clojure/tools.logging "0.3.1"]
                 [prismatic/schema "1.1.0"]

                 [puppetlabs/http-client "0.5.0"]
                 [puppetlabs/kitchensink ~ks-version]
                 [puppetlabs/ssl-utils "0.8.1"]
                 [ring "1.4.0"]
                 [slingshot "0.12.2"]]

  ;; Abort when version ranges or version conflicts are detected in
  ;; dependencies. Also supports :warn to simply emit warnings.
  ;; requires lein 2.2.0+.
  :pedantic? :abort

  :plugins [[lein-release "1.0.5"]]

  :deploy-repositories [["releases" {:url "https://clojars.org/repo"
                                     :username :env/clojars_jenkins_username
                                     :password :env/clojars_jenkins_password
                                     :sign-releases false}]
                        ["snapshots" "http://nexus.delivery.puppetlabs.net/content/repositories/snapshots/"]]

  :profiles {:dev {:dependencies [
                                  ;; begin version conflict resolution dependencies
                                  [org.clojure/tools.reader "1.0.0-beta1"]
                                  [org.clojure/tools.macro "0.1.5"]
                                  ;; begin version conflict resolution dependencies

                                  [puppetlabs/trapperkeeper-webserver-jetty9 "1.5.5"]
                                  [puppetlabs/kitchensink ~ks-version :classifier "test" :scope "test"]
                                  [puppetlabs/trapperkeeper ~tk-version :classifier "test" :scope "test"]
                                  [compojure "1.5.0"]]}})
