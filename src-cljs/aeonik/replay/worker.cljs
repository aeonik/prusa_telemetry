(ns aeonik.replay.worker
  (:require [aeonik.replay.index :as replay-index]
            [cljs.reader :as reader]
            [clojure.string :as str]))

(def ^:private default-window-size 120)
(def ^:private progress-interval-ms 100)

(defonce worker-state
  (atom {:token nil
         :index nil
         :pending-snapshot nil
         :snapshot-scheduled? false}))

(declare flush-snapshot!)

(defn- post!
  [message]
  (.postMessage js/self (clj->js message)))

(defn- response-content-length
  [response]
  (when-let [header (.get (.-headers response) "content-length")]
    (let [value (js/parseInt header 10)]
      (when-not (js/isNaN value)
        value))))

(defn- error-message
  [error fallback]
  (or (some-> error .-message)
      fallback))

(defn- process-line
  [build line]
  (let [line (str/trim line)]
    (if (seq line)
      (replay-index/add-packet build (reader/read-string line))
      build)))

(defn- post-progress!
  [token build bytes-loaded bytes-total]
  (post! (cond-> {:type "progress"
                  :token token
                  :processed (:packet-count build)}
           bytes-loaded
           (assoc :bytes-loaded bytes-loaded)

           bytes-total
           (assoc :bytes-total bytes-total))))

(defn- post-snapshot!
  [token index packet-msg window-size request-id]
  (post! (cond-> {:type "snapshot"
                  :token token
                  :snapshot (replay-index/snapshot-at index packet-msg window-size)}
           request-id
           (assoc :request-id request-id))))

(defn- schedule-snapshot!
  []
  (when-not (:snapshot-scheduled? @worker-state)
    (swap! worker-state assoc :snapshot-scheduled? true)
    (js/setTimeout flush-snapshot! 0)))

(defn- flush-snapshot!
  []
  (let [{:keys [token index pending-snapshot]} @worker-state]
    (swap! worker-state assoc
           :pending-snapshot nil
           :snapshot-scheduled? false)
    (when-let [{request-token :token
                :keys [packet-msg window-size request-id]} pending-snapshot]
      (when (and (= token request-token)
                 index
                 (number? packet-msg))
        (post-snapshot! token index packet-msg window-size request-id)))))

(defn- complete-load!
  [token build bytes-loaded bytes-total]
  (let [index (replay-index/finalize build)
        start-msg (get-in index [:packet-range :min])
        snapshot (when start-msg
                   (replay-index/snapshot-at index start-msg default-window-size))]
    (reset! worker-state {:token token
                          :index index
                          :pending-snapshot nil
                          :snapshot-scheduled? false})
    (post! (cond-> {:type "complete"
                    :token token
                    :data (replay-index/summarize index)
                    :snapshot snapshot}
             bytes-loaded
             (assoc :bytes-loaded bytes-loaded)

             bytes-total
             (assoc :bytes-total bytes-total)))))

(defn- fail!
  [token error]
  (post! {:type "error"
          :token token
          :message (error-message error "Error loading telemetry replay file")}))

(defn- load-replay!
  [{:keys [token archive url expected-bytes]}]
  (let [decoder (js/TextDecoder. "utf-8")
        line-buffer (atom "")
        build (atom (replay-index/empty-build archive nil))
        bytes-loaded (atom 0)
        bytes-total (atom expected-bytes)
        last-progress-at (atom 0)]
    (letfn [(maybe-progress! []
              (let [now (.now js/Date)]
                (when (> (- now @last-progress-at) progress-interval-ms)
                  (reset! last-progress-at now)
                  (post-progress! token @build @bytes-loaded @bytes-total))))
            (drain-lines! []
              (loop []
                (let [text @line-buffer
                      idx (.indexOf text "\n")]
                  (when (>= idx 0)
                    (let [line (subs text 0 idx)]
                      (reset! line-buffer (subs text (inc idx)))
                      (swap! build process-line line)
                      (maybe-progress!)
                      (recur))))))
            (append-text! [text]
              (when (seq text)
                (swap! line-buffer str text)
                (drain-lines!)))
            (finish! []
              (let [tail (.decode decoder)]
                (when (seq tail)
                  (append-text! tail)))
              (let [tail (str/trim @line-buffer)]
                (reset! line-buffer "")
                (when (seq tail)
                  (swap! build process-line tail)))
              (post-progress! token @build @bytes-loaded @bytes-total)
              (complete-load! token @build @bytes-loaded @bytes-total))
            (read-loop! [stream-reader]
              (-> (.read stream-reader)
                  (.then (fn [result]
                           (if (.-done result)
                             (finish!)
                             (let [value (.-value result)
                                   text (.decode decoder value #js {:stream true})]
                               (swap! bytes-loaded + (.-byteLength value))
                               (append-text! text)
                               (read-loop! stream-reader)))))
                  (.catch #(fail! token %))))]
      (-> (js/fetch url)
          (.then (fn [response]
                   (if (.-ok response)
                     (let [content-length (response-content-length response)
                           body (.-body response)]
                       (when content-length
                         (reset! bytes-total content-length))
                       (if body
                         (read-loop! (.getReader body))
                         (fail! token (js/Error. "This browser does not support streaming replay loads"))))
                     (-> (.text response)
                         (.then (fn [text]
                                  (fail! token (js/Error. (str "HTTP " (.-status response) ": " text)))))))))
          (.catch #(fail! token %))))))

(defn- handle-snapshot!
  [{:keys [token packet-msg window-size request-id]}]
  (let [{active-token :token index :index} @worker-state]
    (when (and (= token active-token)
               index
               (number? packet-msg))
      (swap! worker-state assoc
             :pending-snapshot {:token token
                                :packet-msg packet-msg
                                :window-size (or window-size default-window-size)
                                :request-id request-id})
      (schedule-snapshot!))))

(defn- handle-message!
  [message]
  (case (:type message)
    "load"
    (load-replay! message)

    "snapshot"
    (handle-snapshot! message)

    "dispose"
    (reset! worker-state {:token nil
                          :index nil
                          :pending-snapshot nil
                          :snapshot-scheduled? false})

    nil))

(defn init
  "Start the replay worker message loop."
  []
  (set! (.-onmessage js/self)
        (fn [event]
          (handle-message! (js->clj (.-data event) :keywordize-keys true)))))
