(ns aeonik.events
  (:require [aeonik.state :refer [app-state] :as state]
            [aeonik.timeline :as timeline]
            [aeonik.util :as u]
            [clojure.string :as str]))

;; ============================================================================
;; WebSocket message handlers
;; ============================================================================

(defn- create-events
  "Create event records from metrics, one per metric.
   Returns a vector, never lazy sequences.
   Device-time-us comes from the metrics themselves (calculated from prelude tm + tick).
   Packet metadata (msg, received-at) is included for timeline navigation."
  [sender metrics wall-time-str print-filename packet-msg received-at]
  (let [wall-time-ms (or (u/parse-wall-time-str wall-time-str)
                         (when wall-time-str
                           (println "Warning: Failed to parse wall-time-str:" wall-time-str)
                           nil))]
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
                 :packet-msg      packet-msg
                 :received-at     received-at
                 :print-filename  print-filename})
              metrics))))

(defn- time-range
  "Calculate time range from events using device-time-us (microseconds)"
  [events]
  (when (seq events)
    (let [events-with-time (filter #(some? (:device-time-us %)) events)]
      (when (seq events-with-time)
        (let [times (map :device-time-us events-with-time)
              min-t (apply min times)
              max-t (apply max times)]
          {:min min-t :max max-t})))))

(defn- strip-quotes
  "Remove leading/trailing quotes from a string"
  [s]
  (when s
    (-> (str s)
        (str/replace #"^[\"']+" "")
        (str/replace #"[\"']+$" "")
        str/trim)))

(defn- normalize-filename-for-matching
  "Try to match filename to one in timeline-filenames, handling format differences"
  [filename timeline-filenames]
  (let [normalize (fn [f]
                   (-> f
                       strip-quotes
                       (str/replace #"^_" "")
                       (str/replace #"\.edn$" "")))
        normalized-input (normalize filename)
        find-match (fn [timeline-fname]
                    (= normalized-input (normalize timeline-fname)))]
    (or (some #(when (= filename %) %) timeline-filenames)
        (some #(when (find-match %) %) timeline-filenames)
        filename)))

(defn- ensure-timeline-selection
  [state print-filename]
  (let [events (:telemetry-events state)
        timeline-data (state/get-timeline-data events)
        filenames (keys timeline-data)]
    (if (empty? filenames)
      state
      (let [selected-filename-from-state (:selected-filename state)
            ;; Normalize the selected filename to match timeline-data keys
            current-filename (or (when selected-filename-from-state
                                  (normalize-filename-for-matching selected-filename-from-state filenames))
                                (when print-filename
                                  (normalize-filename-for-matching print-filename filenames))
                                (first filenames))
            packets (get timeline-data current-filename [])]
        (when (not= selected-filename-from-state current-filename)
          (println "Filename normalized from" selected-filename-from-state "to" current-filename))
        (if (seq packets)
          (let [packet-msg-numbers (map :packet-msg packets)
                min-msg (apply min packet-msg-numbers)
                max-msg (apply max packet-msg-numbers)
                current-packet-msg (:selected-packet-msg state)
                ;; Set packet-msg if nil, or if it's outside the valid range
                should-set-packet-msg (or (nil? current-packet-msg)
                                         (< current-packet-msg min-msg)
                                         (> current-packet-msg max-msg))]
            (cond-> state
              ;; Always set filename to the normalized version that matches timeline-data
              (or (nil? (:selected-filename state))
                  (not= (:selected-filename state) current-filename))
              (assoc :selected-filename current-filename)
              
              ;; Set packet-msg to max when data exists and packet-msg is nil or out of range
              ;; This ensures the timeline shows data after loading
              should-set-packet-msg
              (assoc :selected-packet-msg max-msg)))
          state)))))

(defn- extract-print-filename-from-metrics
  "Extract print_filename from a list of metrics, stripping quotes and normalizing"
  [metrics]
  (let [print-filename-metric (first (filter #(= (:name %) "print_filename") metrics))
        raw-value (when print-filename-metric
                   (or (:value print-filename-metric)
                       (when-let [fields (:fields print-filename-metric)]
                         (if (map? fields)
                           (or (get fields "value")
                               (first (vals fields)))
                           nil))))
        ;; Strip quotes and normalize
        cleaned (when raw-value
                 (-> (str raw-value)
                     (str/replace #"^[\"']" "")
                     (str/replace #"[\"']$" "")
                     str/trim))]
    cleaned))

(defn- get-event-time
  "Get device-time-us from event, defaulting to 0"
  [e]
  (or (:device-time-us e) 0))

(defn- merge-sorted-events
  "Efficiently merge two sorted event vectors into one sorted vector.
   Both vectors should already be sorted by device-time-us.
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
  "Convert telemetry packets to event records, sorted by device-time-us.
   Returns a vector, never lazy sequences.
   Packets from files are already sorted, but we still need to sort events within packets."
  [packets]
  (let [events-vec (reduce (fn [acc packet]
                              (let [sender (:sender packet)
                                    metrics (:metrics packet)
                                    wall-time-str (:wall-time-str packet)
                                    prelude (:prelude packet)
                                    packet-msg (:msg prelude)
                                    received-at (:received-at packet)
                                    print-filename (extract-print-filename-from-metrics metrics)
                                    new-events (create-events sender metrics wall-time-str print-filename packet-msg received-at)]
                                ;; Log if we have events without device-time-us
                                (when (and (seq new-events) (nil? (:device-time-us (first new-events))))
                                  (println "Warning: Packet has no device-time-us. wall-time-str:" wall-time-str
                                           "sender:" sender "metrics count:" (count metrics)))
                                ;; Use reduce with conj! for transient vectors, not into
                                (reduce conj! acc new-events)))
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
      {:telemetry-events [] :available-files [] :view-mode :latest})
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
      ;; All batches processed - ensure timeline selection is set
      (js/setTimeout
       (fn []
         (swap! app-state #(ensure-timeline-selection % nil))
         (reset! batch-processing-state nil))
       100))
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
      {:telemetry-events [] :available-files [] :view-mode :latest})
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
  [state {:keys [sender metrics wall-time-str print-filename prelude received-at]}]
  (if (not (map? state))
    (do
      (println "Error: handle-ws-message received non-map state:" (type state) state)
      {:telemetry-events [] :available-files [] :view-mode :latest})
    (let [packet-msg (:msg prelude)
          received-at-ms (when received-at (if (number? received-at) received-at (.getTime received-at)))
          new-events-unsorted (create-events sender metrics wall-time-str print-filename packet-msg received-at-ms)
          ;; Sort new events before merging (they're from a single packet, so should be small)
          new-events (vec (sort-by get-event-time new-events-unsorted))
          updated-events (merge-sorted-events (:telemetry-events state) new-events)
          updated-state (assoc state :telemetry-events updated-events)]
      (when (and (seq new-events) (nil? (:device-time-us (first new-events))))
        (println "Warning: Events created without device-time-us. wall-time-str:" wall-time-str))
      ;; Only call ensure-timeline-selection if selection is missing or if we have a new filename
      (if (or (nil? (:selected-filename updated-state))
              (nil? (:selected-packet-msg updated-state))
              (and print-filename (not= print-filename (:selected-filename updated-state))))
        (ensure-timeline-selection updated-state print-filename)
        updated-state))))

;; ============================================================================
;; Timeline event handlers
;; ============================================================================

(defn- clamp-forward
  [current {:keys [max]} step]
  (when current
    (min max (+ current step))))

(defn- clamp-backward
  [current {:keys [min]} step]
  (when current
    (max min (- current step))))

(defn- handle-timeline-tick
  "Handle timeline tick - step is number of packets"
  [state {:keys [step packet-range]}]
  (let [current (:selected-packet-msg state)
        max-msg (:max packet-range)]
    (if (and current packet-range (< current max-msg))
      (assoc state :selected-packet-msg (min max-msg (+ current (or step 1))))
      (assoc state :timeline-playing false))))

(defn- handle-step-forward
  [state {:keys [packet-range]}]
  (if-let [new-msg (clamp-forward (:selected-packet-msg state) packet-range 1)]
    (assoc state :selected-packet-msg new-msg)
    state))

(defn- handle-step-backward
  [state {:keys [packet-range]}]
  (if-let [new-msg (clamp-backward (:selected-packet-msg state) packet-range 1)]
    (assoc state :selected-packet-msg new-msg)
    state))

(defn- handle-jump-to-start
  [state {:keys [packet-range]}]
  (assoc state :selected-packet-msg (:min packet-range)))

(defn- handle-jump-to-end
  [state {:keys [packet-range]}]
  (assoc state :selected-packet-msg (:max packet-range)))

;; ============================================================================
;; Main event handler
;; ============================================================================

(defn handle-event [state {:keys [type] :as ev}]
  (case type
    :view/set
    (assoc state :view-mode (:mode ev))

    :view/set-cycle
    (let [next (case (:view-mode state)
                 :latest  :packets
                 :packets :timeline
                 :timeline :latest
                 :latest)]
      (assoc state :view-mode next))

    :data/clear
    (assoc state :telemetry-events [])

    :timeline/set-filename
    (assoc state :selected-filename (:filename ev))

    :timeline/set-packet-msg
    (assoc state :selected-packet-msg (:packet-msg ev))

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
