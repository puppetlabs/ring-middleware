(ns puppetlabs.ring-middleware.core
  (:require [ring.middleware.cookies :refer [wrap-cookies]]
            [clojure.string :refer [join split]]
            [puppetlabs.http.client.sync :refer [request]]
            [puppetlabs.ring-middleware.common :refer [prepare-cookies]])
  (:import (java.net URI)))

(defn wrap-proxy
  "Proxies requests to proxied-path, a local URI, to the remote URI at
  remote-uri-base, also a string."
  [handler ^String proxied-path remote-uri-base & [http-opts]]
  (wrap-cookies
    (fn [req]
      (if (.startsWith ^String (:uri req) (str proxied-path "/"))
        ; Remove :decompress-body from the options map, as if this is
        ; ever set to true, the response returned to the client making the
        ; proxy request will be truncated
        (let [http-opts (dissoc http-opts :decompress-body)
              uri (URI. remote-uri-base)
              remote-uri (URI. (.getScheme uri)
                               (.getAuthority uri)
                               (str (.getPath uri)
                                    (subs (:uri req) (.length proxied-path)))
                               nil
                               nil)]
          (-> (merge {:method (:request-method req)
                      :url (str remote-uri "?" (:query-string req))
                      :headers (dissoc (:headers req) "host" "content-length")
                      :body (let [body (slurp (:body req))]
                              (if-not (empty? body)
                                body
                                nil))
                      :as :stream
                      :force-redirects true
                      :decompress-body false} http-opts)
              request
              prepare-cookies))
        (handler req)))))
