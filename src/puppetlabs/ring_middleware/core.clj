(ns puppetlabs.ring-middleware.core
  (:require [ring.middleware.cookies :refer [wrap-cookies]]
            [puppetlabs.ssl-utils.core :refer [get-cn-from-x509-certificate]]
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

(defn wrap-with-certificate-cn
  "Ring middleware that will annotate the request with an
  :ssl-client-cn key representing the CN contained in the client
  certificate of the request. If no client certificate is present,
  the key's value is set to nil."
  [handler]
  (fn [{:keys [ssl-client-cert] :as req}]
    (let [cn  (some-> ssl-client-cert
                      get-cn-from-x509-certificate)
          req (assoc req :ssl-client-cn cn)]
      (handler req))))
