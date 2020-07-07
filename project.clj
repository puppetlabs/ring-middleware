(defproject puppetlabs/ring-middleware "1.0.2"
  :dependencies [[org.clojure/clojure]
                 [org.clojure/tools.logging]
                 [cheshire]
                 [prismatic/schema]
                 [ring/ring-core]
                 [slingshot]

                 [puppetlabs/http-client]
                 [puppetlabs/kitchensink]
                 [puppetlabs/ssl-utils]
                 [puppetlabs/i18n]]

  :min-lein-version "2.7.1"

  :parent-project {:coords [puppetlabs/clj-parent "0.4.3"]
                   :inherit [:managed-dependencies]}

  ;; Abort when version ranges or version conflicts are detected in
  ;; dependencies. Also supports :warn to simply emit warnings.
  ;; requires lein 2.2.0+.
  :pedantic? :abort

  :plugins [[lein-parent "0.3.1"]
            [puppetlabs/i18n "0.7.1"]]

  :deploy-repositories [["releases" {:url "https://clojars.org/repo"
                                     :username :env/clojars_jenkins_username
                                     :password :env/clojars_jenkins_password
                                     :sign-releases false}]
                        ["snapshots" "http://nexus.delivery.puppetlabs.net/content/repositories/snapshots/"]]

  :profiles {:dev {:dependencies [[puppetlabs/trapperkeeper-webserver-jetty9]
                                  [puppetlabs/kitchensink nil :classifier "test" :scope "test"]
                                  [puppetlabs/trapperkeeper nil :classifier "test" :scope "test"]
                                  [compojure]]}})
