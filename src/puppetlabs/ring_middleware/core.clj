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

(defn wrap-add-cache-headers
  "Adds cache control invalidation headers to GET and PUT requests if they are handled by the handler"
  [handler]
  (fn [request]
    (let [request-method (:request-method request)
          response       (handler request)]
      (when-not (nil? response)
        (if (or
              (= request-method :get)
              (= request-method :put))
            (assoc-in response [:headers "cache-control"] "private, max-age=0, no-cache")
            response)))))
