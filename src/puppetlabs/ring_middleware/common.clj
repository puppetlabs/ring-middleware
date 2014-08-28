(ns puppetlabs.ring-middleware.common)

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

; DELETE requests with a body are technically not allowed, and blow up our HTTP client, so filter them out
(defn prepare-body
  [req]
  (if (= (:request-method req) :delete)
    nil
    (let [body (slurp (:body req))]
      (if-not (empty? body)
        body
        nil))))
