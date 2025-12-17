(ns aeonik.files-test
  (:require [aeonik.files :as files]
            [clojure.test :refer :all]))

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
    (let [packets (files/normalize-packets
                   [{:sender :buddy
                     :wall-time-str "2025-01-01T00:00:00Z"
                     :wall-time-ms 10
                     :metrics [{:name "temp" :value 42 :fields {:raw 1} :tick 1 :type :numeric :device-time-us 2 :device-time-str "2"}]}])]
      (is (= [{:sender "buddy" :wall-time-str "2025-01-01T00:00:00Z" :wall-time-ms 10
               :metrics [{:name "temp" :value 42 :fields {:raw 1} :error nil :type :numeric :tick 1 :device-time-us 2 :device-time-str "2" :wall-time-ms nil}]}]
             (map #(select-keys % [:sender :wall-time-str :wall-time-ms :metrics]) packets)))))
  (testing "Handles missing metrics"
    (let [packets (files/normalize-packets [{:sender :none :metrics nil}])]
      (is (= [{:sender "none" :wall-time-str nil :wall-time-ms nil :metrics []}]
             (map #(select-keys % [:sender :wall-time-str :wall-time-ms :metrics]) packets)))))
  (testing "Drops malformed metrics"
    (let [packets (files/normalize-packets [{:sender "p" :wall-time-str "t" :wall-time-ms "5"
                                            :metrics [{:value 1} "bad"]}])]
      (is (= [{:sender "p" :wall-time-str "t" :wall-time-ms 5
               :metrics [{:name nil :value 1 :fields nil :error nil :type nil :tick nil :device-time-us nil :device-time-str nil :wall-time-ms nil}]}]
             (map #(select-keys % [:sender :wall-time-str :wall-time-ms :metrics]) packets)))))
  (testing "Returns empty for non-sequential input"
    (is (= [] (files/normalize-packets {:not-a-seq true})))))
