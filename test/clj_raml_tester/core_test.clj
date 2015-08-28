(ns clj-raml-tester.core-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clj-raml-tester.core :as crt]
            [clj-http.client :as http]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.util.response :refer [response created]]
            [ring.middleware.json :refer [wrap-json-response]]
            [compojure.core :refer :all]
            [compojure.route :as route]
            [freeport.core :refer [get-free-port!]]))

(def raml-tester-proxy-port
  (Integer/valueOf
    (System/getProperty
      "clj-raml-tester.proxy-port"
      (str (get-free-port!)))))

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
      raml-tester-proxy-port
      path)))

(defroutes api-routes
  (GET "/fruit" []
       (response {:id 1
                  :name "banana"}))
  (POST "/fruits" []
        (created "fake-url" {:id 2
                             :name "orange"}))
  (route/not-found "not found"))

(def api-app
  (wrap-json-response api-routes))

(defn with-test-api [f]
  (let [jetty (run-jetty
                api-app
                {:port test-api-port
                 :join? false})]
    (f)
    (.stop jetty)))

(use-fixtures :once with-test-api)

(defn resource->absolute-url
  [rsc]
  (str "file://"
       (io/file
         (io/resource rsc))))

(defn start-proxy
  []
  (crt/start-proxy
    raml-tester-proxy-port
    (test-api-url)
    (resource->absolute-url "test-api.raml")))

(deftest no-api-hit
  (with-open [rtp (start-proxy)]
    (let [rsus (crt/proxy->reports-and-usages rtp)]
      (are [coll expected] (= (count coll) expected)
           (:unused-resources rsus)      2
           (:unused rsus)                2
           (:request-violations rsus)    0
           (:response-violations rsus)   0
           (:validation-violations rsus) 0))))
