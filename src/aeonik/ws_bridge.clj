(ns aeonik.ws-bridge
  (:require
   [aeonik.archive :as archive]
   [aleph.http :as http]
   [clojure.data.json :as json]
   [manifold.deferred :as d]
   [manifold.stream :as s]))

(def default-client-buffer-size 100)
(def default-write-timeout-ms 1000)

(defn- error-message
  "Return a printable error message."
  [error]
  (cond
    (instance? Throwable error) (.getMessage ^Throwable error)
    (some? error) (str error)
    :else "unknown error"))

(defn packet-json
  "Serialize a telemetry packet for WebSocket clients."
  [packet]
  (json/write-str (archive/telemetry-to-json packet)))

(defn send-packet!
  "Send one telemetry packet to a WebSocket sink.

   Returns a deferred that realizes true on delivery and false if the socket is
   already closed, serialization fails, or the write cannot complete before the
   deadline."
  ([ws packet]
   (send-packet! ws packet default-write-timeout-ms))
  ([ws packet write-timeout-ms]
   (if (s/closed? ws)
     (d/success-deferred false)
     (try
       (s/try-put! ws (packet-json packet) write-timeout-ms false)
       (catch Exception e
         (println "ERROR serializing WebSocket telemetry packet:" (error-message e))
         (d/success-deferred false))))))

(defn- close-stream!
  "Close a stream if it is not already closed."
  [stream]
  (when-not (s/closed? stream)
    (s/close! stream)))

(defn- send-or-close!
  [ws client-stream packet write-timeout-ms]
  (d/chain
   (send-packet! ws packet write-timeout-ms)
   (fn [delivered?]
     (when-not delivered?
       (close-stream! client-stream))
     delivered?)))

(defn bridge-client!
  "Attach one WebSocket client to the telemetry fan-out stream.

   Each client gets a bounded branch stream. WebSocket writes use try-put! so a
   slow or stalled client is disconnected without blocking the shared telemetry
   fan-out indefinitely."
  ([telemetry-stream ws]
   (bridge-client! telemetry-stream ws {}))
  ([telemetry-stream ws {:keys [client-buffer-size write-timeout-ms]
                         :or {client-buffer-size default-client-buffer-size
                              write-timeout-ms default-write-timeout-ms}}]
   (let [client-stream (s/stream client-buffer-size)
         _ (s/connect telemetry-stream
                      client-stream
                      {:description "fan-out -> websocket-client"
                       :upstream? false
                       :downstream? true
                       :timeout write-timeout-ms})
         pump (s/consume-async
               #(send-or-close! ws client-stream % write-timeout-ms)
               client-stream)]
     ;; Drain any client messages so inbound frames do not accumulate. The app
     ;; does not currently accept WebSocket commands.
     (s/consume (fn [_] nil) ws)
     (s/on-closed ws #(close-stream! client-stream))
     (d/on-realized pump
                    (fn [_]
                      (when-not (s/closed? ws)
                        (s/close! ws)))
                    (fn [error]
                      (println "WebSocket telemetry pump failed:" (error-message error))
                      (close-stream! client-stream)
                      (when-not (s/closed? ws)
                        (s/close! ws))))
     ws)))

(defn websocket-handler
  "Create a WebSocket handler that streams telemetry data."
  ([telemetry-stream]
   (websocket-handler telemetry-stream {}))
  ([telemetry-stream opts]
   (fn [req]
     (let [ws-deferred (http/websocket-connection req)
           bridge-deferred (d/chain ws-deferred #(bridge-client! telemetry-stream % opts))]
       (d/catch bridge-deferred
                #(println "WebSocket connection error:" (error-message %)))
       ws-deferred))))
