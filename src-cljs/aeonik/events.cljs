(ns aeonik.events
  (:require [aeonik.state :refer [app-state]]
            [aeonik.timeline :as timeline]))

(defn handle-event [state {:keys [type] :as ev}]
  (case type
    :connection/open
    (assoc state :connected true)

    :connection/close
    (assoc state :connected false)

    :ws/message
    (let [{:keys [sender metrics wall-time-str print-filename]} ev
          paused (:paused state)]
      (if paused
        state
        (let [;; Update latest values map
              latest-values-updated
              (reduce (fn [acc metric]
                        (let [metric-name (:name metric)
                              key (str sender "/" metric-name)]
                          (assoc acc key {:sender sender
                                          :name metric-name
                                          :value (:value metric)
                                          :fields (:fields metric)
                                          :error (:error metric)
                                          :type (:type metric)
                                          :tick (:tick metric)
                                          :device-time-us (:device-time-us metric)
                                          :device-time-str (:device-time-str metric)
                                          :wall-time-str wall-time-str})))
                      (:latest-values state)
                      metrics)
              ;; Update timeline data if we have a filename
              timeline-data-updated
              (if print-filename
                (let [metrics-list (get (:timeline-data state) print-filename [])
                      new-metrics (map (fn [m]
                                         {:sender sender
                                          :name (:name m)
                                          :value (:value m)
                                          :fields (:fields m)
                                          :error (:error m)
                                          :type (:type m)
                                          :tick (:tick m)
                                          :device-time-us (:device-time-us m)
                                          :device-time-str (:device-time-str m)
                                          :wall-time-str wall-time-str})
                                       metrics)
                      updated-list (concat metrics-list new-metrics)]
                  (assoc (:timeline-data state) print-filename updated-list))
                (:timeline-data state))
              ;; Update telemetry data for packet view
              telemetry-data-updated
              (if (= (:view-mode state) :packets)
                (let [updated (conj (:telemetry-data state) {:sender sender
                                                             :metrics metrics
                                                             :wall-time-str wall-time-str})]
                  (if (> (count updated) 50)
                    (vec (take-last 50 updated))
                    updated))
                (:telemetry-data state))]
          (-> state
              (assoc :latest-values latest-values-updated)
              (assoc :timeline-data timeline-data-updated)
              (assoc :telemetry-data telemetry-data-updated)))))

    :view/set
    (assoc state :view-mode (:mode ev))

    :view/set-cycle
    (let [next (case (:view-mode state)
                 :latest  :packets
                 :packets :timeline
                 :timeline :latest
                 :latest)]
      (assoc state :view-mode next))

    :pause/toggle
    (update state :paused not)

    :data/clear
    (-> state
        (assoc :latest-values {})
        (assoc :telemetry-data []))

    :timeline/set-filename
    (-> state
        (assoc :selected-filename (:filename ev))
        (assoc :selected-time nil))

    :timeline/set-time
    (assoc state :selected-time (:time ev))

    :timeline/play
    (let [updated (assoc state :timeline-playing true)]
      (timeline/update-loop!)
      updated)

    :timeline/stop
    (let [updated (assoc state :timeline-playing false)]
      (timeline/stop-loop!)
      updated)

    :timeline/tick
    (let [step-us (:step-us ev)
          current-time (:selected-time state)
          time-range (:time-range ev)
          max-time (:max time-range)]
      (if (and current-time time-range (< current-time max-time))
        (let [new-time (min max-time (+ current-time step-us))]
          (assoc state :selected-time new-time))
        (-> state
            (assoc :timeline-playing false)
            (assoc :timeline-interval nil))))

    :timeline/step-forward
    (let [time-range (:time-range ev)
          current (:selected-time state)
          max-time (:max time-range)
          step (* 1000000 1)] ; 1 second
      (if (and current (< current max-time))
        (assoc state :selected-time (min max-time (+ current step)))
        state))

    :timeline/step-backward
    (let [time-range (:time-range ev)
          current (:selected-time state)
          min-time (:min time-range)
          step (* 1000000 1)] ; 1 second
      (if (and current (> current min-time))
        (assoc state :selected-time (max min-time (- current step)))
        state))

    :timeline/jump-to-start
    (let [time-range (:time-range ev)]
      (assoc state :selected-time (:min time-range)))

    :timeline/jump-to-end
    (let [time-range (:time-range ev)]
      (assoc state :selected-time (:max time-range)))

    :slider/drag-start
    (assoc state :slider-dragging true)

    :slider/drag-end
    (assoc state :slider-dragging false)

    :user/interacting
    (assoc state :user-interacting (:interacting ev))

    state))

(defn dispatch! [ev]
  (swap! app-state handle-event ev))

;; Set up timeline to use dispatch! as its callback
(defn init-timeline! []
  (timeline/set-dispatch-callback! dispatch!))
