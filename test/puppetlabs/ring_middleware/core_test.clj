(ns puppetlabs.ring-middleware.core-test
  (:require [cheshire.core :as json]
            [clojure.test :refer :all]
            [compojure.core :refer :all]
            [compojure.handler :as handler]
            [compojure.route :as route]
            [puppetlabs.ring-middleware.core :as core]
            [puppetlabs.ring-middleware.testutils.common :refer :all]
            [puppetlabs.ssl-utils.core :refer [pem->cert]]
            [puppetlabs.ssl-utils.simple :as ssl-simple]
            [puppetlabs.trapperkeeper.app :refer [get-service]]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :refer :all]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer [with-app-with-config]]
            [puppetlabs.trapperkeeper.testutils.logging :as logutils]
            [ring.util.response :as rr]
            [schema.core :as schema]
            [slingshot.slingshot :as slingshot]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Testing Helpers


(def WackSchema
  [schema/Str])

(schema/defn ^:always-validate cause-schema-error
  [request :- WackSchema]
  (throw (IllegalStateException. "The test should have never gotten here...")))

(defn throwing-handler
  [atype msg]
  (fn [_] (slingshot/throw+ {:type atype :message msg})))

(defn basic-request
  ([] (basic-request "foo-agent" :get "https://example.com"))
  ([subject method uri]
   {:request-method method
    :uri uri
    :ssl-client-cert (:cert (ssl-simple/gen-self-signed-cert subject 1))
    :authorization {:certificate "foo"}}))

(defn post-target-handler
  [req]
  (if (= (:request-method req) :post)
    {:status 200 :body (slurp (:body req))}
    {:status 404 :body "Z'oh"}))

(defn proxy-target-handler
  [req]
  (condp = (:uri req)
    "/hello"                 {:status 302 :headers {"Location" "/hello/world"}}
    "/hello/"                {:status 302 :headers {"Location" "/hello/world"}}
    "/hello/world"           {:status 200 :body "Hello, World!"}
    "/hello/wrong-host"      {:status 302 :headers {"Location" "http://localhost:4/fake"}}
    "/hello/fully-qualified" {:status 302 :headers {"Location" "http://localhost:9000/hello/world"}}
    "/hello/different-path"  {:status 302 :headers {"Location" "http://localhost:9000/different/"}}
    {:status 404 :body "D'oh"}))

(defn non-proxy-target
  [_]
  {:status 200 :body "Non-proxied path"})

(def gzip-body
  (apply str (repeat 1000 "f")))

(defn proxy-gzip-response
  [_]
  (-> gzip-body
      (rr/response)
      (rr/status 200)
      (rr/content-type "text/plain")
      (rr/charset "UTF-8")))

(defn proxy-error-handler
  [_]
  {:status 404 :body "N'oh"})

(defn proxy-regex-response
  [req]
  {:status 200 :body (str "Proxied to " (:uri req))})

(defroutes fallthrough-routes
  (GET "/hello/world" [] "Hello, World! (fallthrough)")
  (GET "/goodbye/world" [] "Goodbye, World! (fallthrough)")
  (route/not-found "Not Found (fallthrough)"))

(def proxy-regex-fallthrough
  (handler/site fallthrough-routes))

(def proxy-wrapped-app
  (-> proxy-error-handler
      (core/wrap-proxy "/hello-proxy" "http://localhost:9000/hello")))

(def proxy-wrapped-app-ssl
  (-> proxy-error-handler
      (core/wrap-proxy "/hello-proxy" "https://localhost:9001/hello"
                  {:ssl-cert "./dev-resources/config/jetty/ssl/certs/localhost.pem"
                   :ssl-key  "./dev-resources/config/jetty/ssl/private_keys/localhost.pem"
                   :ssl-ca-cert "./dev-resources/config/jetty/ssl/certs/ca.pem"})))

(def proxy-wrapped-app-redirects
  (-> proxy-error-handler
      (core/wrap-proxy "/hello-proxy" "http://localhost:9000/hello"
                  {:force-redirects true
                   :follow-redirects true})))

(def proxy-wrapped-app-regex
  (-> proxy-regex-fallthrough
      (core/wrap-proxy #"^/([^/]+/certificate.*)$" "http://localhost:9000/hello")))

(def proxy-wrapped-app-regex-alt
  (-> proxy-regex-fallthrough
      (core/wrap-proxy #"/hello-proxy" "http://localhost:9000/hello")))

(def proxy-wrapped-app-regex-no-prepend
  (-> proxy-regex-fallthrough
      (core/wrap-proxy #"^/([^/]+/certificate.*)$" "http://localhost:9000")))

(def proxy-wrapped-app-regex-trailing-slash
  (-> proxy-regex-fallthrough
      (core/wrap-proxy #"^/([^/]+/certificate.*)$" "http://localhost:9000/")))

(defmacro with-target-and-proxy-servers
  [{:keys [target proxy proxy-handler ring-handler endpoint target-endpoint]} & body]
  `(with-app-with-config proxy-target-app#
     [jetty9-service]
     {:webserver ~target}
     (let [target-webserver# (get-service proxy-target-app# :WebserverService)]
       (add-ring-handler
         target-webserver#
         ~ring-handler
         ~target-endpoint)
       (add-ring-handler
         target-webserver#
         non-proxy-target
         "/different")
       (add-ring-handler
         target-webserver#
         post-target-handler
         "/hello/post/"))
       (with-app-with-config proxy-app#
         [jetty9-service]
         {:webserver ~proxy}
         (let [proxy-webserver# (get-service proxy-app# :WebserverService)]
           (add-ring-handler proxy-webserver# ~proxy-handler ~endpoint))
         ~@body)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Core Helpers

(deftest json-response-test
  (testing "json response"
    (let [source {:key 1}
          response (core/json-response 200 source)]
      (testing "has 200 status code"
        (is (= 200 (:status response))))
      (testing "has json content-type"
        (is (re-matches #"application/json.*" (get-in response [:headers "Content-Type"]))))
      (testing "is properly converted to a json string"
        (is (= 1 ((json/parse-string (:body response)) "key")))))))

(deftest plain-response-test
  (testing "json response"
    (let [message "Response message"
          response (core/plain-response 200 message)]
      (testing "has 200 status code"
        (is (= 200 (:status response))))
      (testing "has plain content-type"
        (is (re-matches #"text/plain.*" (get-in response [:headers "Content-Type"])))))))


(deftest sanitize-client-cert-test
  (testing "sanitize-client-cert"
    (let [subject "foo-client"
          cert (:cert (ssl-simple/gen-self-signed-cert subject 1))
          request {:ssl-client-cert cert :authorization {:certificate "stuff"}}
          response (core/sanitize-client-cert request)]
      (testing "adds the CN at :ssl-client-cert-cn"
        (is (= subject (response :ssl-client-cert-cn))))
      (testing "removes :ssl-client-cert key from response"
        (is (nil? (response :ssl-client-cert))))
      (testing "remove tk-auth cert info"
        (is (nil? (get-in response [:authorization :certificate])))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Core Middleware

(deftest test-proxy
  (let [common-ssl-config {:ssl-cert    "./dev-resources/config/jetty/ssl/certs/localhost.pem"
                           :ssl-key     "./dev-resources/config/jetty/ssl/private_keys/localhost.pem"
                           :ssl-ca-cert "./dev-resources/config/jetty/ssl/certs/ca.pem"}]

    (testing "basic proxy support"
      (with-target-and-proxy-servers
        {:target        {:host "0.0.0.0"
                         :port 9000}
         :proxy         {:host "0.0.0.0"
                         :port 10000}
         :proxy-handler proxy-wrapped-app
         :ring-handler  proxy-target-handler
         :endpoint      "/hello-proxy"
         :target-endpoint "/hello"}
        (let [response (http-get "http://localhost:9000/hello/world")]
          (is (= (:status response) 200))
          (is (= (:body response) "Hello, World!")))
        (let [response (http-get "http://localhost:10000/hello-proxy/world")]
          (is (= (:status response) 200))
          (is (= (:body response) "Hello, World!")))
        (let [response (http-get "http://localhost:10000/hello-proxy/world" {:as :stream})]
          (is (= (slurp (:body response)) "Hello, World!")))
        (let [response (http-post "http://localhost:10000/hello-proxy/post/" {:as :stream :body "I'm posted!"})]
          (is (= (:status response) 200))
          (is (= (slurp (:body response)) "I'm posted!")))))

    (testing "basic https proxy support"
      (with-target-and-proxy-servers
        {:target        (merge common-ssl-config
                               {:ssl-host "0.0.0.0"
                                :ssl-port 9001})
         :proxy         (merge common-ssl-config
                               {:ssl-host "0.0.0.0"
                                :ssl-port 10001})
         :proxy-handler proxy-wrapped-app-ssl
         :ring-handler  proxy-target-handler
         :endpoint      "/hello-proxy"
         :target-endpoint "/hello"}
        (let [response (http-get "https://localhost:9001/hello/world" default-options-for-https-client)]
          (is (= (:status response) 200))
          (is (= (:body response) "Hello, World!")))
        (let [response (http-get "https://localhost:10001/hello-proxy/world" default-options-for-https-client)]
          (is (= (:status response) 200))
          (is (= (:body response) "Hello, World!")))))

    (testing "basic http->https proxy support"
      (with-target-and-proxy-servers
        {:target        (merge common-ssl-config
                               {:ssl-host "0.0.0.0"
                                :ssl-port 9001})
         :proxy         {:host "0.0.0.0"
                         :port 10000}
         :proxy-handler proxy-wrapped-app-ssl
         :ring-handler  proxy-target-handler
         :endpoint      "/hello-proxy"
         :target-endpoint "/hello"}
        (let [response (http-get "https://localhost:9001/hello/world" default-options-for-https-client)]
          (is (= (:status response) 200))
          (is (= (:body response) "Hello, World!")))
        (let [response (http-get "http://localhost:10000/hello-proxy/world")]
          (is (= (:status response) 200))
          (is (= (:body response) "Hello, World!")))))

    (testing "basic https->http proxy support"
      (with-target-and-proxy-servers
        {:target        {:host "0.0.0.0"
                         :port 9000}
         :proxy         (merge common-ssl-config
                               {:ssl-host "0.0.0.0"
                                :ssl-port 10001})
         :proxy-handler proxy-wrapped-app
         :ring-handler  proxy-target-handler
         :endpoint      "/hello-proxy"
         :target-endpoint "/hello"}
        (let [response (http-get "http://localhost:9000/hello/world")]
          (is (= (:status response) 200))
          (is (= (:body response) "Hello, World!")))
        (let [response (http-get "https://localhost:10001/hello-proxy/world" default-options-for-https-client)]
          (is (= (:status response) 200))
          (is (= (:body response) "Hello, World!")))))
    (testing "redirect test with proxy"
      (with-target-and-proxy-servers
        {:target       {:host "0.0.0.0"
                        :port 9000}
         :proxy        {:host "0.0.0.0"
                        :port 10000}
        :proxy-handler proxy-wrapped-app
        :ring-handler  proxy-target-handler
        :endpoint      "/hello-proxy"
        :target-endpoint "/hello"}
        (let [response (http-get "http://localhost:9000/hello")]
          (is (= (:status response) 200))
          (is (= (:body response) "Hello, World!")))
        (let [response (http-get "http://localhost:9000/hello/world")]
          (is (= (:status response) 200))
          (is (= (:body response) "Hello, World!")))
        (let [response (http-get "http://localhost:10000/hello-proxy/"
                                 {:follow-redirects false
                                  :as :text})]
          (is (= (:status response) 302))
          (is (= "/hello/world" (get-in response [:headers "location"]))))
        (let [response (http-post "http://localhost:10000/hello-proxy/"
                                 {:follow-redirects false
                                  :as :text})]
          (is (= (:status response) 302))
          (is (= "/hello/world" (get-in response [:headers "location"]))))
        (let [response (http-get "http://localhost:10000/hello-proxy/world")]
          (is (= (:status response) 200))
          (is (= (:body response) "Hello, World!")))))

    (testing "proxy redirect succeeds on POST if :force-redirects set true"
      (with-target-and-proxy-servers
        {:target        {:host "0.0.0.0"
                         :port 9000}
         :proxy         {:host "0.0.0.0"
                         :port 10000}
         :proxy-handler proxy-wrapped-app-redirects
         :ring-handler  proxy-target-handler
         :endpoint      "/hello-proxy"
         :target-endpoint "/hello"}
        (let [response (http-get "http://localhost:10000/hello-proxy/"
                                 {:follow-redirects false
                                  :as :text})]
          (is (= (:status response) 200))
          (is (= (:body response) "Hello, World!")))
        (let [response (http-post "http://localhost:10000/hello-proxy/"
                                 {:follow-redirects false})]
          (is (= (:status response) 200)))))

    (testing "redirect test with fully qualified url, correct host, and proxied path"
      (with-target-and-proxy-servers
        {:target       {:host "0.0.0.0"
                        :port 9000}
         :proxy        {:host "0.0.0.0"
                        :port 10000}
         :proxy-handler proxy-wrapped-app-redirects
         :ring-handler  proxy-target-handler
         :endpoint      "/hello-proxy"
         :target-endpoint "/hello"}
        (let [response (http-get "http://localhost:10000/hello-proxy/fully-qualified"
                                 {:follow-redirects false
                                  :as :text})]
          (is (= (:status response) 200))
          (is (= (:body response) "Hello, World!")))))

    (testing "redirect test with correct host on non-proxied path"
      (with-target-and-proxy-servers
        {:target {:host "0.0.0.0"
                  :port 9000}
         :proxy  {:host "0.0.0.0"
                  :port 10000}
         :proxy-handler proxy-wrapped-app-redirects
         :ring-handler  proxy-target-handler
         :endpoint      "/hello-proxy"
         :target-endpoint "/hello"}
        (let [response (http-get "http://localhost:9000/different")]
          (is (= (:status response) 200))
          (is (= (:body response) "Non-proxied path")))
        (let [response (http-get "http://localhost:10000/different")]
          (is (= (:status response) 404)))
        (let [response (http-get "http://localhost:10000/hello-proxy/different-path"
                                 {:follow-redirects false
                                  :as :text})]
          (is (= (:status response) 200))
          (is (= (:body response) "Non-proxied path")))))

    (testing "gzipped responses not truncated"
      (with-target-and-proxy-servers
        {:target        {:host "0.0.0.0"
                         :port 9000}
         :proxy         {:host "0.0.0.0"
                         :port 10000}
         :proxy-handler proxy-wrapped-app
         :ring-handler  proxy-gzip-response
         :endpoint      "/hello-proxy"
         :target-endpoint "/hello"}
        (let [response (http-get "http://localhost:9000/hello")]
          (is (= gzip-body (:body response)))
          (is (= "gzip" (:orig-content-encoding response))))
        (let [response (http-get "http://localhost:10000/hello-proxy/")]
          (is (= gzip-body (:body response)))
          (is (= "gzip" (:orig-content-encoding response))))))

    (testing "proxy works with regex"
      (with-target-and-proxy-servers
        {:target        {:host "0.0.0.0"
                         :port 9000}
         :proxy         {:host "0.0.0.0"
                         :port 10000}
         :proxy-handler proxy-wrapped-app-regex
         :ring-handler  proxy-regex-response
         :endpoint      "/"
         :target-endpoint "/hello"}
        (let [response (http-get "http://localhost:10000/production/certificate/foo")]
          (is (= (:status response) 200))
          (is (= (:body response) "Proxied to /hello/production/certificate/foo")))
        (let [response (http-get "http://localhost:10000/hello/world")]
          (is (= (:status response) 200))
          (is (= (:body response) "Hello, World! (fallthrough)")))
        (let [response (http-get "http://localhost:10000/goodbye/world")]
          (is (= (:status response) 200))
          (is (= (:body response) "Goodbye, World! (fallthrough)")))
        (let [response (http-get "http://localhost:10000/production/cert/foo")]
          (is (= (:status response) 404))
          (is (= (:body response) "Not Found (fallthrough)")))))

    (testing "proxy regex matches beginning of string"
      (with-target-and-proxy-servers
        {:target {:host "0.0.0.0"
                  :port 9000}
         :proxy   {:host "0.0.0.0"
                   :port 10000}
         :proxy-handler proxy-wrapped-app-regex-alt
         :ring-handler proxy-regex-response
         :endpoint "/"
         :target-endpoint "/hello"}
        (let [response (http-get "http://localhost:10000/hello-proxy")]
          (is (= (:status response) 200))
          (is (= (:body response) "Proxied to /hello/hello-proxy")))
        (let [response (http-get "http://localhost:10000/production/hello-proxy")]
          (is (= (:status response) 404))
          (is (= (:body response) "Not Found (fallthrough)")))))

    (testing "proxy regex does not need to match entire request uri"
      (with-target-and-proxy-servers
        {:target {:host "0.0.0.0"
                  :port 9000}
         :proxy  {:host "0.0.0.0"
                  :port 10000}
         :proxy-handler proxy-wrapped-app-regex-alt
         :ring-handler  proxy-regex-response
         :endpoint "/"
         :target-endpoint "/hello"}
        (let [response (http-get "http://localhost:10000/hello-proxy/world")]
          (is (= (:status response) 200))
          (is (= (:body response) "Proxied to /hello/hello-proxy/world")))))

    (testing "proxy works with regex and no prepended path"
      (with-target-and-proxy-servers
        {:target        {:host "0.0.0.0"
                         :port 9000}
         :proxy         {:host "0.0.0.0"
                         :port 10000}
         :proxy-handler proxy-wrapped-app-regex-no-prepend
         :ring-handler  proxy-regex-response
         :endpoint      "/"
         :target-endpoint "/"}
        (let [response (http-get "http://localhost:10000/production/certificate/foo")]
          (is (= (:status response) 200))
          (is (= (:body response) "Proxied to /production/certificate/foo")))))

    (testing "no repeat slashes exist in rewritten uri"
      (with-target-and-proxy-servers
        {:target        {:host "0.0.0.0"
                         :port 9000}
         :proxy         {:host "0.0.0.0"
                         :port 10000}
         :proxy-handler proxy-wrapped-app-regex-trailing-slash
         :ring-handler  proxy-regex-response
         :endpoint      "/"
         :target-endpoint "/"}
        (let [response (http-get "http://localhost:10000/production/certificate/foo")]
          (is (= (:status response) 200))
          (is (= (:body response) "Proxied to /production/certificate/foo")))))))

(deftest test-wrap-add-cache-headers
  (let [put-request     {:request-method :put}
        get-request     {:request-method :get}
        post-request    {:request-method :post}
        delete-request  {:request-method :delete}
        no-cache-header "private, max-age=0, no-cache"]
    (testing "wrap-add-cache-headers ignores nil response"
      (let [handler (constantly nil)
            wrapped-handler (core/wrap-add-cache-headers handler)]
        (is (nil? (wrapped-handler put-request)))
        (is (nil? (wrapped-handler get-request)))
        (is (nil? (wrapped-handler post-request)))
        (is (nil? (wrapped-handler delete-request)))))
    (testing "wrap-add-cache-headers observes handled response"
      (let [handler              (constantly {})
            wrapped-handler      (core/wrap-add-cache-headers handler)
            handled-response     {:headers {"cache-control" no-cache-header}}
            not-handled-response {}]
        (is (= handled-response (wrapped-handler get-request)))
        (is (= handled-response (wrapped-handler put-request)))
        (is (= not-handled-response (wrapped-handler post-request)))
        (is (= not-handled-response (wrapped-handler delete-request)))))
    (testing "wrap-add-cache-headers doesn't stomp on existing headers"
      (let [fake-response        {:headers {:something "Hi mom"}}
            handler              (constantly fake-response)
            wrapped-handler      (core/wrap-add-cache-headers handler)
            handled-response     {:headers {:something      "Hi mom"
                                            "cache-control" no-cache-header}}
            not-handled-response fake-response]
        (is (= handled-response (wrapped-handler get-request)))
        (is (= handled-response (wrapped-handler put-request)))
        (is (= not-handled-response (wrapped-handler post-request)))
        (is (= not-handled-response (wrapped-handler delete-request)))))))

(deftest test-wrap-with-cn
  (testing "When extracting a CN from a cert"
    (testing "and there is no cert"
      (let [mw-fn (core/wrap-with-certificate-cn identity)
            post-req (mw-fn {})]
        (testing "ssl-client-cn is set to nil"
          (is (= post-req {:ssl-client-cn nil})))))

    (testing "and there is a cert"
      (let [mw-fn (core/wrap-with-certificate-cn identity)
            post-req (mw-fn {:ssl-client-cert (pem->cert "dev-resources/ssl/cert.pem")})]
        (testing "ssl-client-cn is set properly"
          (is (= (:ssl-client-cn post-req) "localhost")))))))

(deftest test-wrap-add-x-frame-options-deny
  (let [get-request    {:request-method :get}
        put-request    {:request-method :put}
        post-request   {:request-method :post}
        delete-request {:request-method :delete}
        x-frame-header "DENY"]
    (testing "wrap-add-x-frame-options-deny ignores nil response"
      (let [handler         (constantly nil)
            wrapped-handler (core/wrap-add-x-frame-options-deny handler)]
        (is (nil? (wrapped-handler get-request)))
        (is (nil? (wrapped-handler put-request)))
        (is (nil? (wrapped-handler post-request)))
        (is (nil? (wrapped-handler delete-request)))))
    (testing "wrap-add-x-frame-options-deny observes handled response"
      (let [handler              (constantly {})
            wrapped-handler      (core/wrap-add-x-frame-options-deny handler)
            handled-response     {:headers {"X-Frame-Options" x-frame-header}}
            not-handled-response {}]
        (is (= handled-response (wrapped-handler get-request)))
        (is (= handled-response (wrapped-handler put-request)))
        (is (= handled-response (wrapped-handler post-request)))
        (is (= handled-response (wrapped-handler delete-request)))))
    (testing "wrap-add-x-frame-options-deny doesn't stomp on existing headers"
      (let [fake-response        {:headers {:something "Hi mom"}}
            handler              (constantly fake-response)
            wrapped-handler      (core/wrap-add-x-frame-options-deny handler)
            handled-response     {:headers {:something      "Hi mom"
                                            "X-Frame-Options" x-frame-header}}]
        (is (= handled-response (wrapped-handler get-request)))
        (is (= handled-response (wrapped-handler put-request)))
        (is (= handled-response (wrapped-handler post-request)))
        (is (= handled-response (wrapped-handler delete-request)))))))

(deftest wrap-response-logging-test
  (testing "wrap-response-logging"
    (logutils/with-test-logging
      (let [stack (core/wrap-response-logging identity)
            response (stack (basic-request))]
        (is (logged? #"Computed response.*" :trace))))))

(deftest wrap-request-logging-test
  (testing "wrap-request-logging"
    (logutils/with-test-logging
      (let [subject "foo-agent"
            method :get
            uri "https://example.com"
            stack (core/wrap-request-logging identity)
            request (basic-request subject method uri)
            response (stack request)]
        (is (logged? (format "Processing %s %s" method uri) :debug))
        (is (logged? #"Full request" :trace))))))

(deftest wrap-data-errors-test
  (testing "wrap-data-errors"
    (testing "default behavior"
      (logutils/with-test-logging
        (let [stack (core/wrap-data-errors (throwing-handler :user-data-invalid "Error Message"))
              response (stack (basic-request))
              json-body (json/parse-string (response :body))]
          (is (= 400 (response :status)))
          (is (= "Error Message" (get-in json-body ["error" "message"])))
          (is (logged? #"Error Message" :error))
          (is (logged? #"Submitted data is invalid" :error)))))
    (doseq [error [:request-data-invalid :user-data-invalid :service-status-version-not-found]]
      (testing (str "handles errors of " error)
        (let [stack (core/wrap-data-errors (throwing-handler error "Error Message"))
              response (stack (basic-request))
              json-body (json/parse-string (response :body))]
          (is (= 400 (response :status)))
          (is (= (name error) (get-in json-body ["error" "type"]))))))
    (testing "can be plain text"
      (let [stack (core/wrap-data-errors
                    (throwing-handler :user-data-invalid "Error Message") :plain)
            response (stack (basic-request))]
        (is (re-matches #"text/plain.*" (get-in response [:headers "Content-Type"])))))))

(deftest wrap-schema-errors-test
  (testing "wrap-schema-errors"
    (testing "default behavior"
      (logutils/with-test-logging
        (let [stack (core/wrap-schema-errors cause-schema-error)
              response (stack (basic-request))
              json-body (json/parse-string (response :body))]
          (is (= 500 (response :status)))
          (is (logged? #".*Something unexpected.*" :error))
          (is (re-matches #"Something unexpected.*"(get-in json-body ["error" "message"])))
          (is (= "application-error" (get-in json-body ["error" "type"]))))))
    (testing "can be plain text"
      (let [stack (core/wrap-schema-errors cause-schema-error :plain)
            response (stack (basic-request))]
        (is (re-matches #"text/plain.*" (get-in response [:headers "Content-Type"])))))))

(deftest wrap-uncaught-errors-test
  (testing "wrap-uncaught-errors"
    (testing "default behavior"
      (logutils/with-test-logging
        (let [stack (core/wrap-uncaught-errors (fn [_] (throw (IllegalStateException. "Woah..."))))
              response (stack (basic-request))
              json-body (json/parse-string (response :body))]
          (is (= 500 (response :status)))
          (is (logged? #".*Internal Server Error.*" :warn))
          (is (re-matches #"Internal Server Error.*" (get-in json-body ["error" "message"]))))))
    (testing "can be plain text"
      (let [stack (core/wrap-uncaught-errors (fn [_] (throw (IllegalStateException. "Woah..."))) :plain)
            response (stack (basic-request))]
        (is (re-matches #"text/plain.*" (get-in response [:headers "Content-Type"])))))))
