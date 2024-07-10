(ns puppetlabs.ring-middleware.core
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [puppetlabs.i18n.core :refer [trs]]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.ring-middleware.common :as common]
            [puppetlabs.ring-middleware.utils :as utils]
            [puppetlabs.ssl-utils.core :as ssl-utils]
            [ring.middleware.cookies :as cookies]
            [schema.core :as schema]
            [slingshot.slingshot :as sling])
  (:import (clojure.lang IFn)
           (com.fasterxml.jackson.core JsonParseException)
           (java.io ByteArrayOutputStream PrintStream)
           (java.util.regex Pattern)))

(def json-encoding-type "application/json")
;; HTTP error codes
(def InternalServiceError 500)
(def ServiceUnavailable 503)
(def BadRequest 400)
(def NotAcceptable 406)
(def UnsupportedMediaType 415)

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
    (log/debug (trs "Processing {0} {1}" request-method uri))
    (log/trace (format "%s\n%s" (trs "Full request:") (ks/pprint-to-string (sanitize-client-cert req))))
    (handler req)))

(schema/defn ^:always-validate wrap-response-logging :- IFn
  "A ring middleware that logs the response."
  [handler :- IFn]
  (fn [req]
    (let [resp (handler req)]
      (log/trace (trs "Computed response: {0}" resp))
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
          (assoc-in response [:headers "cache-control"] "no-store")
          response)))))

(schema/defn ^:always-validate wrap-add-x-frame-options-deny :- IFn
  "Adds 'X-Frame-Options: DENY' headers to requests if they are handled by the handler"
  [handler :- IFn]
  (fn [request]
    (let [response (handler request)]
      (when response
        (assoc-in response [:headers "X-Frame-Options"] "DENY")))))

(schema/defn ^:always-validate wrap-add-x-content-nosniff :- IFn
  "Adds 'X-Content-Type-Options: nosniff' headers to request."
  [handler :- IFn]
  (fn [request]
    (let [response (handler request)]
      (when response
        (assoc-in response [:headers "X-Content-Type-Options"] "nosniff")))))

(schema/defn ^:always-validate wrap-add-csp :- IFn
  "Adds 'Content-Security-Policy: default-src 'self'' headers to request."
  [handler :- IFn
   csp-val]
  (fn [request]
    (let [response (handler request)]
      (when response
        (assoc-in response [:headers "Content-Security-Policy"] csp-val)))))

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
  logs the error and returns a BadRequest ring response."
  ([handler :- IFn]
   (wrap-data-errors handler :json))
  ([handler :- IFn
    type :- utils/ResponseType]
   (let [response (fn [e]
                    (log/error e (trs "Submitted data is invalid: {0}" (:msg e)))
                    (case type
                      :json (utils/json-response BadRequest e)
                      :plain (utils/plain-response BadRequest (:msg e))))]
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
  utils/throw-service-unavailabe!, logs the error and returns a ServiceUnavailable ring
  response."
  ([handler :- IFn]
   (wrap-service-unavailable handler :json))
  ([handler :- IFn
    type :- utils/ResponseType]
   (let [response (fn [e]
                    (log/error e (trs "Service Unavailable:" (:msg e)))
                    (case type
                      :json (utils/json-response ServiceUnavailable e)
                      :plain (utils/plain-response ServiceUnavailable (:msg e))))]
     (fn [request]
       (sling/try+ (handler request)
                   (catch utils/service-unavailable? e
                     (response e)))))))

(schema/defn ^:always-validate wrap-bad-request :- IFn
  "A ring middleware that catches slingshot errors thrown by
  utils/throw-bad-request!, logs the error and returns a BadRequest ring
  response."
  ([handler :- IFn]
   (wrap-bad-request handler :json))
  ([handler :- IFn
    type :- utils/ResponseType]
   (let [response (fn [e]
                    (log/error e (trs "Bad Request:" (:msg e)))
                    (case type
                      :json (utils/json-response BadRequest e)
                      :plain (utils/plain-response BadRequest (:msg e))))]
     (fn [request]
       (sling/try+ (handler request)
                   (catch utils/bad-request? e
                     (response e)))))))

(schema/defn ^:always-validate wrap-schema-errors :- IFn
  "A ring middleware that catches schema errors and returns a InternalServiceError
  response with the details"
  ([handler :- IFn]
   (wrap-schema-errors handler :json))
  ([handler :- IFn
    type :- utils/ResponseType]
   (let [response (fn [e]
                    (let [msg (trs "Something unexpected happened: {0}"
                                   (select-keys e [:error :value :type]))]
                      (log/error e msg)
                      (case type
                        :json (utils/json-response InternalServiceError
                                                   {:kind :application-error
                                                    :msg msg})
                        :plain (utils/plain-response InternalServiceError msg))))]
     (fn [request]
       (sling/try+ (handler request)
                   (catch utils/schema-error? e
                     (response e)))))))

(defn handle-error-response
  [e type]
  (with-open [baos (ByteArrayOutputStream.)
              print-stream (PrintStream. baos)]
    (.printStackTrace e print-stream)
    (let [msg (trs "Internal Server Error: {0}" (.toString e))]
      (log/error (trs "Internal Server Error: {0}" (.toString baos)))
      (case type
        :json (utils/json-response InternalServiceError
                                   {:kind :application-error
                                    :msg msg})
        :plain (utils/plain-response InternalServiceError msg)))))

(schema/defn ^:always-validate wrap-uncaught-errors :- IFn
  "A ring middleware that catches all otherwise uncaught errors and
  returns a InternalServiceError response with the error message"
  ([handler :- IFn]
   (wrap-uncaught-errors handler :json))
  ([handler :- IFn
    type :- utils/ResponseType]
    (fn [request]
      (try
        (handler request)
        (catch Throwable e
          (handle-error-response e type))))))

(schema/defn ^:always-validate wrap-add-referrer-policy :- IFn
  "Adds referrer policy to the header as 'Referrer-Policy: no-referrer' or 'Referrer-Policy: same-origin'"
  [policy-option :- schema/Str
   handler :- IFn]
  (fn [request]
    (let [response (handler request)]
      (when-not (nil? response)
        (assoc-in response [:headers "Referrer-Policy"] policy-option)))))

(def superwildcard "*/*")

(defn acceptable-content-type
  "Returns a boolean indicating whether the `candidate` mime type
  matches any of those listed in `header`, an Accept header."
  [candidate header]
  (if-not (string? header)
    true
    (let [[prefix] (.split ^String candidate "/")
          wildcard (str prefix "/*")
          types (->> (str/split header #",")
                     (map #(.trim ^String %))
                     (set))]
      (or (types superwildcard)
          (types wildcard)
          (types candidate)))))

(defn wrap-accepts-content-type
  "Ring middleware that requires a request for the wrapped `handler` to accept the
  provided `content-type`. If the content type isn't acceptable, a 406 Not
  Acceptable status is returned, with a message informing the client it must
  accept the content type."
  [handler content-type]
  (fn [{:keys [headers] :as req}]
    (if (acceptable-content-type
          content-type
          (get headers "accept"))
      (handler req)
      (utils/json-response NotAcceptable
                           {:kind    "not-acceptable"
                            :msg     (trs "accept header must include {0}" content-type)}))))

(def wrap-accepts-json
  "Ring middleware which requires a request for `handler` to accept
  application/json as a content type. If the request doesn't accept
  application/json, a 406 Not Acceptable status is returned with an error
  message indicating the problem."
  (fn [handler]
    (wrap-accepts-content-type handler json-encoding-type)))

(defn wrap-content-type
  "Verification for the specified list of content-types."
  [handler content-types]
  {:pre [(coll? content-types)
         (every? string? content-types)]}
  (fn [{:keys [headers] :as req}]
    (if (or (= (:request-method req) :post) (= (:request-method req) :put))
      (let [content-type (get headers "content-type")
            media-type (when-not (nil? content-type)
                        (ks/base-type content-type))]
        (if (or (nil? media-type) (some #{media-type} content-types))
          (handler req)
          (utils/json-response UnsupportedMediaType
                               {:kind    "unsupported-type"
                                :msg     (trs "content-type {0} is not a supported type for request of type {1} at {2}"
                                              media-type (:request-method req) (:uri req))})))
      (handler req))))

(def wrap-content-type-json
  "Ring middleware which requires a request for `handler` to accept
  application/json as a content-type.  If the request doesn't specify
  a content-type of application/json a 415 Unsupported Type status is returned."
  (fn [handler]
    (wrap-content-type handler [json-encoding-type])))

(def wrap-json-parse-exception-handler
  "Ring middleware which catches JsonParseExceptions and returns a predictable result"
  (fn [handler]
    (fn [req]
      (try
        (handler req)
        (catch JsonParseException e
          (log/debug e (trs "Failed to parse json for request of type {0} at {1}" (:request-method req) (:uri req)))
          (utils/json-response BadRequest
                               {:kind    :json-parse-exception
                                :msg     (.getMessage e)}))))))
