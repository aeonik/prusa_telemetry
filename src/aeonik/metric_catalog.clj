(ns aeonik.metric-catalog
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def default-resource "telemetry/metrics.edn")

(defn load-catalog
  "Load a telemetry metric catalog from a classpath resource."
  ([] (load-catalog default-resource))
  ([resource-name]
   (if-let [resource (io/resource resource-name)]
     (edn/read-string (slurp resource))
     (throw (ex-info "Metric catalog resource not found"
                     {:resource resource-name})))))

(defonce ^:private default-catalog
  (delay (load-catalog)))

(defn catalog
  "Return the default telemetry metric catalog."
  []
  @default-catalog)

(defn metrics-by-name
  "Return metric definitions keyed by firmware metric name."
  [catalog]
  (into {} (map (juxt :name identity)) (:metrics catalog)))

(defonce ^:private default-metrics-by-name
  (delay (metrics-by-name (catalog))))

(defn metric-definition
  "Return the catalog entry for metric-name, when known."
  ([metric-name] (get @default-metrics-by-name metric-name))
  ([catalog metric-name]
   (get (metrics-by-name catalog) metric-name)))

(defn scalar-metric-type
  "Return the scalar metric type for a known non-custom metric."
  [metric-definition]
  (let [metric-type (:type metric-definition)]
    (when (contains? #{:float :integer :string :event} metric-type)
      metric-type)))
