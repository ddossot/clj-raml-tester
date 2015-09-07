(ns clj-raml-tester.core-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clj-raml-tester.core :refer :all]
            [clj-http.client :as http]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.util.response :refer [response created]]
            [ring.middleware.json :refer [wrap-json-response]]
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
  (POST "/fruits" []
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
  (let [results (proxy-results rtp)]
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
         (:validation-violations results)   0)))

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

;; TODO add full API coverage without request violation
;; TODO add full API coverage with request violation
;; TODO add test-report tests
