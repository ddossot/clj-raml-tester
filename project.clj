(defproject net.dossot/clj-raml-tester "0.8.4-SNAPSHOT"
  :description "Clojure RAML Tester Tooling"
  :url "http://github.com/ddossot/clj-raml-tester"
  :license {:name "The MIT License (MIT)"
            :url "http://opensource.org/licenses/MIT"
            :comments "Copyright (c) 2015 David Dossot"}

  :java-source-paths ["java-src"]

  ; set to true to have verbose debug of integration tests
  :jvm-opts ["-Dclj-raml-tester.debug=false"]

  :profiles {:dev {:plugins [[lein-kibit "0.1.2"]
                             [jonase/eastwood "0.2.1"]
                             [codox "0.8.13"]]
                   :dependencies [[clj-http "2.0.0"]
                                  [ring/ring-jetty-adapter "1.4.0"]
                                  [ring/ring-json "0.4.0"]
                                  [ring/ring-defaults "0.1.5"]
                                  [ring.middleware.conditional "0.2.0"]
                                  [compojure "1.4.0"]
                                  [freeport "1.0.0"]]
                   :resource-paths ["test-resources"]}}

  :dependencies [[org.clojure/clojure "1.6.0"]
                 [guru.nidi.raml/raml-tester-client "0.8.4"
                  :exclusions [com.fasterxml.jackson.core/jackson-databind]]
                 [com.fasterxml.jackson.core/jackson-databind "2.4.4"]])
