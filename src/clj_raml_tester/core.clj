(ns clj-raml-tester.core
  (:require [clojure.set :refer [union]]
            [clojure.string :as str])
  (:import   clj_raml_tester.Util
             guru.nidi.ramltester.MultiReportAggregator
             guru.nidi.ramltester.RamlDefinition
             guru.nidi.ramltester.core.Usage
            [guru.nidi.ramlproxy.core RamlProxyServer
                                      ServerOptions
                                      ValidatorConfigurator]
             guru.nidi.ramlproxy.jetty.JettyRamlProxyServer
             guru.nidi.ramlproxy.report.ReportSaver
             java.io.File
            [java.net URI URL]))

(defprotocol RamlTesterProxy
  "Defines the protocol for a RAML Tester Proxy"
  (raml-definition [this])
  (usage [this])
  (reports [this])
  (close [this]))

(defn- proxy->aggregator
  [^RamlProxyServer proxy]
  (.. proxy getSaver getAggregator))

(defrecord ^:private RamlTesterProxyRecord
  [^JettyRamlProxyServer proxy
   ^RamlDefinition raml-definition]
  RamlTesterProxy
  (raml-definition [this]
    raml-definition)
  (usage [this]
    (.getUsage
      (proxy->aggregator proxy)
      raml-definition))
  (reports [this]
    (.getReports
      (proxy->aggregator proxy)
      raml-definition))
  (close [this]
    (.close proxy)
    (.waitForServer proxy)))

(defn- trim-left-slashes
  [s]
  (second
    (re-matches #"^/*([^/]+.*)$" s)))

(defmulti ^:private ^URI urlish->str
  (fn [v] [(class v)]))

(defmethod ^:private urlish->str [String] [v]
  v)

(defmethod ^:private urlish->str [File] [^File v]
  (urlish->str (.toURI v)))

(defmethod ^:private urlish->str [URL] [^URL v]
  (urlish->str (.toURI v)))

(defmethod ^:private urlish->str [URI] [^URI v]
  (case (.getScheme v)
    ;; raml-tester likes its file URLs triple-slashed
    "file" (str "file:///"
                (trim-left-slashes
                  (.getSchemeSpecificPart v)))
    (str v)))

(defn- urlish?
  [v]
  (or
    (string? v)
    (instance? URI v)
    (instance? URL v)
    (instance? File v)))

(defn- validate-raml
  [server-options raml-definition]
  (-> (.validateRaml
        server-options
        raml-definition)
    .getValidationViolations
    .asList))

(defn- throw-raml-violations
  [raml-violations]
  (throw
    (IllegalArgumentException.
      (str "The RAML file has validation errors: \n"
           (str/join "\n" raml-violations)))))

(defn- load-and-validate-raml
  [server-options]
  (let [raml-definition (Util/fetchRamlDefinition
                          server-options)
        raml-violations (validate-raml
                          server-options
                          raml-definition)]
    (if (empty? raml-violations)
      raml-definition
      (throw-raml-violations
        raml-violations))))

(defn start-proxy
  "TODO document"
  [port target-url raml-url
   & {:keys [base-uri ignore-x-headers]
      :or {base-uri target-url
           ignore-x-headers true}}]
  {:pre [(pos? port)
         (urlish? target-url)
         (urlish? raml-url)
         (urlish? base-uri)
         (instance? Boolean ignore-x-headers)]
   :post [(satisfies? RamlTesterProxy %)]}

  (let [server-options (ServerOptions.
                         port
                         (urlish->str target-url)
                         nil              ; mock-dir
                         (urlish->str raml-url)
                         (urlish->str base-uri)
                         nil              ; save-dir
                         nil              ; file-format
                         ignore-x-headers
                         false            ; async-mode
                         0                ; min-delay
                         0                ; max-delay
                         ValidatorConfigurator/NONE)
        raml-definition (load-and-validate-raml
                          server-options)]
    (RamlTesterProxyRecord.
      (JettyRamlProxyServer.
        server-options
        (ReportSaver. (MultiReportAggregator.))
        raml-definition)
      raml-definition)))

(defmacro ^:private update-violations
  [violations type getter raml-report]
  `(update-in ~violations [~type]
     union (set (~getter ~raml-report))))

(defn- raml-reports->violations
  [raml-reports]
  (reduce
    (fn -violation-reducer
      [violations raml-report]
      (-> violations
        (update-violations
          :request-violations
          .getRequestViolations
          raml-report)
        (update-violations
          :response-violations
          .getResponseViolations
          raml-report)
        (update-violations
          :validation-violations
          .getValidationViolations
          raml-report)))
    {:request-violations #{}
     :response-violations #{}
     :validation-violations #{}}
    raml-reports))

(defn proxy->reports-and-usages
  "TODO doc"
  [proxy-rec]
  {:pre [(satisfies? RamlTesterProxy proxy-rec)]}
  (let [usage (usage proxy-rec)]
    (merge
      (raml-reports->violations (reports proxy-rec))
      {:unused-resources (set (.getUnusedResources usage))
       :unused-actions (set (.getUnusedActions usage))
       :unused-form-parameter (set (.getUnusedFormParameters usage))
       :unused-query-parameters (set (.getUnusedQueryParameters usage))
       :unused-request-headers (set (.getUnusedRequestHeaders usage))
       :unused-response-headers (set (.getUnusedResponseHeaders usage))
       :unused-response-codes (set (.getUnusedResponseCodes usage))})))

(defn proxy->test-report
  "TODO doc"
  [proxy-rec
   & {:keys [ignore-request-violations]
      :or {ignore-request-violations true}}]
  {:pre [(satisfies? RamlTesterProxy proxy-rec)
         (instance? Boolean ignore-request-violations)]
   :post [(map? %)]}
  (let [rsus (proxy->reports-and-usages proxy-rec)]
    ;; TODO implement me!
    nil))
