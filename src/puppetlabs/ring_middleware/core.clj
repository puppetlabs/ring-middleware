(ns puppetlabs.ring-middleware.core
  (:require [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.ring-middleware.common :as common]
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
;;;; Schemas

(def ResponseType
  (schema/enum :json :plain))

(def RingRequest
  {:uri schema/Str
   (schema/optional-key :ssl-client-cert) (schema/maybe X509Certificate)
   schema/Keyword schema/Any})

(def RingResponse
  {:status schema/Int
   :headers {schema/Str schema/Any}
   :body schema/Any
   schema/Keyword schema/Any})


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
;;;; Helpers

(schema/defn ^:always-validate json-response
  :- RingResponse
  [status :- schema/Int
   body :- schema/Any]
  (-> body
      json/encode
      rr/response
      (rr/status status)
      (rr/content-type "application/json; charset=utf-8")))

(schema/defn ^:always-validate plain-response
  :- RingResponse
  [status :- schema/Int
   body :- schema/Str]
  (-> body
      rr/response
      (rr/status status)
      (rr/content-type "text/plain; charset=utf-8")))

(defn throw-bad-request!
  "Throw a :bad-request type slingshot error with the supplied message"
  [message]
  (sling/throw+  {:type :bad-request
                  :message message}))

(defn bad-request?
  [e]
  "Determine if the supplied slingshot error is for a bad request"
  (when (map? e)
    (= (:type e)
       :bad-request)))

(defn throw-service-unavailable!
  "Throw a :service-unavailable type slingshot error with the supplied message"
  [message]
  (sling/throw+  {:type :service-unavailable
                  :message message}))

(defn service-unavailable?
  [e]
  "Determine if the supplied slingshot error is for an unavailable service"
  (when  (map? e)
    (= (:type e)
       :service-unavailable)))

(defn throw-data-invalid!
  "Throw a :data-invalid type slingshot error with the supplied message"
  [message]
  (sling/throw+  {:type :data-invalid
                  :message message}))

(defn data-invalid?
  [e]
  "Determine if the supplied slingshot error is for invalid data"
  (when  (map? e)
    (= (:type e)
       :data-invalid)))

(defn schema-error?
  [e]
  "Determine if the supplied slingshot error is for a schema mismatch"
  (when (map? e)
    (= (:type e)
       :schema.core/error)))

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
  "A ring middleware that catches slingshot errors with a :type of
  one of:
    :request-data-invalid
    :user-data-invalid
    :data-invalid
    :service-status-version-not-found
  logs the error and returns a 400 ring response."
  ([handler :- IFn]
   (wrap-data-errors handler :json))
  ([handler :- IFn
    type :- ResponseType]
   (let [code 400
         response (fn [e]
                    (log/error "Submitted data is invalid:" (:message e))
                    (case type
                      :json (json-response code {:error e})
                      :plain (plain-response code (:message e))))]
     (fn [request]
       (sling/try+ (handler request)
                   (catch
                    #(contains? #{:request-data-invalid
                                  :user-data-invalid
                                  :data-invalid
                                  :service-status-version-not-found}
                                (:type %))
                    e
                     (response e)))))))

(schema/defn ^:always-validate wrap-service-unavailable :- IFn
  "A ring middleware that catches slingshot errors thrown by
  throw-service-unavailabe!, logs the error and returns a 503 ring response."
  ([handler :- IFn]
   (wrap-service-unavailable handler :json))
  ([handler :- IFn
    type :- ResponseType]
   (let [code 503
         response (fn [e]
                    (log/error "Service Unavailable:" (:message e))
                    (case type
                      :json (json-response code {:error e})
                      :plain (plain-response code (:message e))))]
     (fn [request]
       (sling/try+ (handler request)
                   (catch service-unavailable? e
                     (response e)))))))

(schema/defn ^:always-validate wrap-bad-request :- IFn
  "A ring middleware that catches slingshot errors thrown by
  throw-bad-request!, logs the error and returns a 503 ring response."
  ([handler :- IFn]
   (wrap-bad-request handler :json))
  ([handler :- IFn
    type :- ResponseType]
   (let [code 400
         response (fn [e]
                    (log/error "Bad Request:" (:message e))
                    (case type
                      :json (json-response code {:error e})
                      :plain (plain-response code (:message e))))]
     (fn [request]
       (sling/try+ (handler request)
                   (catch bad-request? e
                     (response e)))))))

(schema/defn ^:always-validate wrap-schema-errors :- IFn
  "A ring middleware that catches schema errors and returns a 500
  response with the details"
  ([handler :- IFn]
   (wrap-schema-errors handler :json))
  ([handler :- IFn
    type :- ResponseType]
   (let [code 500
         response (fn [e]
                    (let [msg (str "Something unexpected happened: "
                                   (select-keys e [:error :value :type]))]
                      (log/error msg)
                      (case type
                        :json (json-response code
                                             {:error
                                              {:type :application-error
                                               :message msg}})
                        :plain (plain-response code msg))))]
     (fn [request]
       (sling/try+ (handler request)
                   (catch schema-error? e
                     (response e)))))))

(schema/defn ^:always-validate wrap-uncaught-errors :- IFn
  "A ring middleware that catches all otherwise uncaught errors and
  returns a 500 response with the error message"
  ([handler :- IFn]
   (wrap-uncaught-errors handler :json))
  ([handler :- IFn
    type :- ResponseType]
   (let [code 500
         response (fn [e]
                    (let [msg (str "Internal Server Error: " e)]
                      (log/error msg)
                      (case type
                        :json (json-response code
                                             {:error
                                              {:type :application-error
                                               :message msg}})
                        :plain (plain-response code msg))))]
     (fn [request]
       (sling/try+ (handler request)
                   (catch Exception e
                     (response e)))))))

