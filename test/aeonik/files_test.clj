(ns aeonik.files-test
  (:require [clojure.test :refer :all]
            [aeonik.files :as files]))

(deftest normalize-available-files-sanitizes-entries
  (testing "Filters and coerces file metadata"
    (is (= [{:date "2025-01-01" :filename "run.edn" :size 1024 :modified 123}
            {:date "2025-01-02" :filename "print.edn" :size 0 :modified nil}]
           (files/normalize-available-files
            [{:date "2025-01-01" :filename "run.edn" :size 1024 :modified 123}
             {:date "2025-01-02" :filename "print.edn"}
             {:date nil :filename "skip.edn"}
             "ignored"]))))
  (testing "Returns empty vector for invalid inputs"
    (is (= [] (files/normalize-available-files [nil {} "bad"]))))
  (testing "Safely coerces numeric fields"
    (is (= [{:date "2025-01-03" :filename "numbers.edn" :size 2048 :modified 1000}]
           (files/normalize-available-files
            [{:date "2025-01-03" :filename "numbers.edn" :size "2048" :modified "1000"}])))))

(deftest normalize-packets-coerces-metrics
  (testing "Ensures metrics are vectorized and string keys preserved"
    (is (= [{:sender "buddy" :wall-time-str "2025-01-01T00:00:00Z" :wall-time-ms 10
             :metrics [{:name "temp" :value 42 :fields {:raw 1} :error nil :type :numeric :tick 1 :device-time-us 2 :device-time-str "2" :wall-time-ms nil}]}]
           (files/normalize-packets
            [{:sender :buddy
              :wall-time-str "2025-01-01T00:00:00Z"
              :wall-time-ms 10
              :metrics [{:name "temp" :value 42 :fields {:raw 1} :tick 1 :type :numeric :device-time-us 2 :device-time-str "2"}]}]))))
  (testing "Handles missing metrics"
    (is (= [{:sender "none" :wall-time-str nil :wall-time-ms nil :metrics []}]
           (files/normalize-packets [{:sender :none :metrics nil}])))
  (testing "Drops malformed metrics"
    (is (= [{:sender "p" :wall-time-str "t" :wall-time-ms 5
             :metrics [{:name nil :value 1 :fields nil :error nil :type nil :tick nil :device-time-us nil :device-time-str nil :wall-time-ms nil}]}]
           (files/normalize-packets [{:sender "p" :wall-time-str "t" :wall-time-ms "5"
                                      :metrics [{:value 1} "bad"]}]))))
  (testing "Returns empty for non-sequential input"
    (is (= [] (files/normalize-packets {:not-a-seq true}))))))
