(ns aeonik.events
  (:require [aeonik.state :refer [app-state]]
            [aeonik.timeline :as timeline]
            [aeonik.util :as u]))

;; ============================================================================
;; WebSocket message handlers
;; ============================================================================

(defn- update-latest-values
  [state sender metrics wall-time-str]
  (let [old (:latest-values state)
        new (reduce (fn [acc metric]
                      (let [metric-name (:name metric)
                            k          (str sender "/" metric-name)]
                        (assoc acc k {:sender          sender
                                      :name            metric-name
                                      :value           (:value metric)
                                      :fields          (:fields metric)
                                      :error           (:error metric)
                                      :type            (:type metric)
                                      :tick            (:tick metric)
                                      :device-time-us  (:device-time-us metric)
                                      :device-time-str (:device-time-str metric)
                                      :wall-time-str   wall-time-str})))
                    old
                    metrics)]
    (assoc state :latest-values new)))

(defn- append-timeline-metrics
  [metrics sender wall-time-str]
  (let [wall-time-ms (u/parse-wall-time-str wall-time-str)]
    (map (fn [m]
           {:sender          sender
            :name            (:name m)
            :value           (:value m)
            :fields          (:fields m)
            :error           (:error m)
            :type            (:type m)
            :tick            (:tick m)
            :device-time-us  (:device-time-us m)
            :device-time-str (:device-time-str m)
            :wall-time-str   wall-time-str
            :wall-time-ms    wall-time-ms})
         metrics)))

(defn- update-timeline-data
  [state sender metrics wall-time-str print-filename]
  (if (nil? print-filename)
    state
    (let [timeline-data   (:timeline-data state)
          existing        (get timeline-data print-filename [])
          new-metrics     (append-timeline-metrics metrics sender wall-time-str)
          updated-list    (concat existing new-metrics)
          timeline-data'  (assoc timeline-data print-filename updated-list)]
      (assoc state :timeline-data timeline-data'))))

(def ^:private packet-history-limit 50)

(defn- update-telemetry-data
  [state sender metrics wall-time-str]
  (if (not= (:view-mode state) :packets)
    state
    (let [packet  {:sender sender
                   :metrics metrics
                   :wall-time-str wall-time-str}
          updated (conj (:telemetry-data state) packet)
          trimmed (if (> (count updated) packet-history-limit)
                    (vec (take-last packet-history-limit updated))
                    updated)]
      (assoc state :telemetry-data trimmed))))

(defn- time-range
  [metrics]
  (when (seq metrics)
    (let [metrics-with-time (filter #(some? (:wall-time-ms %)) metrics)]
      (when (seq metrics-with-time)
        (let [times (map :wall-time-ms metrics-with-time)
              min-t (apply min times)
              max-t (apply max times)]
          {:min min-t :max max-t})))))

(defn- ensure-timeline-selection
  [state print-filename]
  (let [timeline-data (:timeline-data state)]
    (if (or (nil? print-filename)
            (empty? timeline-data))
      state
      (let [filenames        (keys timeline-data)
            current-filename (or (:selected-filename state)
                                 (first filenames))
            all-metrics      (get timeline-data current-filename [])
            {:keys [max] :as tr} (time-range all-metrics)]
        (cond-> state
          (and tr (nil? (:selected-time state)))
          (assoc :selected-time max)

          (and current-filename (nil? (:selected-filename state)))
          (assoc :selected-filename current-filename))))))

(defn- handle-ws-message
  [state {:keys [sender metrics wall-time-str print-filename]}]
  (if (:paused state)
    state
    (-> state
        (update-latest-values sender metrics wall-time-str)
        (update-timeline-data sender metrics wall-time-str print-filename)
        (update-telemetry-data sender metrics wall-time-str)
        (ensure-timeline-selection print-filename))))

;; ============================================================================
;; Timeline event handlers
;; ============================================================================

(def ^:private one-second-ms 1000)

(defn- clamp-forward
  [current {:keys [max]} step]
  (when current
    (min max (+ current step))))

(defn- clamp-backward
  [current {:keys [min]} step]
  (when current
    (max min (- current step))))

(defn- handle-timeline-tick
  [state {:keys [step-ms time-range]}]
  (let [current (:selected-time state)
        max-t   (:max time-range)]
    (if (and current time-range (< current max-t))
      (assoc state :selected-time (min max-t (+ current step-ms)))
      (-> state
          (assoc :timeline-playing false)
          (assoc :timeline-interval nil)))))

(defn- handle-step-forward
  [state {:keys [time-range]}]
  (if-let [new-t (clamp-forward (:selected-time state) time-range one-second-ms)]
    (assoc state :selected-time new-t)
    state))

(defn- handle-step-backward
  [state {:keys [time-range]}]
  (if-let [new-t (clamp-backward (:selected-time state) time-range one-second-ms)]
    (assoc state :selected-time new-t)
    state))

(defn- handle-jump-to-start
  [state {:keys [time-range]}]
  (assoc state :selected-time (:min time-range)))

(defn- handle-jump-to-end
  [state {:keys [time-range]}]
  (assoc state :selected-time (:max time-range)))

;; ============================================================================
;; Main event handler
;; ============================================================================

(defn handle-event [state {:keys [type] :as ev}]
  (case type
    :connection/open
    (assoc state :connected true)

    :connection/close
    (assoc state :connected false)

    :ws/message
    (handle-ws-message state ev)

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
    (assoc state :timeline-playing true)

    :timeline/stop
    (assoc state :timeline-playing false)

    :timeline/tick
    (handle-timeline-tick state ev)

    :timeline/step-forward
    (handle-step-forward state ev)

    :timeline/step-backward
    (handle-step-backward state ev)

    :timeline/jump-to-start
    (handle-jump-to-start state ev)

    :timeline/jump-to-end
    (handle-jump-to-end state ev)

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
