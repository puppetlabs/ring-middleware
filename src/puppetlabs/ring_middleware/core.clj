(ns puppetlabs.ring-middleware.core
  (:require [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.ring-middleware.common :as common]
            [puppetlabs.ring-middleware.utils :as utils]
            [puppetlabs.ssl-utils.core :as ssl-utils]
            [ring.middleware.cookies :as cookies]
            [ring.util.response :as rr]
            [schema.core :as schema]
            [slingshot.slingshot :as sling])
  (:import (clojure.lang IFn ExceptionInfo)
           (java.lang Exception)
           (java.util.regex Pattern)
           (java.security.cert X509Certificate)))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Private

(defn sanitize-client-cert
  "Given a ring request, return a map which replaces the :ssl-client-cert with
  just the certificate's Common Name at :ssl-client-cert-cn.  Also, remove the
  copy of the certificate put on the request by TK-auth."
  [req]
  (-> (if-let [client-cert (:ssl-client-cert req)]
        (-> req
            (dissoc :ssl-client-cert)
            (assoc :ssl-client-cert-cn (ssl-utils/get-cn-from-x509-certificate client-cert)))
        req)
      (ks/dissoc-in [:authorization :certificate])))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Middleware

(schema/defn ^:always-validate wrap-request-logging :- IFn
  "A ring middleware that logs the request."
  [handler :- IFn]
  (fn [{:keys [request-method uri] :as req}]
    (log/debug "Processing" request-method uri)
    (log/trace (str "Full request:\n" (ks/pprint-to-string (sanitize-client-cert req))))
    (handler req)))

(schema/defn ^:always-validate wrap-response-logging :- IFn
  "A ring middleware that logs the response."
  [handler :- IFn]
  (fn [req]
    (let [resp (handler req)]
      (log/trace "Computed response:" resp)
      resp)))

(schema/defn ^:always-validate wrap-proxy :- IFn
  "Proxies requests to proxied-path, a local URI, to the remote URI at
  remote-uri-base, also a string."
  [handler :- IFn
   proxied-path :- (schema/either Pattern schema/Str)
   remote-uri-base :- schema/Str
   & [http-opts]]
  (let [proxied-path (if (instance? Pattern proxied-path)
                       (re-pattern (str "^" (.pattern proxied-path)))
                       proxied-path)]
    (cookies/wrap-cookies
     (fn [req]
       (if (or (and (string? proxied-path) (.startsWith ^String (:uri req) (str proxied-path "/")))
               (and (instance? Pattern proxied-path) (re-find proxied-path (:uri req))))
         (common/proxy-request req proxied-path remote-uri-base http-opts)
         (handler req))))))

(schema/defn ^:always-validate wrap-add-cache-headers :- IFn
  "Adds cache control invalidation headers to GET and PUT requests if they are handled by the handler"
  [handler :- IFn]
  (fn [request]
    (let [request-method (:request-method request)
          response       (handler request)]
      (when-not (nil? response)
        (if (or
             (= request-method :get)
             (= request-method :put))
          (assoc-in response [:headers "cache-control"] "private, max-age=0, no-cache")
          response)))))

(schema/defn ^:always-validate wrap-add-x-frame-options-deny :- IFn
  "Adds 'X-Frame-Options: DENY' headers to requests if they are handled by the handler"
  [handler :- IFn]
  (fn [request]
    (let [response (handler request)]
      (when response
        (assoc-in response [:headers "X-Frame-Options"] "DENY")))))

(schema/defn ^:always-validate wrap-with-certificate-cn :- IFn
  "Ring middleware that will annotate the request with an
  :ssl-client-cn key representing the CN contained in the client
  certificate of the request. If no client certificate is present,
  the key's value is set to nil."
  [handler :- IFn]
  (fn [{:keys [ssl-client-cert] :as req}]
    (let [cn  (some-> ssl-client-cert
                      ssl-utils/get-cn-from-x509-certificate)
          req (assoc req :ssl-client-cn cn)]
      (handler req))))

(schema/defn ^:always-validate wrap-data-errors :- IFn
  "A ring middleware that catches a slingshot error thrown by
  throw-data-invalid! or a :kind of slingshot error of one of:
    :request-data-invalid
    :user-data-invalid
    :data-invalid
    :service-status-version-not-found
  logs the error and returns a 400 ring response."
  ([handler :- IFn]
   (wrap-data-errors handler :json))
  ([handler :- IFn
    type :- utils/ResponseType]
   (let [code 400
         response (fn [e]
                    (log/error "Submitted data is invalid:" (:msg e))
                    (case type
                      :json (utils/json-response code e)
                      :plain (utils/plain-response code (:msg e))))]
     (fn [request]
       (sling/try+ (handler request)
                   (catch
                    #(contains? #{:request-data-invalid
                                  :user-data-invalid
                                  :data-invalid
                                  :service-status-version-not-found}
                                (:kind %))
                    e
                     (response e)))))))

(schema/defn ^:always-validate wrap-service-unavailable :- IFn
  "A ring middleware that catches slingshot errors thrown by
  utils/throw-service-unavailabe!, logs the error and returns a 503 ring
  response."
  ([handler :- IFn]
   (wrap-service-unavailable handler :json))
  ([handler :- IFn
    type :- utils/ResponseType]
   (let [code 503
         response (fn [e]
                    (log/error "Service Unavailable:" (:msg e))
                    (case type
                      :json (utils/json-response code e)
                      :plain (utils/plain-response code (:msg e))))]
     (fn [request]
       (sling/try+ (handler request)
                   (catch utils/service-unavailable? e
                     (response e)))))))

(schema/defn ^:always-validate wrap-bad-request :- IFn
  "A ring middleware that catches slingshot errors thrown by
  utils/throw-bad-request!, logs the error and returns a 503 ring
  response."
  ([handler :- IFn]
   (wrap-bad-request handler :json))
  ([handler :- IFn
    type :- utils/ResponseType]
   (let [code 400
         response (fn [e]
                    (log/error "Bad Request:" (:msg e))
                    (case type
                      :json (utils/json-response code e)
                      :plain (utils/plain-response code (:msg e))))]
     (fn [request]
       (sling/try+ (handler request)
                   (catch utils/bad-request? e
                     (response e)))))))

(schema/defn ^:always-validate wrap-schema-errors :- IFn
  "A ring middleware that catches schema errors and returns a 500
  response with the details"
  ([handler :- IFn]
   (wrap-schema-errors handler :json))
  ([handler :- IFn
    type :- utils/ResponseType]
   (let [code 500
         response (fn [e]
                    (let [msg (str "Something unexpected happened: "
                                   (select-keys e [:error :value :type]))]
                      (log/error msg)
                      (case type
                        :json (utils/json-response code
                                                   {:kind :application-error
                                                    :msg msg})
                        :plain (utils/plain-response code msg))))]
     (fn [request]
       (sling/try+ (handler request)
                   (catch utils/schema-error? e
                     (response e)))))))

(schema/defn ^:always-validate wrap-uncaught-errors :- IFn
  "A ring middleware that catches all otherwise uncaught errors and
  returns a 500 response with the error message"
  ([handler :- IFn]
   (wrap-uncaught-errors handler :json))
  ([handler :- IFn
    type :- utils/ResponseType]
   (let [code 500
         response (fn [e]
                    (let [msg (str "Internal Server Error: " e)]
                      (log/error msg)
                      (case type
                        :json (utils/json-response code
                                                   {:kind :application-error
                                                    :msg msg})
                        :plain (utils/plain-response code msg))))]
     (fn [request]
       (sling/try+ (handler request)
                   (catch Exception e
                     (response e)))))))

