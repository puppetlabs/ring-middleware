(ns puppetlabs.ring-middleware.core-test
  (:require [clojure.test :refer :all]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :refer :all]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer [with-app-with-config]]
            [puppetlabs.trapperkeeper.app :refer [get-service]]
            [puppetlabs.ring-middleware.core :refer [wrap-proxy wrap-add-cache-headers]]
            [puppetlabs.ring-middleware.testutils.common :refer :all]
            [ring.util.response :as rr]
            [compojure.core :refer :all]
            [compojure.handler :as handler]
            [compojure.route :as route]))

(defn post-target-handler
  [req]
  (if (= (:request-method req) :post)
    {:status 200 :body (slurp (:body req))}
    {:status 404 :body "D'oh"}))

(defn proxy-target-handler
  [req]
  (condp = (:uri req)
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
  {:status 404 :body "D'oh"})

(defn proxy-regex-response
  [_]
  {:status 200 :body "Proxied with regex!"})

(defroutes fallthrough-routes
  (GET "/hello/world" [] "Hello, World! (fallthrough)")
  (GET "/goodbye/world" [] "Goodbye, World! (fallthrough)")
  (route/not-found "Not Found (fallthrough)"))

(def proxy-regex-fallthrough
  (handler/site fallthrough-routes))

(def proxy-wrapped-app
  (-> proxy-error-handler
      (wrap-proxy "/hello-proxy" "http://localhost:9000/hello")))

(def proxy-wrapped-app-ssl
  (-> proxy-error-handler
      (wrap-proxy "/hello-proxy" "https://localhost:9001/hello"
                  {:ssl-cert "./dev-resources/config/jetty/ssl/certs/localhost.pem"
                   :ssl-key  "./dev-resources/config/jetty/ssl/private_keys/localhost.pem"
                   :ssl-ca-cert "./dev-resources/config/jetty/ssl/certs/ca.pem"})))

(def proxy-wrapped-app-no-redirect
  (-> proxy-error-handler
      (wrap-proxy "/hello-proxy" "http://localhost:9000/hello"
                  {:follow-redirects false})))

(def proxy-wrapped-app-no-post-redirect
  (-> proxy-error-handler
      (wrap-proxy "/hello-proxy" "http://localhost:9000/hello"
                  {:force-redirects false})))

(def proxy-wrapped-app-regex
  (-> proxy-regex-fallthrough
      (wrap-proxy #"^/([^/]+/certificate.*)$" "http://localhost:9000/hello")))

(def proxy-wrapped-app-regex-alt
  (-> proxy-regex-fallthrough
      (wrap-proxy #"/hello-proxy" "http://localhost:9000/hello")))

(defmacro with-target-and-proxy-servers
  [{:keys [target proxy proxy-handler ring-handler endpoint]} & body]
  `(with-app-with-config proxy-target-app#
     [jetty9-service]
     {:webserver ~target}
     (let [target-webserver# (get-service proxy-target-app# :WebserverService)]
       (add-ring-handler
         target-webserver#
         ~ring-handler
         "/hello")
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
         :endpoint      "/hello-proxy"}
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
         :endpoint      "/hello-proxy"}
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
         :endpoint      "/hello-proxy"}
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
         :endpoint      "/hello-proxy"}
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
        :endpoint      "/hello-proxy"}
        (let [response (http-get "http://localhost:9000/hello")]
          (is (= (:status response) 200))
          (is (= (:body response) "Hello, World!")))
        (let [response (http-get "http://localhost:9000/hello/world")]
          (is (= (:status response) 200))
          (is (= (:body response) "Hello, World!")))
        (let [response (http-get "http://localhost:10000/hello-proxy/"
                                 {:follow-redirects false
                                  :as :text})]
          (is (= (:status response) 200))
          (is (= (:body response) "Hello, World!")))
        (let [response (http-post "http://localhost:10000/hello-proxy/"
                                 {:follow-redirects false
                                  :as :text})]
          (is (= (:status response) 200))
          (is (= (:body response) "Hello, World!")))
        (let [response (http-get "http://localhost:10000/hello-proxy/world")]
          (is (= (:status response) 200))
          (is (= (:body response) "Hello, World!")))))

    (testing "proxy redirect fails if :follow-redirects set to false"
      (with-target-and-proxy-servers
        {:target        {:host "0.0.0.0"
                         :port 9000}
         :proxy         {:host "0.0.0.0"
                         :port 10000}
         :proxy-handler proxy-wrapped-app-no-redirect
         :ring-handler  proxy-target-handler
         :endpoint      "/hello-proxy"}
        (let [response (http-get "http://localhost:9000/hello")]
          (is (= (:status response) 200))
          (is (= (:body response) "Hello, World!")))
        (let [response (http-get "http://localhost:10000/hello-proxy/"
                                 {:follow-redirects false})]
          (is (= (:status response 302))))))

    (testing "proxy redirect fails on POST if :force-redirects set to false"
      (with-target-and-proxy-servers
        {:target        {:host "0.0.0.0"
                         :port 9000}
         :proxy         {:host "0.0.0.0"
                         :port 10000}
         :proxy-handler proxy-wrapped-app-no-post-redirect
         :ring-handler  proxy-target-handler
         :endpoint      "/hello-proxy"}
        (let [response (http-get "http://localhost:10000/hello-proxy/"
                                 {:follow-redirects false
                                  :as :text})]
          (is (= (:status response) 200))
          (is (= (:body response) "Hello, World!")))
        (let [response (http-post "http://localhost:10000/hello-proxy/"
                                 {:follow-redirects false})]
          (is (= (:status response 302))))))

    (testing "proxy redirect to non-target host fails"
      (with-target-and-proxy-servers
        {:target        {:host "0.0.0.0"
                         :port 9000}
         :proxy         {:host "0.0.0.0"
                         :port 10000}
         :proxy-handler proxy-wrapped-app
         :ring-handler  proxy-target-handler
         :endpoint      "/hello-proxy"}
        (let [response (http-get "http://localhost:10000/hello-proxy/wrong-host")]
          (is (= (:status response 502))))))

    (testing "redirect test with fully qualified url, correct host, and proxied path"
      (with-target-and-proxy-servers
        {:target       {:host "0.0.0.0"
                        :port 9000}
         :proxy        {:host "0.0.0.0"
                        :port 10000}
         :proxy-handler proxy-wrapped-app
         :ring-handler  proxy-target-handler
         :endpoint      "/hello-proxy"}
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
         :proxy-handler proxy-wrapped-app
         :ring-handler  proxy-target-handler
         :endpoint      "/hello-proxy"}
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
         :endpoint      "/hello-proxy"}
        (let [response (http-get "http://localhost:9000/hello")]
          (is (= (:body response) gzip-body))
          (is (= (:orig-content-encoding response) "gzip")))
        (let [response (http-get "http://localhost:10000/hello-proxy")]
          (is (= (:body response) gzip-body))
          (is (= (:orig-content-encoding response) "gzip")))))

    (testing "proxy works with regex"
      (with-target-and-proxy-servers
        {:target        {:host "0.0.0.0"
                         :port 9000}
         :proxy         {:host "0.0.0.0"
                         :port 10000}
         :proxy-handler proxy-wrapped-app-regex
         :ring-handler  proxy-regex-response
         :endpoint      "/"}
        (let [response (http-get "http://localhost:10000/production/certificate/foo")]
          (is (= (:status response) 200))
          (is (= (:body response) "Proxied with regex!")))
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
         :endpoint "/"}
        (let [response (http-get "http://localhost:10000/hello-proxy")]
          (is (= (:status response) 200))
          (is (= (:body response) "Proxied with regex!")))
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
         :ring-handler  proxy-target-handler
         :endpoint "/"}
        (let [response (http-get "http://localhost:10000/hello-proxy/world")]
          (is (= (:status response) 200))
          (is (= (:body response) "Hello, World!")))))))

(deftest test-wrap-add-cache-headers
  (let [put-request     {:request-method :put}
        get-request     {:request-method :get}
        post-request    {:request-method :post}
        delete-request  {:request-method :delete}
        no-cache-header "private, max-age=0, no-cache"]
    (testing "wrap-add-cache-headers ignores nil response"
      (let [handler (constantly nil)
            wrapped-handler (wrap-add-cache-headers handler)]
        (is (nil? (wrapped-handler put-request)))
        (is (nil? (wrapped-handler get-request)))
        (is (nil? (wrapped-handler post-request)))
        (is (nil? (wrapped-handler delete-request)))))
    (testing "wrap-add-cache-headers observes handled response"
      (let [handler              (constantly {})
            wrapped-handler      (wrap-add-cache-headers handler)
            handled-response     {:headers {"cache-control" no-cache-header}}
            not-handled-response {}]
        (is (= handled-response (wrapped-handler get-request)))
        (is (= handled-response (wrapped-handler put-request)))
        (is (= not-handled-response (wrapped-handler post-request)))
        (is (= not-handled-response (wrapped-handler delete-request)))))
    (testing "wrap-add-cache-headers doesn't stomp on existing headers"
      (let [fake-response        {:headers {:something "Hi mom"}}
            handler              (constantly fake-response)
            wrapped-handler      (wrap-add-cache-headers handler)
            handled-response     {:headers {:something      "Hi mom"
                                            "cache-control" no-cache-header}}
            not-handled-response fake-response]
        (is (= handled-response (wrapped-handler get-request)))
        (is (= handled-response (wrapped-handler put-request)))
        (is (= not-handled-response (wrapped-handler post-request)))
        (is (= not-handled-response (wrapped-handler delete-request)))))))
