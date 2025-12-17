(ns aeonik.xtdb
  (:require [clojure.string :as str]
            [xtdb.api :as xt]))

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
