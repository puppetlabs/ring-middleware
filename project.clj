(defproject puppetlabs/ring-middleware "2.0.5-SNAPSHOT"
  :dependencies [[cheshire]]

  :min-lein-version "2.7.1"

  :parent-project {:coords [puppetlabs/clj-parent "7.3.31"]
                   :inherit [:managed-dependencies]}
  :license {:name "Apache-2.0"
            :url "https://www.apache.org/licenses/LICENSE-2.0.txt"}
          
  ;; Abort when version ranges or version conflicts are detected in
  ;; dependencies. Also supports :warn to simply emit warnings.
  ;; requires lein 2.2.0+.
  :pedantic? :abort

  :plugins [[lein-parent "0.3.7"]
            [puppetlabs/i18n "0.7.1"]]

  :deploy-repositories [["releases" {:url "https://clojars.org/repo"
                                     :username :env/clojars_jenkins_username
                                     :password :env/clojars_jenkins_password
                                     :sign-releases false}]
                        ["snapshots" "http://nexus.delivery.puppetlabs.net/content/repositories/snapshots/"]]

  :profiles {:dev {:dependencies [[com.puppetlabs/trapperkeeper-webserver-jetty10]
                                  [org.bouncycastle/bcpkix-jdk18on]
                                  [puppetlabs/kitchensink nil :classifier "test" :scope "test"]
                                  [puppetlabs/trapperkeeper nil :classifier "test" :scope "test"]
                                  [compojure]]}})
