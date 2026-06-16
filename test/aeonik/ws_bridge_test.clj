(ns aeonik.ws-bridge-test
  (:require [aeonik.ws-bridge :as ws-bridge]
            [clojure.data.json :as json]
            [clojure.test :refer :all]
            [manifold.stream :as s]))

(defn- packet
  []
  {:sender "printer-1"
   :received-at (java.util.Date. 0)
   :prelude {:msg 42}
   :metrics [{:name "sdpos"
              :type :numeric
              :value 123
              :device-time-us 456}]
   :display-lines []
   :wall-time-str "00:00:00.000"})

(deftest send-packet-writes-json-to-open-websocket
  (let [ws (s/stream 1)
        delivered? @(ws-bridge/send-packet! ws (packet) 1000)
        body @(s/take! ws)
        decoded (json/read-str body :key-fn keyword)]
    (is (true? delivered?))
    (is (= "printer-1" (:sender decoded)))
    (is (= 42 (get-in decoded [:prelude :msg])))
    (is (= "sdpos" (get-in decoded [:metrics 0 :name])))))

(deftest send-packet-returns-false-for-closed-websocket
  (let [ws (s/stream 1)]
    (s/close! ws)
    (is (false? @(ws-bridge/send-packet! ws (packet) 10)))))

(deftest send-prusalink-state-wraps-dashboard-event
  (let [ws (s/stream 1)
        delivered? @(ws-bridge/send-prusalink-state! ws {:available? true
                                                         :active? false} 1000)
        body @(s/take! ws)
        decoded (json/read-str body :key-fn keyword)]
    (is (true? delivered?))
    (is (= "prusalink/state" (:event decoded)))
    (is (= {:available? true
            :active? false}
           (:data decoded)))))
