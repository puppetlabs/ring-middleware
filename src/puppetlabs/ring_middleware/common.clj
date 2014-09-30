(ns puppetlabs.ring-middleware.common
  (:require [clojure.tools.logging :as log]
            [clojure.string :refer [join split replace-first]]
            [puppetlabs.http.client.sync :refer [request]])
  (:import (java.net URI)))

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

(defn proxy-request
  [req proxied-path remote-uri-base & [http-opts]]
  ; Remove :decompress-body from the options map, as if this is
  ; ever set to true, the response returned to the client making the
  ; proxy request will be truncated
  (let [http-opts (dissoc http-opts :decompress-body)
        uri (URI. remote-uri-base)
        remote-uri (URI. (.getScheme uri)
                         (.getAuthority uri)
                         (str (.getPath uri)
                              (replace-first (:uri req) proxied-path ""))
                         nil
                         nil)
        response (-> (merge {:method          (:request-method req)
                             :url             (str remote-uri "?" (:query-string req))
                             :headers         (dissoc (:headers req) "host" "content-length")
                             :body            (let [body (slurp (:body req))]
                                                (if-not (empty? body)
                                                  body
                                                  nil))
                             :as              :stream
                             :force-redirects true
                             :decompress-body false}
                            http-opts)
                     request
                     prepare-cookies)]
    (log/debug "Proxying request to" (:uri req) "to remote url" (str remote-uri) ". Remote server responded with status" (:status response))
    response))
