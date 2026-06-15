(ns aeonik.web-server-test
  (:require [aeonik.web-server :as web]
            [clojure.test :refer :all]))

(defn- packet
  [sender metrics]
  {:sender sender
   :received-at (java.util.Date.)
   :metrics metrics})

(deftest print-filename-normalization
  (testing "Blank firmware filenames are rejected"
    (is (nil? (#'web/normalize-print-filename "")))
    (is (nil? (#'web/normalize-print-filename "   ")))
    (is (nil? (#'web/sanitize-filename ""))))
  (testing "Boundary quotes from string metrics are removed"
    (is (= "OpenSCAD" (#'web/normalize-print-filename "\"OpenSCAD")))
    (is (= "OpenSCAD" (#'web/normalize-print-filename "\"OpenSCAD\"")))
    (is (= "OpenSCAD_Model_0.4n_0.2mm_PP_Prusa_MK4S_20m44s.edn"
           (str (#'web/sanitize-filename
                 "\"OpenSCAD Model_0.4n_0.2mm_PP_Prusa MK4S_20m44s.")
                ".edn")))))

(deftest packet-saving-tracks-active-print
  (reset! (var-get #'web/active-prints) {})
  (reset! (var-get #'web/prusalink-print-state) {:available? false :active? nil})
  (let [saved (atom [])]
    (with-redefs-fn {#'web/save-packet-to-file! (fn [_ filename]
                                                  (swap! saved conj filename)
                                                  true)
                     #'web/telemetry-to-json identity}
      (fn []
        (#'web/handle-packet-saving!
         (packet "printer-1"
                 [{:name "print_filename"
                   :value "OpenSCAD Model_0.4n_0.2mm_PP_Prusa MK4S_20m44s."}]))
        (#'web/handle-packet-saving!
         (packet "printer-1" [{:name "sdpos" :value 100}]))
        (#'web/handle-packet-saving!
         (packet "printer-1" [{:name "print_filename" :value ""}]))
        (#'web/handle-packet-saving!
         (packet "printer-1" [{:name "sdpos" :value 200}]))))
    (is (= ["OpenSCAD Model_0.4n_0.2mm_PP_Prusa MK4S_20m44s."
	            "OpenSCAD Model_0.4n_0.2mm_PP_Prusa MK4S_20m44s."]
	           @saved))
	    (is (nil? (get @(var-get #'web/active-prints) "printer-1")))))

(deftest packet-saving-stops-when-prusalink-job-ends
  (reset! (var-get #'web/active-prints) {})
  (reset! (var-get #'web/prusalink-print-state) {:available? true :active? true})
  (let [saved (atom [])]
    (with-redefs-fn {#'web/save-packet-to-file! (fn [_ filename]
                                                  (swap! saved conj filename)
                                                  true)
                     #'web/telemetry-to-json identity}
      (fn []
        (#'web/handle-packet-saving!
         (packet "printer-1"
                 [{:name "print_filename"
                   :value "Active Print.gcode"}]))
        (reset! (var-get #'web/prusalink-print-state) {:available? true :active? false})
        (#'web/handle-packet-saving!
         (packet "printer-1" [{:name "sdpos" :value 200}]))
        (#'web/handle-packet-saving!
         (packet "printer-1"
                 [{:name "print_filename"
                   :value "Active Print.gcode"}]))))
	    (is (= ["Active Print.gcode"] @saved))
	    (is (nil? (get @(var-get #'web/active-prints) "printer-1")))
    (reset! (var-get #'web/prusalink-print-state) {:available? false :active? nil})))

(deftest packet-saving-separates-same-filename-prusalink-reruns
  (reset! (var-get #'web/active-prints) {})
  (reset! (var-get #'web/prusalink-print-state)
          {:available? true :active? true :job-id 101 :run-id "job-101_run-first"})
  (let [saved (atom [])]
    (with-redefs-fn {#'web/save-packet-to-file! (fn [_ filename]
                                                  (swap! saved conj filename)
                                                  true)
                     #'web/telemetry-to-json identity}
      (fn []
        (#'web/handle-packet-saving!
         (packet "printer-1"
                 [{:name "print_filename"
                   :value "Repeated Print.gcode"}]))
        (reset! (var-get #'web/prusalink-print-state)
                {:available? true :active? true :job-id 101 :run-id "job-101_run-second"})
        (#'web/handle-packet-saving!
         (packet "printer-1" [{:name "sdpos" :value 200}]))
        (#'web/handle-packet-saving!
         (packet "printer-1"
                 [{:name "print_filename"
                   :value "Repeated Print.gcode"}]))))
    (is (= ["Repeated Print.gcode_job-101_run-first"
            "Repeated Print.gcode_job-101_run-second"
            "Repeated Print.gcode_job-101_run-second"]
           @saved))
    (reset! (var-get #'web/prusalink-print-state) {:available? false :active? nil})))

(deftest prusalink-print-state-starts-new-run-when-time-rolls-back
  (let [first-state (#'web/prusalink-status->print-state
                     {:printer {:state "PRINTING"}
                      :job {:id 229 :progress 42.0 :time_printing 600}}
                     {:available? true :active? false}
                     1000)
        second-state (#'web/prusalink-status->print-state
                      {:printer {:state "PRINTING"}
                       :job {:id 229 :progress 2.0 :time_printing 20}}
                      first-state
                      2000)]
    (is (re-matches #"job-229_run-.+" (:run-id first-state)))
    (is (re-matches #"job-229_run-.+" (:run-id second-state)))
    (is (not= (:run-id first-state) (:run-id second-state)))))
