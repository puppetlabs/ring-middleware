(ns puppetlabs.ring-middleware.testutils.common
  (:require [puppetlabs.http.client.sync :as http-client]))

(def default-options-for-https-client
  {:ssl-cert "./dev-resources/config/jetty/ssl/certs/localhost.pem"
   :ssl-key  "./dev-resources/config/jetty/ssl/private_keys/localhost.pem"
   :ssl-ca-cert "./dev-resources/config/jetty/ssl/certs/ca.pem"
   :as :text})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Testing utility functions

(defn http-get
  ([url]
   (http-get url {:as :text}))
  ([url options]
   (http-client/get url options)))

(defn http-post
  ([url]
   (http-post url {:as :text}))
  ([url options]
   (http-client/post url options)))