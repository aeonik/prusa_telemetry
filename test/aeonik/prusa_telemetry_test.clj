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
    (is (= {:msg 7 :base-time-us 9000 :v 2}
           (telemetry/parse-prelude "msg=7,tm=9000,v=2")))
    (is (= {:type :numeric
            :name "temp"
            :value 25
            :offset-us 1
            :offset-ms 0.001
            :device-time-us 1001}
           (telemetry/parse-metric-line "temp v=25i 1" 1000)))))

(deftest parse-string-metric-with-spaces
  (testing "Keeps quoted string metric values intact"
    (is (= {:type :numeric
            :name "print_filename"
            :value "OpenSCAD Model_0.4n_0.2mm_PP_Prusa MK4S_20m44s."
            :offset-us 71976
            :offset-ms 71.976
            :device-time-us 71976}
           (telemetry/parse-metric-line
            "print_filename v=\"OpenSCAD Model_0.4n_0.2mm_PP_Prusa MK4S_20m44s.\" 71976"
            0)))))
