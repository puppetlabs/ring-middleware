(ns puppetlabs.ring-middleware.common
  (:require [clojure.string :refer [replace-first]]
            [clojure.tools.logging :as log]
            [puppetlabs.http.client.sync :refer [request]]
            [puppetlabs.i18n.core :refer [trs]])
  (:import (java.net URI)
           (java.util.regex Pattern)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Private utility functions

(defn prepare-cookies
  "Removes the :domain and :secure keys and converts the :expires key (a Date)
  to a string in the ring response map resp. Returns resp with cookies properly
  munged."
  [resp]
  (let [prepare #(-> (update-in % [1 :expires] str)
                     (update-in [1] dissoc :domain :secure))]
    (assoc resp :cookies (into {} (map prepare (:cookies resp))))))

(defn strip-trailing-slash
  [url]
  (if (.endsWith url "/")
    (.substring url 0 (- (count url) 1))
    url))

(defn proxy-request
  [req proxied-path remote-uri-base & [http-opts]]
  ; Remove :decompress-body from the options map, as if this is
  ; ever set to true, the response returned to the client making the
  ; proxy request will be truncated
  (let [http-opts (dissoc http-opts :decompress-body)
        uri (URI. (strip-trailing-slash remote-uri-base))
        remote-uri (URI. (.getScheme uri)
                         (.getAuthority uri)
                         (str (.getPath uri)
                              (if (instance? Pattern proxied-path)
                                (:uri req)
                                (replace-first (:uri req) proxied-path "")))
                         nil
                         nil)
        response (-> (merge {:method          (:request-method req)
                             :url             (str remote-uri "?" (:query-string req))
                             :headers         (dissoc (:headers req) "host" "content-length")
                             :body            (not-empty (slurp (:body req)))
                             :as              :stream
                             :force-redirects false
                             :follow-redirects false
                             :decompress-body false}
                            http-opts)
                     request
                     prepare-cookies)]
    (log/debug (trs "Proxying request to {0} to remote url {1}. Remote server responded with status {2}" (:uri req) (str remote-uri) (:status response)))
    response))
