(ns aeonik.ws
  (:require [aeonik.events :refer [dispatch!]]
            [aeonik.state :refer [app-state]]))

(defn- parse-ws-message [data]
  (let [sender (aget data "sender")
        metrics (aget data "metrics")
        wall-time-str (aget data "wall-time-str")
        prelude-obj (aget data "prelude")
        prelude (when prelude-obj
                  {:msg (aget prelude-obj "msg")
                   :tm (aget prelude-obj "tm")
                   :v (aget prelude-obj "v")})
        received-at (aget data "received-at")
        ;; Extract print_filename
        print-filename-metric (first (filter #(= (aget % "name") "print_filename") (array-seq metrics)))
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
        ;; Convert metrics array to Clojure data
        metrics-clj (map (fn [m]
                           {:name (aget m "name")
                            :value (or (aget m "value") (aget m "error"))
                            :fields (aget m "fields")
                            :error (aget m "error")
                            :type (aget m "type")
                            :tick (aget m "tick")
                            :device-time-us (aget m "device-time-us")
                            :device-time-str (aget m "device-time-str")})
                         (array-seq metrics))]
    {:type :ws/message
     :sender sender
     :metrics metrics-clj
     :wall-time-str wall-time-str
     :prelude prelude
     :received-at received-at
     :print-filename print-filename}))

(defn connect-websocket! []
  (let [protocol (if (= js/location.protocol "https:") "wss:" "ws:")
        ;; Use relative URL - shadow-cljs proxies /ws to backend automatically
        ws-url (str protocol "//" js/location.host "/ws")
        socket (js/WebSocket. ws-url)]
    (println "Connecting to WebSocket:" ws-url)
    (swap! app-state assoc :ws socket)
    (set! (.-onopen socket)
          (fn [_]
            (println "WebSocket connected")
            (dispatch! {:type :connection/open})))
    (set! (.-onclose socket)
          (fn [_]
            (dispatch! {:type :connection/close})))
    (set! (.-onerror socket)
          (fn [_]
            (dispatch! {:type :connection/close})))
    (set! (.-onmessage socket)
          (fn [event]
            (try
              (let [data (js/JSON.parse (.-data event))]
                (dispatch! (parse-ws-message data)))
              (catch :default e
                (println "Error parsing message:" e)
                (js/console.error e)))))
    socket))
