(ns aeonik.archive-repair-test
  (:require [aeonik.archive-repair :as repair]
            [clojure.test :refer :all]))

(deftest repair-metric-moves-embedded-tags
  (is (= {:name "temp_noz"
          :tags {"n" 0, "a" 1}
          :fields {"value" 215.5}}
         (repair/repair-metric
          {:name "temp_noz,n=0,a=1"
           :fields {"value" 215.5}}))))

(deftest repair-metric-preserves-existing-tags
  (is (= {:name "temp_noz"
          :tags {"n" 0, "a" 2, "source" "archive"}}
         (repair/repair-metric
          {:name "temp_noz,n=0,a=1"
           :tags {"a" 2, "source" "archive"}}))))

(deftest repair-metric-leaves-clean-name-alone
  (let [metric {:name "sdpos" :value 100}]
    (is (identical? metric (repair/repair-metric metric)))))

(deftest repair-line-rewrites-changed-packet
  (let [line (pr-str {:metrics [{:name "temp_noz,n=0,a=1"
                                 :fields {"value" 215.5}}
                                {:name "sdpos"
                                 :value 100}]})
        repaired (repair/repair-line line)]
    (is (= 1 (:changed-metrics repaired)))
    (is (= [{:name "temp_noz"
             :fields {"value" 215.5}
             :tags {"n" 0, "a" 1}}
            {:name "sdpos"
             :value 100}]
           (:metrics (read-string (:line repaired)))))))
