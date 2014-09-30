(ns puppetlabs.ring-middleware.core
  (:require [ring.middleware.cookies :refer [wrap-cookies]]
            [puppetlabs.ring-middleware.common :refer [proxy-request]]))

(defn wrap-proxy
  "Proxies requests to proxied-path, a local URI, to the remote URI at
  remote-uri-base, also a string."
  [handler proxied-path remote-uri-base & [http-opts]]
  (let [proxied-path (if (instance? java.util.regex.Pattern proxied-path)
                       (re-pattern (str "^" (.pattern proxied-path)))
                       proxied-path)]
       (wrap-cookies
         (fn [req]
             (if (or (and (string? proxied-path) (.startsWith ^String (:uri req) (str proxied-path "/")))
                     (and (instance? java.util.regex.Pattern proxied-path) (re-find proxied-path (:uri req))))
               (proxy-request req proxied-path remote-uri-base http-opts)
               (handler req))))))
