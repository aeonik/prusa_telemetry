(ns aeonik.events
  (:require [aeonik.state :refer [app-state] :as state]
            [aeonik.timeline :as timeline]
            [aeonik.util :as u]))

;; ============================================================================
;; WebSocket message handlers
;; ============================================================================

(defn- create-events
  "Create event records from metrics, one per metric.
   Returns a vector, never lazy sequences."
  [sender metrics wall-time-str print-filename]
  (let [wall-time-ms (u/parse-wall-time-str wall-time-str)]
    (vec (map (fn [m]
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
                 :wall-time-ms    wall-time-ms
                 :print-filename  print-filename})
              metrics))))

(defn- time-range
  [events]
  (when (seq events)
    (let [events-with-time (filter #(some? (:wall-time-ms %)) events)]
      (when (seq events-with-time)
        (let [times (map :wall-time-ms events-with-time)
              min-t (apply min times)
              max-t (apply max times)]
          {:min min-t :max max-t})))))

(defn- ensure-timeline-selection
  [state print-filename]
  (let [events (:telemetry-events state)
        timeline-data (state/get-timeline-data events)
        filenames (keys timeline-data)]
    (if (empty? filenames)
      state
      (let [current-filename (or (:selected-filename state)
                                 (when print-filename print-filename)
                                 (first filenames))
            all-metrics (get timeline-data current-filename [])
            {:keys [max] :as tr} (time-range all-metrics)]
        (cond-> state
          (and tr (nil? (:selected-time state)))
          (assoc :selected-time max)

          (and current-filename (nil? (:selected-filename state)))
          (assoc :selected-filename current-filename))))))

(defn- extract-print-filename-from-metrics
  "Extract print_filename from a list of metrics"
  [metrics]
  (let [print-filename-metric (first (filter #(= (:name %) "print_filename") metrics))]
    (when print-filename-metric
      (or (:value print-filename-metric)
          (when-let [fields (:fields print-filename-metric)]
            (if (map? fields)
              (or (get fields "value")
                  (first (vals fields)))
              nil))))))

(defn- get-event-time
  "Get wall-time-ms from event, defaulting to 0"
  [e]
  (or (:wall-time-ms e) 0))

(defn- merge-sorted-events
  "Efficiently merge two sorted event vectors into one sorted vector.
   Both vectors should already be sorted by wall-time-ms.
   This is O(n+m) instead of O(n*log(n+m)) for sort.
   Returns a vector, never lazy sequences."
  [existing-events new-events]
  (cond
    (empty? new-events) existing-events
    (empty? existing-events) (if (vector? new-events) new-events (vec new-events))
    :else
    (let [existing-vec (if (vector? existing-events) existing-events (vec existing-events))
          new-vec (if (vector? new-events) new-events (vec new-events))
          existing-time (get-event-time (last existing-vec))
          new-time (get-event-time (first new-vec))]
      (if (>= new-time existing-time)
        ;; Fast path: new events are all after existing events
        (into existing-vec new-vec)
        ;; Need to merge: both lists have events, merge them eagerly
        (loop [result (transient [])
               existing-idx 0
               new-idx 0
               existing-len (count existing-vec)
               new-len (count new-vec)]
          (cond
            (>= existing-idx existing-len)
            (persistent! (reduce conj! result (subvec new-vec new-idx)))
            (>= new-idx new-len)
            (persistent! (reduce conj! result (subvec existing-vec existing-idx)))
            :else
            (let [e-time (get-event-time (get existing-vec existing-idx))
                  n-time (get-event-time (get new-vec new-idx))]
              (if (<= e-time n-time)
                (recur (conj! result (get existing-vec existing-idx))
                       (inc existing-idx) new-idx existing-len new-len)
                (recur (conj! result (get new-vec new-idx))
                       existing-idx (inc new-idx) existing-len new-len)))))))))
(defn- packets-to-events
  "Convert telemetry packets to event records, sorted by wall-time-ms.
   Returns a vector, never lazy sequences."
  [packets]
  (let [events-vec (reduce (fn [acc packet]
                              (let [sender (:sender packet)
                                    metrics (:metrics packet)
                                    wall-time-str (:wall-time-str packet)
                                    print-filename (extract-print-filename-from-metrics metrics)
                                    new-events (create-events sender metrics wall-time-str print-filename)]
                                ;; Use into to build vector eagerly, not concat (which is lazy)
                                (into acc new-events)))
                            (transient [])
                            packets)]
    ;; Sort events from this packet batch (already a vector, sort returns vector)
    (vec (sort-by get-event-time (persistent! events-vec)))))

(def ^:private batch-size 100) ; Process packets in batches to avoid blocking UI

;; Atom to track batch processing state
(defonce batch-processing-state (atom nil))

(defn- load-telemetry-packets-batch
  "Load a batch of telemetry packets into state.
   Does NOT call ensure-timeline-selection (deferred to end of batch processing)."
  [state packets-batch]
  (if (not (map? state))
    (do
      (println "Error: load-telemetry-packets-batch received non-map state:" (type state) state)
      {:ws nil :connected false :telemetry-events [] :available-files [] :paused false :view-mode :latest})
    (if (or (nil? packets-batch) (not (sequential? packets-batch)) (empty? packets-batch))
      state
      (let [new-events (packets-to-events packets-batch)
            updated-events (merge-sorted-events (:telemetry-events state) new-events)]
        (assoc state :telemetry-events updated-events)))))

(defn- process-next-batch
  "Process the next batch of packets"
  [batches batch-index]
  (if (>= batch-index (count batches))
    (do
      ;; Only call ensure-timeline-selection once at the end of all batches
      (swap! app-state ensure-timeline-selection nil)
      (reset! batch-processing-state nil)
      (println "Finished processing all packets"))
    (let [batch (nth batches batch-index)
          current-state @app-state
          new-state (load-telemetry-packets-batch current-state batch)]
      (swap! app-state (constantly new-state))
      ;; Schedule next batch
      (js/requestAnimationFrame
       (fn []
         (js/setTimeout
          (fn []
            (process-next-batch batches (inc batch-index)))
          0))))))

(defn- process-packets-in-batches
  "Process packets in batches to avoid blocking the UI"
  [packets]
  (let [packet-count (count packets)
        batches (vec (partition-all batch-size packets))]
    (println (str "Processing " packet-count " packets in " (count batches) " batches of " batch-size))
    (reset! batch-processing-state {:total-batches (count batches) :current-batch 0})
    (process-next-batch batches 0)))

(defn- load-telemetry-packets
  "Load telemetry packets into state, converting them to events and merging with existing events.
   For large files, processes packets in batches to avoid blocking the UI."
  [state packets]
  (if (not (map? state))
    (do
      (println "Error: load-telemetry-packets received non-map state:" (type state) state)
      {:ws nil :connected false :telemetry-events [] :available-files [] :paused false :view-mode :latest})
    (if (or (nil? packets) (not (sequential? packets)))
      (do
        (println "Warning: load-telemetry-packets called with invalid packets:" packets)
        state)
      (let [packet-count (count packets)]
        (if (> packet-count batch-size)
          ;; Large file - process in batches
          (do
            (js/setTimeout
             (fn []
               (process-packets-in-batches packets))
             0)
            ;; Return state immediately, batches will update it incrementally
            state)
          ;; Small file - process immediately
          (-> (load-telemetry-packets-batch state packets)
              (ensure-timeline-selection nil)))))))

(defn- handle-ws-message
  [state {:keys [sender metrics wall-time-str print-filename]}]
  (if (not (map? state))
    (do
      (println "Error: handle-ws-message received non-map state:" (type state) state)
      {:ws nil :connected false :telemetry-events [] :available-files [] :paused false :view-mode :latest})
    (if (:paused state)
      state
      (let [new-events-unsorted (create-events sender metrics wall-time-str print-filename)
            ;; Sort new events before merging (they're from a single packet, so should be small)
            new-events (vec (sort-by get-event-time new-events-unsorted))
            updated-events (merge-sorted-events (:telemetry-events state) new-events)
            updated-state (assoc state :telemetry-events updated-events)]
        (when (and (seq new-events) (nil? (:wall-time-ms (first new-events))))
          (println "Warning: Events created without wall-time-ms. wall-time-str:" wall-time-str))
        ;; Only call ensure-timeline-selection if selection is missing or if we have a new filename
        (if (or (nil? (:selected-filename updated-state))
                (nil? (:selected-time updated-state))
                (and print-filename (not= print-filename (:selected-filename updated-state))))
          (ensure-timeline-selection updated-state print-filename)
          updated-state)))))

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
    (assoc state :telemetry-events [])

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

    :dropdown/interact-start
    (assoc state :dropdown-interacting true)

    :dropdown/interact-end
    (assoc state :dropdown-interacting false)

    :user/interacting
    (assoc state :user-interacting (:interacting ev))

    :data/load-file
    (if-let [packets (:packets ev)]
      (load-telemetry-packets state packets)
      (do
        (println "Warning: :data/load-file event missing :packets")
        state))

    :files/set-available
    (let [files-vec (vec (:files ev))] ; Ensure it's a vector, not a lazy seq
      (assoc state :available-files files-vec))

    :files/fetch-available
    state ; Side effect handled in files namespace

    state))

(defn dispatch! [ev]
  (swap! app-state handle-event ev))

;; Set up timeline to use dispatch! as its callback
(defn init-timeline! []
  (timeline/set-dispatch-callback! dispatch!))
