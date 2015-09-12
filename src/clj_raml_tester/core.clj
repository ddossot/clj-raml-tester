(ns clj-raml-tester.core
  "A set of utilities for using RAML Tester in API integration tests."
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

(defn start-raml-tester-proxy
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
        (ReportSaver.
          (MultiReportAggregator.))
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

(defonce ^:private default-requests-wait-time-millis 3000)
(defonce ^:private request-wait-sleep-time-millis 10)

(defn wait-n-requests
  "Wait for n test requests to be captured by the proxy."
  ([proxy-rec n]
    (wait-n-requests
      proxy-rec
      n
      default-requests-wait-time-millis))
  ([proxy-rec n max-wait-millis]
    {:pre [(satisfies? RamlTesterProxy proxy-rec)
           (pos? n)
           (pos? max-wait-millis)]}
    (loop [attempts-left (/ max-wait-millis
                            request-wait-sleep-time-millis)]
      (when
        (< (count (reports proxy-rec)) n)
        (Thread/sleep
          request-wait-sleep-time-millis)
        (recur (dec attempts-left))))))

(defrecord RamlTesterResults
  [request-violations
   response-violations
   validation-violations
   unused-resources
   unused-actions
   unused-form-parameter
   unused-query-parameters
   unused-request-headers
   unused-response-headers
   unused-response-codes])

(def empty-raml-tester-results
  (->RamlTesterResults
    #{} #{} #{} #{} #{}
    #{} #{} #{} #{} #{}))

(defn raml-tester-results
  "Gets the currently captured tester results
   and returns them as RamlTesterResults record.
   All fields of the record are sets."
  [proxy-rec]
  {:pre [(satisfies? RamlTesterProxy proxy-rec)]
   :post [(instance? RamlTesterResults %)]}
  (let [usage (usage proxy-rec)]
    (map->RamlTesterResults
      (merge
        (raml-reports->violations (reports proxy-rec))
        {:unused-resources (set (.getUnusedResources usage))
         :unused-actions (set (.getUnusedActions usage))
         :unused-form-parameter (set (.getUnusedFormParameters usage))
         :unused-query-parameters (set (.getUnusedQueryParameters usage))
         :unused-request-headers (set (.getUnusedRequestHeaders usage))
         :unused-response-headers (set (.getUnusedResponseHeaders usage))
         :unused-response-codes (set (.getUnusedResponseCodes usage))}))))

(defn results->str
  "Formats results as a single string."
  [results]
  {:pre [(instance? RamlTesterResults results)]
   :post [(string? %)]}
  (str/join
    "\n"
    (reduce
      (fn -results-formatter
        [issues [k v]]
        (if (seq v)
          (conj issues
                (str " " (name k) ":\n  "
                     (str/join "\n  " v)))
          issues))
      []
      results)))

(defn count-issues
  "Counts the total number of issues in the provided results,
   potentially ignoring request violations."
  [results ignore-request-violations?]
  {:pre [(instance? RamlTesterResults results)
         (instance? Boolean ignore-request-violations?)]
   :post [(or (zero? %) (pos? %))]}
  (let [results* (if ignore-request-violations?
                   (assoc results
                          :request-violations nil)
                   results)]
    (reduce
      (fn -issue-counter
        [total [k v]]
        (+ total (count v)))
     0
     results*)))

(defn- base-report-message
  [issue-count ignore-request-violations?]
  (str
    issue-count
    " issue"
    (when (> issue-count 1) "s")
    " detected (ignore-request-violations? "
    ignore-request-violations? ")"
    (if (pos? issue-count) ":" ".")))

(defn results->test-report
  "Transforms results into a map that can be used by clojure.test/do-report.
   Supported options:

   :ignore-request-violations? - should detected request violations by ignored?
                                 (defaults to true because API integration tests
                                 typically simulate bad client requests)"
  [results
   & {:keys [ignore-request-violations?]
      :or {ignore-request-violations? true}}]
  {:pre [(instance? RamlTesterResults results)
         (instance? Boolean ignore-request-violations?)]
   :post [(map? %)]}
  (let [issue-count (count-issues
                      results
                      ignore-request-violations?)
        brm (base-report-message
              issue-count
              ignore-request-violations?)
        [report-type report-message] (if (pos? issue-count)
                                       [:fail (str brm
                                                   "\n\n"
                                                   (results->str results)
                                                   "\n")]
                                       [:pass brm])]
    {:type report-type
     :message report-message
     :expected 0
     :actual issue-count}))
