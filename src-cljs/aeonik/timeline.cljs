(ns aeonik.timeline
  (:require [aeonik.state :refer [app-state]]))

(defonce timeline-loop (atom nil))
(defonce current-time-range (atom nil))
(defonce dispatch-callback (atom nil))

(defn set-dispatch-callback! [callback]
  "Set the callback function to use for dispatching events"
  (reset! dispatch-callback callback))

(defn stop-loop! []
  (when-let [id @timeline-loop]
    (js/clearInterval id)
    (reset! timeline-loop nil))
  (reset! current-time-range nil))

(defn start-loop! [step-ms time-range]
  (stop-loop!) ; Make sure to stop any existing loop
  (reset! current-time-range time-range)
  (reset! timeline-loop
          (js/setInterval
           (fn []
             (let [state @app-state]
               (when (and (:timeline-playing state)
                          (not (:slider-dragging state))
                          (not (:user-interacting state))
                          @current-time-range
                          @dispatch-callback)
                 (@dispatch-callback {:type :timeline/tick
                                     :step-ms step-ms
                                     :time-range @current-time-range}))))
           100)))

(defn update-loop! []
  "Check if we need to start/stop the loop based on state"
  (let [state @app-state
        playing (:timeline-playing state)
        current-filename (:selected-filename state)
        all-metrics (get (:timeline-data state) current-filename [])
        time-range (if (seq all-metrics)
                    (let [metrics-with-time (filter #(some? (:wall-time-ms %)) all-metrics)]
                      (when (seq metrics-with-time)
                        (let [times (map :wall-time-ms metrics-with-time)
                              min-time (apply min times)
                              max-time (apply max times)]
                          {:min min-time :max max-time})))
                    nil)]
    (if (and playing time-range)
      (start-loop! 100 time-range) ; 100ms per step
      (stop-loop!))))

;; Watch for timeline-playing and timeline-data changes to start/stop loop
(add-watch app-state :timeline-loop
           (fn [_ _ old new]
             (when (or (not= (:timeline-playing old) (:timeline-playing new))
                      (not= (:selected-filename old) (:selected-filename new))
                      (not= (:timeline-data old) (:timeline-data new)))
               (update-loop!))))
