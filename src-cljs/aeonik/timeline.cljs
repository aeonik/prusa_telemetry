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
(def ^:private replay-target-ticks 1200)

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

(defn- active-packet-range
  "Return the packet range for the active timeline surface."
  [app-state-val]
  (or (when (= :replay (:view-mode app-state-val))
        (get-in app-state-val [:replay :data :packet-range]))
      (when-let [current-filename (:selected-filename app-state-val)]
        (let [timeline-data (state/get-timeline-data nil)]
          (compute-packet-range timeline-data current-filename)))))

(defn- packet-range-span
  [{:keys [min max]}]
  (if (and (number? min) (number? max))
    (max 0 (- max min))
    0))

(defn- playback-step
  "Return the packet-msg increment per playback tick."
  [app-state-val packet-range]
  (if (= :replay (:view-mode app-state-val))
    (int (max 1 (js/Math.ceil (/ (packet-range-span packet-range)
                                  replay-target-ticks))))
    1))

(defn update-loop!
  "Check if we need to start/stop the loop based on current app-state."
  []
  (let [app-state-val @app-state
        playing (:timeline-playing app-state-val)
        packet-range (active-packet-range app-state-val)]
    (if (and playing packet-range)
      (start-loop! (playback-step app-state-val packet-range) packet-range)
      (stop-loop!))))

;; Watch app-state and update loop when relevant state changes
(add-watch app-state :timeline-loop
           (fn [_ _ old new]
             (when (or (not= (:timeline-playing old) (:timeline-playing new))
                       (not= (:selected-filename old) (:selected-filename new))
                       (not= (:view-mode old) (:view-mode new))
                       (not= (get-in old [:replay :data :packet-range])
                             (get-in new [:replay :data :packet-range]))
                       (not= (:telemetry-events old) (:telemetry-events new)))
               (update-loop!))))
