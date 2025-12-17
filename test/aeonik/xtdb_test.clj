(ns aeonik.xtdb-test
  (:require [aeonik.xtdb :as xtdb]
            [clojure.test :refer :all]
            [xtdb.api :as xt]))

(deftest metric-doc-id-composes-fields
  (testing "IDs combine message metadata, metric keys, and sender"
    (is (= "7:9000:1:temp:printer1"
           (xtdb/metric-doc-id {:msg 7 :tm 9000}
                               {:name "temp" :tick 1}
                               "printer 1")))))

(deftest build-metric-docs-creates-rich-documents
  (testing "Documents carry message metadata, timestamps, and optional fields"
    (let [packet {:prelude     {:msg 7 :tm 9000}
                  :metrics     [{:name "temp" :value 25 :tick 1 :device-time-us 2000}
                                {:name "status" :fields {:state "ready"} :tick 2}]
                  :sender      "node-A"
                  :received-at (java.util.Date. 0)}
          docs   (xtdb/build-metric-docs packet :telemetry/raw)]
      (is (= 2 (count docs)))
      (is (= :telemetry/raw (:telemetry/table (first docs))))
      (is (= 0 (:telemetry/ingested-at-ms (first docs))))
      (is (= "ready" (get-in (second docs) [:telemetry/fields :state]))))))

(deftest metrics->put-tx-wraps-docs
  (testing "Transaction op uses :put-docs with provided table"
    (let [packet {:prelude {:msg 1 :tm 10}
                  :metrics [{:name "x" :value 1 :tick 1}]}
          tx-op  (xtdb/metrics->put-tx packet :telemetry/raw)]
      (is (= :put-docs (first tx-op)))
      (is (= {:into :telemetry/raw} (second tx-op)))
      (is (= 3 (count tx-op))))))

(deftest submit-metrics!-delegates-to-xtdb
  (testing "Submission forwards to xt/submit-tx with tx-opts"
    (let [packet {:prelude {:msg 1 :tm 1}
                  :metrics [{:name "y" :tick 1 :value 2}]}
          captured (atom nil)]
      (with-redefs [xt/submit-tx (fn [& args]
                                   (reset! captured args)
                                   ::tx-key)]
        (is (= ::tx-key (xtdb/submit-metrics! :conn packet {:table :telemetry/raw
                                                             :tx-opts {:sync? true}}))))
      (let [[conn tx-ops tx-opts] @captured]
        (is (= :conn conn))
        (is (= {:sync? true} tx-opts))
        (is (= :put-docs (ffirst tx-ops)))))))
