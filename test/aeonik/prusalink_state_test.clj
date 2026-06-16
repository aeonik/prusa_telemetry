(ns aeonik.prusalink-state-test
  (:require [aeonik.prusalink-state :as state]
            [clojure.test :refer :all]))

(deftest prusalink-print-state-starts-new-run-when-time-rolls-back
  (let [first-state (state/status->print-state
                     {:printer {:state "PRINTING"}
                      :job {:id 229 :progress 42.0 :time_printing 600}}
                     {:available? true :active? false}
                     1000)
        second-state (state/status->print-state
                      {:printer {:state "PRINTING"}
                       :job {:id 229 :progress 2.0 :time_printing 20}}
                      first-state
                      2000)]
    (is (re-matches #"job-229_run-.+" (:run-id first-state)))
    (is (re-matches #"job-229_run-.+" (:run-id second-state)))
    (is (not= (:run-id first-state) (:run-id second-state)))))
