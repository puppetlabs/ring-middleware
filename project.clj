(defproject puppetlabs/ring-middleware "0.1.2-SNAPSHOT"
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/tools.logging "0.2.6"]
                 [ring "1.3.0"]
                 [puppetlabs/http-client "0.2.3" :exclusions [prismatic/schema commons-io clj-time]]]

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

  :profiles {:dev {:dependencies [[puppetlabs/trapperkeeper-webserver-jetty9 "0.7.1"]
                                  [puppetlabs/trapperkeeper "0.5.0" :classifier "test" :scope "test"
                                   :exclusions [prismatic/schema clj-time]]
                                  [puppetlabs/kitchensink "0.7.2" :classifier "test" :scope "test"
                                   :exclusions [clj-time commons-io]]]}})
