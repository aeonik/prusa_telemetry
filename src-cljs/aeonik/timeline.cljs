(ns aeonik.timeline
  "Timeline playback loop management.
   
   This namespace manages the JavaScript interval that drives timeline playback.
   It watches app-state and automatically starts/stops the playback loop when needed.
   
   Local state (separate from app-state):
   - timeline-loop: JavaScript interval ID (needed to clear the interval)
   - dispatch-callback: Function to dispatch events (set by events namespace)
   
   Note: time-range is computed in the view and passed to start-loop! when needed."
  (:require [aeonik.state :refer [app-state] :as state]))

(defonce timeline-loop (atom nil))
(defonce dispatch-callback (atom nil))

(defn set-dispatch-callback!
  "Set the callback function to use for dispatching events.
   Called once during initialization from events namespace."
  [callback]
  (reset! dispatch-callback callback))

(defn stop-loop!
  "Stop the playback loop and clear the interval."
  []
  (when-let [id @timeline-loop]
    (js/clearInterval id)
    (reset! timeline-loop nil)))

(defn start-loop!
  "Start the playback loop with given step size and packet range.
   The loop dispatches :timeline/tick events every 100ms while playing.
   step is number of packets to advance."
  [step packet-range]
  (stop-loop!) ; Make sure to stop any existing loop
  (reset! timeline-loop
          (js/setInterval
           (fn []
             (let [state @app-state]
               (when (and (:timeline-playing state)
                          packet-range
                          @dispatch-callback)
                 (@dispatch-callback {:type :timeline/tick
                                     :step step
                                     :packet-range packet-range}))))
           100)))

(defn- compute-packet-range
  "Compute packet range for given filename from timeline data.
   Returns {:min <msg> :max <msg>} packet msg numbers or nil if no data."
  [timeline-data filename]
  (let [packets (get timeline-data filename [])]
    (when (seq packets)
      (let [msg-numbers (map :packet-msg packets)]
        {:min (apply min msg-numbers)
         :max (apply max msg-numbers)}))))

(defn update-loop!
  "Check if we need to start/stop the loop based on current app-state.
   Only recomputes packet-range when filename or events change."
  []
  (let [app-state-val @app-state
        playing (:timeline-playing app-state-val)
        current-filename (:selected-filename app-state-val)]
    (if (and playing current-filename)
      ;; Try to start loop if playing
      (let [timeline-data (state/get-timeline-data nil) ; Uses cached value
            packet-range (compute-packet-range timeline-data current-filename)]
        (if packet-range
          (start-loop! 1 packet-range) ; Step by 1 packet
          (stop-loop!)))
      ;; Stop loop if not playing or no filename
      (stop-loop!))))

;; Watch app-state and update loop when relevant state changes
(add-watch app-state :timeline-loop
           (fn [_ _ old new]
             (when (or (not= (:timeline-playing old) (:timeline-playing new))
                      (not= (:selected-filename old) (:selected-filename new))
                      (not= (:telemetry-events old) (:telemetry-events new)))
               (update-loop!))))
