(ns puppetlabs.ring-middleware.common-test
  (:require [clojure.test :refer :all]
            [puppetlabs.ring-middleware.common :refer :all]))

(defn make-string-inputstream [string]
  (java.io.ByteArrayInputStream. (.getBytes string "UTF-8")))

(deftest prepare-body-test
  (testing "when the request is a DELETE"
    (is (= (prepare-body {:request-method :delete}) nil)))
  (testing "when the body is a stream with content"
    (is (= (prepare-body {:body (make-string-inputstream "I am a body")}) "I am a body")))
  (testing "when the body is a stream without content")
    (is (= (prepare-body {:body (make-string-inputstream "")}) nil)))
