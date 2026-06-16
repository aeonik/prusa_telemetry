(ns aeonik.ws
  (:require [aeonik.events :refer [dispatch!]]
            [aeonik.state :refer [app-state]]
            [goog.object :as gobj]))

(defn- js-fields->clj [fields]
  (when fields
    (js->clj fields :keywordize-keys false)))

(defn- parse-ws-message [data]
  (try
    (if (= "prusalink/state" (aget data "event"))
      {:type :prusalink/stream-state
       :data (js->clj (aget data "data") :keywordize-keys true)
       :received-at (.now js/Date)}
      (let [sender (aget data "sender")
          metrics (aget data "metrics")
          wall-time-str (aget data "wall-time-str")
          prelude-obj (aget data "prelude")
          prelude (when prelude-obj
                    {:msg (aget prelude-obj "msg")
                     :base-time-us (or (aget prelude-obj "base-time-us")
                                       (aget prelude-obj "tm"))
                     :v (aget prelude-obj "v")})
          received-at (aget data "received-at")
          ;; Extract print_filename - handle case where metrics might be missing
          metrics-array (when metrics (array-seq metrics))
          print-filename-metric (when metrics-array
                                  (first (filter #(= (aget % "name") "print_filename") metrics-array)))
          print-filename (if print-filename-metric
                          (let [value (aget print-filename-metric "value")
                                fields (aget print-filename-metric "fields")]
                            (cond
                              (and (string? value) (not= value "")) value
                              (and (object? fields) (not (nil? fields)))
                              (let [keys (js/Object.keys fields)]
                                (if (> (count keys) 0)
                                  (aget fields (first keys))
                                  nil))
                              (map? fields) (get fields "value")
                              :else nil))
                          nil)
          ;; Convert metrics array to Clojure data - handle case where metrics might be missing
          metrics-clj (if metrics-array
                        (doall (map (fn [m]
                                      {:name (aget m "name")
                                       :value (or (aget m "value") (aget m "error"))
                                       :fields (js-fields->clj (aget m "fields"))
                                       :error (aget m "error")
                                       :type (aget m "type")
                                       :offset-us (aget m "offset-us")
                                       :offset-ms (aget m "offset-ms")
                                       :device-time-us (aget m "device-time-us")
                                       :device-time-str (aget m "device-time-str")})
                                   metrics-array))
                        [])]
      {:type :ws/message
       :sender sender
       :metrics metrics-clj
       :wall-time-str wall-time-str
       :prelude prelude
       :received-at received-at
       :print-filename print-filename}))
    (catch :default e
      (js/console.error "Error parsing WebSocket message:" e)
      (throw e))))

(defn connect-websocket! []
  (let [protocol (if (= js/location.protocol "https:") "wss:" "ws:")
        ;; Use relative URL - shadow-cljs proxies /ws to backend automatically
        ws-url (str protocol "//" js/location.host "/ws")
        socket (js/WebSocket. ws-url)]
    (swap! app-state assoc :ws socket)
    (gobj/set socket "onopen"
              (fn [_]
                (dispatch! {:type :connection/open})))
    (gobj/set socket "onclose"
              (fn [_]
                (dispatch! {:type :connection/close})))
    (gobj/set socket "onerror"
              (fn [_]
                (dispatch! {:type :connection/close})))
    (gobj/set socket "onmessage"
              (fn [event]
                (try
                  (let [data (js/JSON.parse (.-data event))
                        parsed (parse-ws-message data)]
                    (dispatch! parsed))
                  (catch :default e
                    (js/console.error "Error parsing WebSocket message:" e)))))
    socket))
