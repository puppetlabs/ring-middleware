(ns puppetlabs.ring-middleware.utils-test
  (:require [cheshire.core :as json]
            [clojure.test :refer :all]
            [puppetlabs.ring-middleware.utils :as utils]))

(deftest json-response-test
  (testing "json response"
    (let [source {:key 1}
          response (utils/json-response 200 source)]
      (testing "has 200 status code"
        (is (= 200 (:status response))))
      (testing "has json content-type"
        (is (re-matches #"application/json.*" (get-in response [:headers "Content-Type"]))))
      (testing "is properly converted to a json string"
        (is (= 1 ((json/parse-string (:body response)) "key")))))))

(deftest plain-response-test
  (testing "json response"
    (let [message "Response message"
          response (utils/plain-response 200 message)]
      (testing "has 200 status code"
        (is (= 200 (:status response))))
      (testing "has plain content-type"
        (is (re-matches #"text/plain.*" (get-in response [:headers "Content-Type"])))))))

