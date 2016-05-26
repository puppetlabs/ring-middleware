(ns puppetlabs.ring-middleware.utils
  (:require [schema.core :as schema]
            [ring.util.response :as rr]
            [slingshot.slingshot :as sling]
            [cheshire.core :as json])
  (:import (java.security.cert X509Certificate)))

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
  (sling/throw+  {:kind :bad-request
                  :msg message}))

(defn bad-request?
  [e]
  "Determine if the supplied slingshot error is for a bad request"
  (when (map? e)
    (= (:kind e)
       :bad-request)))

(defn throw-service-unavailable!
  "Throw a :service-unavailable type slingshot error with the supplied message"
  [message]
  (sling/throw+  {:kind :service-unavailable
                  :msg message}))

(defn service-unavailable?
  [e]
  "Determine if the supplied slingshot error is for an unavailable service"
  (when  (map? e)
    (= (:kind e)
       :service-unavailable)))

(defn throw-data-invalid!
  "Throw a :data-invalid type slingshot error with the supplied message"
  [message]
  (sling/throw+  {:kind :data-invalid
                  :msg message}))

(defn data-invalid?
  [e]
  "Determine if the supplied slingshot error is for invalid data"
  (when  (map? e)
    (= (:kind e)
       :data-invalid)))

(defn schema-error?
  [e]
  "Determine if the supplied slingshot error is for a schema mismatch"
  (when (map? e)
    (= (:type e)
       :schema.core/error)))

