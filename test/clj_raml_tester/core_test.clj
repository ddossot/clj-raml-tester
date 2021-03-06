(ns clj-raml-tester.core-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clj-raml-tester.core :refer :all]
            [clj-http.client :as http]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.util.response :refer [response created]]
            [ring.middleware.json :refer [wrap-json-body
                                          wrap-json-response]]
            [ring.middleware.defaults :refer [wrap-defaults
                                              site-defaults]]
            [ring.middleware.conditional :refer [if-url-doesnt-start-with]]
            [compojure.core :refer :all]
            [compojure.route :as route]
            [freeport.core :refer [get-free-port!]]))

(def debug
  (read-string
    (System/getProperty
      "clj-raml-tester.debug"
      "false")))

(def test-api-port
  (Integer/valueOf
    (System/getProperty
      "clj-raml-tester.test-api-port"
      (str (get-free-port!)))))

(defn test-api-url
  ([]
    (test-api-url nil))
  ([path]
    (str
      "http://localhost:"
      test-api-port
      path)))

(def raml-tester-proxy-port
  (Integer/valueOf
    (System/getProperty
      "clj-raml-tester.proxy-port"
      (str (get-free-port!)))))

(defn test-proxy-url
  ([]
    (test-proxy-url nil))
  ([path]
    (str
      "http://localhost:"
      raml-tester-proxy-port
      path)))

(defroutes api-routes
  (GET "/fruit" []
       (response {:id 1
                  :name "banana"}))
  (POST "/fruits" [:as request]
        (created "fake-url" {:id 2
                             :name "orange"}))
  (route/not-found "Not Found"))

(def ring-app
  (-> api-routes
    (if-url-doesnt-start-with
      "/fruit"
      #(wrap-defaults
         %
         (assoc-in
           site-defaults
           [:static :resources]
           "specs")))
    (wrap-json-body
      {:keywords? true})
    wrap-json-response))

(defn with-test-api [f]
  (let [jetty (run-jetty
                ring-app
                {:port test-api-port
                 :join? false})]
    (f)
    (.stop jetty)))

(use-fixtures :once with-test-api)

(defn start-proxy
  [raml-url]
  (start-raml-tester-proxy
    raml-tester-proxy-port
    (test-api-url)
    raml-url))

(defn start-proxy-with-raml-file
  []
  (start-proxy
    (io/resource "specs/test-api.raml")))

(defn start-proxy-with-raml-http
  []
  (start-proxy
    (test-api-url "/test-api.raml")))

(defn log-debug
  [msg]
  (when debug
    (println
      "\n" (testing-contexts-str) "----\n"
      msg "\n")))

(defn proxy-results
  [rtp]
  (let [results (raml-tester-results rtp)]
    (log-debug
      (results->str results))
    results))

(defn no-api-coverage-assertions
  [rtp]
  (let [results (proxy-results rtp)]
    (are [coll expected] (= (count coll) expected)
         (:unused-resources results)        2
         (:unused-actions results)          2
         (:unused-form-parameter results)   0
         (:unused-query-parameters results) 0
         (:unused-request-headers results)  0
         (:unused-response-headers results) 0
         (:unused-response-codes results)   2
         (:request-violations results)      0
         (:response-violations results)     0
         (:validation-violations results)   0)))

(deftest no-api-coverage
  (testing "no API coverage"
    (testing "with RAML file"
      (with-open [rtp (start-proxy-with-raml-file)]
        (no-api-coverage-assertions rtp)))
    (testing "with RAML HTTP"
      (with-open [rtp (start-proxy-with-raml-http)]
        (no-api-coverage-assertions rtp)))))

(defn partial-api-tests
  []
  (let [resp (http/get (test-proxy-url "/fruit")
                       {:as :json})]
    (log-debug resp)
    (is (= (:status resp) 200))))

(defn partial-api-coverage-assertions
  [rtp]
  (let [results (proxy-results rtp)
        report (results->test-report results)]
    (are [coll expected] (= (count coll) expected)
         (:unused-resources results)        1
         (:unused-actions results)          1
         (:unused-form-parameter results)   0
         (:unused-query-parameters results) 0
         (:unused-request-headers results)  0
         (:unused-response-headers results) 0
         (:unused-response-codes results)   1
         (:request-violations results)      0
         (:response-violations results)     0
         (:validation-violations results)   0)
    (are [key expected] (= (get report key) expected)
         :type     :fail
         :expected 0
         :actual   3
         :message  (str
                     "3 issues detected (ignore-request-violations? true):\n\n"
                     " unused-resources:\n"
                     "  /fruits\n"
                     " unused-actions:\n"
                     "  POST /fruits\n"
                     " unused-response-codes:\n"
                     "  201 in POST /fruits\n"))))

(deftest partial-api-coverage
  (testing "partial API coverage"
    (testing "with RAML file"
      (with-open [rtp (start-proxy-with-raml-file)]
        (partial-api-tests)
        (wait-n-requests rtp 1)
        (partial-api-coverage-assertions rtp)))
    (testing "with RAML HTTP"
      (with-open [rtp (start-proxy-with-raml-http)]
        (partial-api-tests)
        (wait-n-requests rtp 1)
        (partial-api-coverage-assertions rtp)))))

(defn full-api-tests
  [post-body]
  (partial-api-tests)
  (let [resp (http/post (test-proxy-url "/fruits")
                        {:as :json
                         :content-type :json
                         :body post-body})]
    (log-debug resp)
    (is (= (:status resp) 201))))

(defn full-api-coverage-assertions
  [rtp expected-request-violations]
  (let [results (proxy-results rtp)]
    (are [coll expected] (= (count coll) expected)
         (:unused-resources results)        0
         (:unused-actions results)          0
         (:unused-form-parameter results)   0
         (:unused-query-parameters results) 0
         (:unused-request-headers results)  0
         (:unused-response-headers results) 0
         (:unused-response-codes results)   0
         (:request-violations results)      expected-request-violations
         (:response-violations results)     0
         (:validation-violations results)   0)

    (do-report
      (results->test-report results))))

(deftest full-api-coverage-valid-requests
  (testing "full API coverage, valid requests"
    (testing "with RAML file"
      (with-open [rtp (start-proxy-with-raml-file)]
        (full-api-tests "{\"name\":\"orange\"}")
        (wait-n-requests rtp 2)
        (full-api-coverage-assertions rtp 0)))
    (testing "with RAML HTTP"
      (with-open [rtp (start-proxy-with-raml-http)]
        (full-api-tests "{\"name\":\"orange\"}")
        (wait-n-requests rtp 2)
        (full-api-coverage-assertions rtp 0)))))

(deftest full-api-coverage-invalid-request
  (testing "full API coverage, invalid request"
    (testing "with RAML file"
      (with-open [rtp (start-proxy-with-raml-file)]
        (full-api-tests "{\"kind\":\"fruit\"}")
        (wait-n-requests rtp 2)
        (full-api-coverage-assertions rtp 1)))
    (testing "with RAML HTTP"
      (with-open [rtp (start-proxy-with-raml-http)]
        (full-api-tests "{\"kind\":\"fruit\"}")
        (wait-n-requests rtp 2)
        (full-api-coverage-assertions rtp 1)))))
