(ns aeonik.prusa-telemetry-test
  (:require [clojure.test :refer :all]
            [aeonik.prusa-telemetry :as telemetry]))

(deftest parse-value-coercion
  (testing "Parses numeric and string payloads"
    (is (= 42 (telemetry/parse-value "42i")))
    (is (= 3.5 (telemetry/parse-value "3.5")))
    (is (= "raw" (telemetry/parse-value "\"raw\"")))
    (is (= "unknown" (telemetry/parse-value "unknown")))))

(deftest parse-prelude-and-metric-line
  (testing "Extracts prelude data and coerces metric lines"
    (is (= {:msg 7 :tm 9000 :v 2}
           (telemetry/parse-prelude "msg=7,tm=9000,v=2")))
    (is (= {:type :numeric
            :name "temp"
            :value 25
            :tick 1
            :device-time-us 2000}
           (telemetry/parse-metric-line "temp v=25i 1" 1000)))))
