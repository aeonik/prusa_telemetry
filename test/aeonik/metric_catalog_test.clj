(ns aeonik.metric-catalog-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def catalog
  (-> "telemetry/metrics.edn"
      io/resource
      slurp
      edn/read-string))

(deftest metric-catalog-loads
  (is (= 1 (:schema/version catalog)))
  (is (= :prusa-buddy-metrics (:catalog/id catalog)))
  (is (= 146 (count (:metrics catalog)))))

(deftest metric-names-are-unique
  (let [names (map :name (:metrics catalog))]
    (is (= (count names) (count (set names))))))

(deftest metrics-have-required-documentation
  (doseq [{:keys [name type source tooltip parser parse]} (:metrics catalog)]
    (testing name
      (is (string? name))
      (is (keyword? type))
      (is (string? source))
      (is (and (string? tooltip) (not (str/blank? tooltip))))
      (is (or parser parse))
      (when parser
        (is (contains? (:parsers catalog) parser))))))
