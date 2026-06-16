(ns aeonik.archive-test
  (:require [aeonik.archive :as archive]
            [clojure.test :refer :all]))

(defn- packet
  [sender metrics]
  {:sender sender
   :received-at (java.util.Date.)
   :metrics metrics})

(defn- test-state
  ([] (test-state {:available? false :active? nil}))
  ([prusalink-state]
   (archive/make-state {:active-prints (atom {})
                        :prusalink-state (atom prusalink-state)
                        :now-ms (constantly 1000)
                        :log-fn (fn [& _])})))

(deftest print-filename-normalization
  (testing "Blank firmware filenames are rejected"
    (is (nil? (archive/normalize-print-filename "")))
    (is (nil? (archive/normalize-print-filename "   ")))
    (is (nil? (archive/sanitize-filename ""))))
  (testing "Boundary quotes from string metrics are removed"
    (is (= "OpenSCAD" (archive/normalize-print-filename "\"OpenSCAD")))
    (is (= "OpenSCAD" (archive/normalize-print-filename "\"OpenSCAD\"")))
    (is (= "OpenSCAD_Model_0.4n_0.2mm_PP_Prusa_MK4S_20m44s.edn"
           (str (archive/sanitize-filename
                 "\"OpenSCAD Model_0.4n_0.2mm_PP_Prusa MK4S_20m44s.")
                ".edn")))))

(deftest archive-path-canonicalization
  (testing "Valid archive paths resolve under the archive root"
    (is (some? (archive/archive-file "2026-06-15" "print.edn"))))
  (testing "Traversal and invalid archive names are rejected"
    (is (nil? (archive/archive-file "../config" "prusalink.edn")))
    (is (nil? (archive/archive-file "2026-06-15" "../config/prusalink.edn")))
    (is (nil? (archive/archive-file "2026-06-15/.." "print.edn")))
    (is (nil? (archive/archive-file "2026-06-15" "print.gcode")))))

(deftest packet-saving-tracks-active-print
  (let [state (test-state)
        saved (atom [])]
    (with-redefs [archive/save-packet-to-file! (fn [_ _ filename]
                                                 (swap! saved conj filename)
                                                 true)]
      (archive/handle-packet-saving!
       state
       (packet "printer-1"
               [{:name "print_filename"
                 :value "OpenSCAD Model_0.4n_0.2mm_PP_Prusa MK4S_20m44s."}]))
      (archive/handle-packet-saving!
       state
       (packet "printer-1" [{:name "sdpos" :value 100}]))
      (archive/handle-packet-saving!
       state
       (packet "printer-1" [{:name "print_filename" :value ""}]))
      (archive/handle-packet-saving!
       state
       (packet "printer-1" [{:name "sdpos" :value 200}])))
    (is (= ["OpenSCAD Model_0.4n_0.2mm_PP_Prusa MK4S_20m44s."
            "OpenSCAD Model_0.4n_0.2mm_PP_Prusa MK4S_20m44s."]
           @saved))
    (is (nil? (get @(:active-prints state) "printer-1")))))

(deftest packet-saving-stops-when-prusalink-job-ends
  (let [state (test-state {:available? true :active? true})
        saved (atom [])]
    (with-redefs [archive/save-packet-to-file! (fn [_ _ filename]
                                                 (swap! saved conj filename)
                                                 true)]
      (archive/handle-packet-saving!
       state
       (packet "printer-1"
               [{:name "print_filename"
                 :value "Active Print.gcode"}]))
      (reset! (:prusalink-state state) {:available? true :active? false})
      (archive/handle-packet-saving!
       state
       (packet "printer-1" [{:name "sdpos" :value 200}]))
      (archive/handle-packet-saving!
       state
       (packet "printer-1"
               [{:name "print_filename"
                 :value "Active Print.gcode"}])))
    (is (= ["Active Print.gcode"] @saved))
    (is (nil? (get @(:active-prints state) "printer-1")))))

(deftest packet-saving-separates-same-filename-prusalink-reruns
  (let [state (test-state {:available? true
                           :active? true
                           :job-id 101
                           :run-id "job-101_run-first"})
        saved (atom [])]
    (with-redefs [archive/save-packet-to-file! (fn [_ _ filename]
                                                 (swap! saved conj filename)
                                                 true)]
      (archive/handle-packet-saving!
       state
       (packet "printer-1"
               [{:name "print_filename"
                 :value "Repeated Print.gcode"}]))
      (reset! (:prusalink-state state)
              {:available? true
               :active? true
               :job-id 101
               :run-id "job-101_run-second"})
      (archive/handle-packet-saving!
       state
       (packet "printer-1" [{:name "sdpos" :value 200}]))
      (archive/handle-packet-saving!
       state
       (packet "printer-1"
               [{:name "print_filename"
                 :value "Repeated Print.gcode"}])))
    (is (= ["Repeated Print.gcode_job-101_run-first"
            "Repeated Print.gcode_job-101_run-second"
            "Repeated Print.gcode_job-101_run-second"]
           @saved))))
