(ns aeonik.xtdb
  "XTDB integration for storing telemetry metrics.
   
   Provides functions for:
   - Starting/stopping XTDB nodes
   - Converting telemetry packets to XTDB documents
   - Submitting metrics to XTDB"
  (:require [clojure.string :as str]
            [xtdb.api :as xt]
            [manifold.stream :as s]
            [next.jdbc :as jdbc])
  (:import (java.io Closeable)))

(defn metric-doc-id
  "Construct a stable XTDB document id for a telemetry metric.

  Parameters:
  - prelude: map containing parsed packet prelude values such as :msg and :tm.
  - metric: individual metric map produced by `parse-metric-line`.
  - sender: string/inet address descriptor from the packet metadata.

  Returns a namespaced string identifier that combines message metadata and the metric name."
  [prelude metric sender]
  (let [msg    (or (:msg prelude) "msg")
        tm     (or (:tm prelude) 0)
        tick   (or (:tick metric) 0)
        name   (or (:name metric) "metric")
        source (some-> sender str (str/replace #"\s" ""))]
    (str msg ":" tm ":" tick ":" name (when source (str ":" source)))))

(defn build-metric-docs
  "Transform a parsed telemetry packet into XTDB-ready documents.

  Parameters:
  - packet: map produced by `parse-packet`, containing :prelude, :metrics, :sender, and :received-at.
  - table-name: keyword destination table for XTDB (defaults to :telemetry/metrics).

  Returns a vector of maps suitable for the `:put-docs` transaction op."
  ([packet]
   (build-metric-docs packet :telemetry/metrics))
  ([{:keys [prelude metrics sender received-at]}
    table-name]
   (let [ingested-at (or received-at (java.util.Date.))
         base-msg    (:msg prelude)
         base-tm     (:tm prelude)]
     (mapv (fn [metric]
             (cond-> {:xt/id                       (metric-doc-id prelude metric sender)
                      :telemetry/name              (:name metric)
                      :telemetry/value             (:value metric)
                      :telemetry/type              (:type metric)
                      :telemetry/tick              (:tick metric)
                      :telemetry/device-time-us    (:device-time-us metric)
                      :telemetry/device-time-str   (:device-time-str metric)
                      :telemetry/sender            (some-> sender str)
                      :telemetry/message           base-msg
                      :telemetry/message-tm        base-tm
                      :telemetry/ingested-at-ms    (inst-ms ingested-at)
                      :telemetry/table             table-name}
               (:fields metric) (assoc :telemetry/fields (:fields metric))
               (:error metric)  (assoc :telemetry/error (:error metric))))
           metrics))))

(defn metrics->put-tx
  "Wrap packet metrics in an XTDB `:put-docs` transaction operation.

  Parameters:
  - packet: parsed packet map from `parse-packet`.
  - table-name: keyword destination table for XTDB (defaults to :telemetry/metrics).

  Returns a vector of the form [:put-docs {:into table-name} doc1 doc2 ...]."
  ([packet]
   (metrics->put-tx packet :telemetry/metrics))
  ([packet table-name]
   (let [docs (build-metric-docs packet table-name)]
     (into [:put-docs {:into table-name}] docs))))

(defn submit-metrics!
  "Submit parsed telemetry metrics to an XTDB node via `xt/submit-tx`.

  Parameters:
  - connectable: XTDB DataSource, Connection, or node that `xt/submit-tx` accepts.
  - packet: parsed telemetry packet from `parse-packet`.
  - opts: optional map with keys
    - :table   keyword destination table (default :telemetry/metrics)
    - :tx-opts map of transaction options passed to XTDB (default {}).

  Returns the TransactionKey from `xt/submit-tx`."
  ([connectable packet]
   (submit-metrics! connectable packet {}))
  ([connectable packet {:keys [table tx-opts]
                        :or   {table   :telemetry/metrics
                               tx-opts {}}}]
   (let [tx-op (metrics->put-tx packet table)]
     (xt/submit-tx connectable [tx-op] tx-opts))))

;; ---- Node lifecycle management ----

(defn- make-stop-fn
  "Create a stop function for a node/client."
  [node-or-client]
  (fn []
    (when node-or-client
      (try
        (when (instance? Closeable node-or-client)
          (.close node-or-client))
        (catch Exception _ nil))
      ::stopped)))

(defn- start-in-memory-client
  "Start an in-memory XTDB node using xtdb.node/start-node.
   See https://docs.xtdb.com/drivers/clojure.html#clojure-api"
  []
  (require 'xtdb.node)
  (let [xtn-ns (find-ns 'xtdb.node)]
    (if xtn-ns
      (let [start-node-fn (ns-resolve xtn-ns 'start-node)]
        (if start-node-fn
          (let [node (start-node-fn)]
            {:node node
             :client node
             :stop! (make-stop-fn node)})
          (throw (ex-info "xtdb.node/start-node not found. Ensure com.xtdb/xtdb-core is in dependencies."
                         {}))))
      (throw (ex-info "xtdb.node namespace not found. Ensure com.xtdb/xtdb-core is in dependencies."
                     {})))))

(defn- start-node-with-xtdb-core
  "Start a node using xtdb.node/start-node with config.
   See https://docs.xtdb.com/drivers/clojure.html#clojure-api"
  [config]
  (require 'xtdb.node)
  (let [xtn-ns (find-ns 'xtdb.node)]
    (if xtn-ns
      (let [start-node-fn (ns-resolve xtn-ns 'start-node)]
        (if start-node-fn
          (let [node (start-node-fn config)]
            {:node node
             :stop! (make-stop-fn node)})
          (throw (ex-info "xtdb.node/start-node not found. Ensure com.xtdb/xtdb-core is in dependencies."
                         {}))))
      (throw (ex-info "xtdb.node namespace not found. Ensure com.xtdb/xtdb-core is in dependencies."
                     {})))))

(defn start-node!
  "Start an XTDB node with optional configuration.
   
   Parameters:
   - opts: optional map with keys:
     - :db-dir string path to database directory (default: \"data/xtdb\")
     - :in-memory? boolean if true, use in-memory storage (default: false)
   
   Returns a map with:
     - :node the XTDB node/client
     - :stop! function to stop the node
   
   For in-memory nodes, uses xtdb.api/client (works on Java 25).
   For persistent nodes, requires xtdb-core dependency."
  ([]
   (start-node! {}))
  ([{:keys [in-memory?]
     :or {in-memory? false}}]
   (if in-memory?
     (try
       (start-in-memory-client)
       (catch Exception e
         (throw (ex-info (str "Failed to start in-memory XTDB node: " (.getMessage e))
                        {:error (.getMessage e)}))))
     (try
       (start-node-with-xtdb-core {})
       (catch Exception e
         (throw (ex-info (str "Failed to start persistent XTDB node: " (.getMessage e)
                            ". Add com.xtdb/xtdb-core to dependencies, "
                            "or use :in-memory? true for testing.")
                        {:error (.getMessage e)})))))))

(defn connect-to-xtdb!
  "Connect to an existing XTDB instance via JDBC DataSource.
   
   Parameters:
   - jdbc-url: string JDBC URL (e.g., \"jdbc:xtdb:mem:test\" or \"jdbc:xtdb:http://localhost:3000\")
   
   Returns a map with:
     - :datasource the JDBC DataSource
     - :stop! function to close the connection
   
   This is useful when connecting to an external XTDB server."
  [jdbc-url]
  (try
    (let [ds (jdbc/get-datasource jdbc-url)]
      {:datasource ds
       :stop! (fn []
               (when ds
                 (try
                   (when (instance? java.io.Closeable ds)
                     (.close ds))
                   (catch Exception _ nil))
                 ::stopped))})
    (catch Exception e
      (throw (ex-info "Failed to connect to XTDB via JDBC"
                     {:jdbc-url jdbc-url
                      :error (.getMessage e)})))))

;; ---- Query helpers ----

(defn query-metrics
  "Query telemetry metrics from XTDB.
   
   Parameters:
   - connectable: XTDB node, DataSource, or Connection
   - opts: optional map with keys:
     - :name string - filter by metric name (e.g., \"temp\")
     - :sender string - filter by sender address
     - :since-ms long - only return metrics ingested after this timestamp
     - :limit int - maximum number of results (default: 1000)
     - :table keyword - table to query (default :telemetry/metrics)
   
   Returns a vector of result tuples [name value type device-time-us ingested-at-ms sender]."
  ([connectable]
   (query-metrics connectable {}))
  ([connectable {:keys [name sender since-ms limit table]
                 :or {limit 1000
                      table :telemetry/metrics}}]
   (let [where-clauses (cond-> '[[e :telemetry/name name]
                                 [e :telemetry/value value]
                                 [e :telemetry/type type]
                                 [e :telemetry/device-time-us device-time-us]
                                 [e :telemetry/ingested-at-ms ingested-at-ms]
                                 [e :telemetry/sender sender]]
                       table (conj '[[e :telemetry/table ?table]])
                       name (conj '[(= name ?name)])
                       sender (conj '[(= sender ?sender)])
                       since-ms (conj '[(>= ingested-at-ms ?since-ms)]))
         query {:find '[name value type device-time-us ingested-at-ms sender]
                :where where-clauses
                :limit limit}
         args (cond-> {}
                table (assoc :?table table)
                name (assoc :?name name)
                sender (assoc :?sender sender)
                since-ms (assoc :?since-ms since-ms))]
     (xt/q connectable query args))))

(defn query-metric-names
  "Get all unique metric names stored in XTDB.
   
   Parameters:
   - connectable: XTDB node, DataSource, or Connection
   - opts: optional map with keys:
     - :table keyword - table to query (default :telemetry/metrics)
   
   Returns a set of metric name strings."
  ([connectable]
   (query-metric-names connectable {}))
  ([connectable {:keys [table]
                 :or {table :telemetry/metrics}}]
   (let [where-clauses (cond-> '[[e :telemetry/name name]]
                       table (conj '[[e :telemetry/table ?table]]))
         query {:find '[name]
                :where where-clauses}
         args (if table {:?table table} {})]
     (set (map first (xt/q connectable query args))))))

(defn query-metric-count
  "Get the total count of metrics stored in XTDB.
   
   Parameters:
   - connectable: XTDB node, DataSource, or Connection
   - opts: optional map with keys:
     - :name string - filter by metric name
     - :sender string - filter by sender
     - :table keyword - table to query (default :telemetry/metrics)
   
   Returns the count as a long."
  ([connectable]
   (query-metric-count connectable {}))
  ([connectable {:keys [name sender table]
                 :or {table :telemetry/metrics}}]
   (let [where-clauses (cond-> '[[e :telemetry/name name]]
                       table (conj '[[e :telemetry/table ?table]])
                       name (conj '[(= name ?name)])
                       sender (conj '[[e :telemetry/sender sender] [(= sender ?sender)]]))
         query {:find '[e]
                :where where-clauses}
         args (cond-> {}
                table (assoc :?table table)
                name (assoc :?name name)
                sender (assoc :?sender sender))]
     (count (xt/q connectable query args)))))

;; ---- Test data helpers ----

(defn insert-test-packet!
  "Insert a test telemetry packet into XTDB for experimentation.
   
   Parameters:
   - connectable: XTDB node, DataSource, or Connection
   - packet: parsed telemetry packet map
   - opts: optional map with keys:
     - :table keyword - table to insert into (default :telemetry/metrics)
     - :tx-opts map - transaction options (default {})
   
   Returns the TransactionKey from xt/submit-tx.
   
   Example:
   (insert-test-packet! (:node @xtdb-node)
                        {:prelude {:msg 7 :tm 9000}
                         :metrics [{:name \"temp\" :value 25 :tick 1 :device-time-us 2000}
                                  {:name \"status\" :fields {:state \"ready\"} :tick 2}]
                         :sender \"test-printer\"})"
  ([connectable packet]
   (insert-test-packet! connectable packet {}))
  ([connectable packet {:keys [table tx-opts]
                       :or {table :telemetry/metrics
                            tx-opts {}}}]
   (submit-metrics! connectable packet {:table table :tx-opts tx-opts})))

;; ---- Stream integration ----

(defn connect-telemetry-stream!
  "Connect a telemetry stream to XTDB for automatic metric storage.
   
   Parameters:
   - connectable: XTDB node, DataSource, or Connection
   - stream: manifold stream of processed telemetry packets
   - opts: optional map with keys:
     - :table keyword destination table (default :telemetry/metrics)
     - :tx-opts map of transaction options (default {})
     - :on-error function to handle errors (default: prints to stderr)
   
   Returns a deferred that completes when the stream is consumed.
   Errors are logged but don't stop the stream."
  ([connectable stream]
   (connect-telemetry-stream! connectable stream {}))
  ([connectable stream {:keys [table tx-opts on-error]
                        :or {table :telemetry/metrics
                             tx-opts {}
                             on-error (fn [e packet]
                                       (binding [*out* *err*]
                                         (println "ERROR submitting metrics to XTDB:")
                                         (println "  Packet:" (select-keys packet [:sender :prelude]))
                                         (println "  Error:" (.getMessage e))
                                         (when (instance? Throwable e)
                                           (.printStackTrace e))))}}]
   (let [consume-fn (fn [packet]
                     (try
                       (when-not (:error packet)
                         (submit-metrics! connectable packet {:table table :tx-opts tx-opts}))
                       (catch Exception e
                         (on-error e packet))))]
     (s/consume consume-fn stream))))
