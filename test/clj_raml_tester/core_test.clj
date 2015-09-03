(ns clj-raml-tester.core-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clj-raml-tester.core :as crt]
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
  (crt/start-proxy
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

(defn no-api-hit-assertions
  [rtp]
  (let [rsus (crt/proxy->reports-and-usages rtp)]
    (are [coll expected] (= (count coll) expected)
         (:unused-resources rsus)      2
         (:unused rsus)                2
         (:request-violations rsus)    0
         (:response-violations rsus)   0
         (:validation-violations rsus) 0)))

(deftest no-api-hit
  (testing "with RAML file"
    (with-open [rtp (start-proxy-with-raml-file)]
      (no-api-hit-assertions rtp)))
  (testing "with RAML HTTP"
    (with-open [rtp (start-proxy-with-raml-http)]
      (no-api-hit-assertions rtp))))
