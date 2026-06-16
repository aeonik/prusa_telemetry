(ns aeonik.prusa-telemetry-test
  (:require [clojure.test :refer :all]
            [aeonik.prusa-telemetry :as telemetry]))

(deftest parse-value-coercion
  (testing "Parses numeric and string payloads"
    (is (= 42 (telemetry/parse-value "42i")))
    (is (= 42 (telemetry/parse-value "42")))
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
    (is (= {:type :string
            :name "print_filename"
            :value "OpenSCAD Model_0.4n_0.2mm_PP_Prusa MK4S_20m44s."
            :offset-us 71976
            :offset-ms 71.976
            :device-time-us 71976}
           (telemetry/parse-metric-line
            "print_filename v=\"OpenSCAD Model_0.4n_0.2mm_PP_Prusa MK4S_20m44s.\" 71976"
            0)))))

(deftest parse-scalar-metric-with-custom-catalog
  (testing "Uses catalog scalar type when a metric definition exists"
    (is (= {:type :string
            :name "serial_number"
            :value "12345"
            :offset-us 7
            :offset-ms 0.007
            :device-time-us 1007}
           (telemetry/parse-metric-line
            "serial_number v=\"12345\" 7"
            1000
            {:metrics [{:name "serial_number"
                        :type :string}]})))))

(deftest parse-custom-metric-tags-and-fields
  (testing "Separates line-protocol tags from the metric name"
    (is (= {:type :structured
            :name "temp_noz"
            :tags {"n" 0, "a" 1}
            :fields {"value" 215.5}
            :offset-us 123
            :offset-ms 0.123
            :device-time-us 1123}
           (telemetry/parse-metric-line
            "temp_noz,n=0,a=1 value=215.5 123"
            1000)))))

(deftest parse-packet-keeps-first-metric-after-prelude
  (testing "Firmware emits the first metric on the same line as the syslog prelude"
    (let [packet (telemetry/parse-packet
                  {:sender "printer"
                   :message "<14>1 - mac buddy - - - msg=7,tm=9000,v=4 stp_stall v=12i 10\nsdpos v=99i 20\n"})]
      (is (= {:msg 7 :base-time-us 9000 :v 4} (:prelude packet)))
      (is (= ["stp_stall" "sdpos"] (mapv :name (:metrics packet))))
      (is (= [9010 9020] (mapv :device-time-us (:metrics packet)))))))
