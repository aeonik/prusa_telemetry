(ns aeonik.events
  (:require [aeonik.state :refer [app-state] :as state]
            [aeonik.replay.index :as replay-index]
            [aeonik.telemetry-events :as te]
            [aeonik.timeline :as timeline]))

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
                                  (te/normalize-filename-for-matching selected-filename-from-state filenames))
                                (when print-filename
                                  (te/normalize-filename-for-matching print-filename filenames))
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

(def ^:private batch-size 100) ; Process packets in batches to avoid blocking UI
(def ^:private live-event-limit 12000)
(def ^:private replay-batch-size 250)

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
      (let [new-events (te/packets-to-events packets-batch)
            updated-events (te/merge-sorted-events (:telemetry-events state) new-events)]
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

(defn- process-replay-batches! [build batches batch-index token]
  (when (= token (:token @batch-processing-state))
    (if (>= batch-index (count batches))
      (let [data (replay-index/finalize build)
            start-msg (get-in data [:packet-range :min])]
        (swap! app-state
               (fn [state]
                 (-> state
                     (assoc :selected-packet-msg start-msg)
                     (assoc :selected-filename (:print-filename data))
                     (assoc :timeline-playing false)
                     (assoc-in [:replay :loading?] false)
                     (assoc-in [:replay :load-progress] nil)
                     (assoc-in [:replay :data] data)
                     (assoc-in [:replay :error] nil)))))
      (let [updated-build (reduce replay-index/add-packet build (nth batches batch-index))]
        (swap! app-state
               (fn [state]
                 (-> state
                     (assoc-in [:replay :load-progress]
                               {:processed (:packet-count updated-build)
                                :total (:total updated-build)}))))
        (js/requestAnimationFrame
         (fn []
           (js/setTimeout
            (fn []
              (process-replay-batches! updated-build batches (inc batch-index) token))
            0)))))))

(defn- process-replay-packets! [archive packets]
  (let [packets (if (vector? packets) packets (vec packets))
        token (str archive ":" (.now js/Date))
        batches (vec (partition-all replay-batch-size packets))]
    (reset! batch-processing-state {:token token})
    (swap! app-state
           (fn [state]
             (-> state
                 (assoc :telemetry-events [])
                 (assoc :selected-packet-msg nil)
                 (assoc :selected-filename nil)
                 (assoc :timeline-playing false)
                 (assoc-in [:replay :selected-run] archive)
                 (assoc-in [:replay :loading?] true)
                 (assoc-in [:replay :load-progress] {:processed 0 :total (count packets)})
                 (assoc-in [:replay :data] nil)
                 (assoc-in [:replay :error] nil))))
    (js/setTimeout #(process-replay-batches! (replay-index/empty-build archive (count packets))
                                             batches
                                             0
                                             token)
                   0)))

(defn- start-replay-stream-load
  [state {:keys [archive token total bytes-total]}]
  (if (= token (:token @batch-processing-state))
    (do
      (reset! batch-processing-state
              {:token token
               :build (replay-index/empty-build archive total)})
      (-> state
          (assoc :telemetry-events [])
          (assoc :selected-packet-msg nil)
          (assoc :selected-filename nil)
          (assoc :timeline-playing false)
          (assoc-in [:replay :selected-run] archive)
          (assoc-in [:replay :loading?] true)
          (assoc-in [:replay :load-progress] {:processed 0
                                               :total total
                                               :bytes-loaded 0
                                               :bytes-total bytes-total})
          (assoc-in [:replay :data] nil)
          (assoc-in [:replay :error] nil)))
    state))

(defn- replay-stream-token-active?
  [token]
  (= token (:token @batch-processing-state)))

(defn- progress-from-build
  [build {:keys [bytes-loaded bytes-total]}]
  (cond-> {:processed (:packet-count build)
           :total (:total build)}
    bytes-loaded
    (assoc :bytes-loaded bytes-loaded)

    bytes-total
    (assoc :bytes-total bytes-total)))

(defn- append-replay-stream-batch
  [state {:keys [token packets] :as ev}]
  (if (replay-stream-token-active? token)
    (let [build (:build @batch-processing-state)
          updated-build (reduce replay-index/add-packet build packets)]
      (swap! batch-processing-state assoc :build updated-build)
      (assoc-in state [:replay :load-progress] (progress-from-build updated-build ev)))
    state))

(defn- update-replay-stream-progress
  [state {:keys [token] :as ev}]
  (if (replay-stream-token-active? token)
    (let [build (:build @batch-processing-state)]
      (assoc-in state [:replay :load-progress] (progress-from-build build ev)))
    state))

(defn- complete-replay-stream-load
  [state {:keys [token]}]
  (if (replay-stream-token-active? token)
    (let [data (replay-index/finalize (:build @batch-processing-state))
          start-msg (get-in data [:packet-range :min])]
      (reset! batch-processing-state nil)
      (-> state
          (assoc :selected-packet-msg start-msg)
          (assoc :selected-filename (:print-filename data))
          (assoc :timeline-playing false)
          (assoc-in [:replay :loading?] false)
          (assoc-in [:replay :load-progress] nil)
          (assoc-in [:replay :data] data)
          (assoc-in [:replay :error] nil)))
    state))

(defn- handle-ws-message
  [state {:keys [sender metrics wall-time-str print-filename prelude received-at]}]
  (if (not (map? state))
    (do
      (println "Error: handle-ws-message received non-map state:" (type state) state)
      {:telemetry-events [] :available-files [] :view-mode :latest})
    (if (:paused state)
      state
    (let [packet-msg (:msg prelude)
          received-at-ms (when received-at (if (number? received-at) received-at (.getTime received-at)))
          new-events-unsorted (te/create-events sender metrics wall-time-str print-filename packet-msg received-at-ms)
          ;; Sort new events before merging (they're from a single packet, so should be small)
          new-events (vec (sort-by te/event-time new-events-unsorted))
          updated-events (te/trim-events
                          (te/merge-sorted-events (:telemetry-events state) new-events)
                          live-event-limit)
          updated-state (assoc state :telemetry-events updated-events)]
      (when (and (seq new-events) (nil? (:device-time-us (first new-events))))
        (println "Warning: Events created without device-time-us. wall-time-str:" wall-time-str))
      ;; Only call ensure-timeline-selection if selection is missing or if we have a new filename
      (if (or (nil? (:selected-filename updated-state))
              (nil? (:selected-packet-msg updated-state))
              (and print-filename (not= print-filename (:selected-filename updated-state))))
        (ensure-timeline-selection updated-state print-filename)
        updated-state)))))

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
    :connection/open
    (assoc state :connection :connected)

	    :connection/close
	    (assoc state :connection :disconnected)

    :prusalink/status-success
    (let [data (:data ev)
          status-job (:job data)
          cached-job (get-in state [:prusalink :job])
          updated-state (-> state
                            (assoc-in [:prusalink :connection] :connected)
                            (assoc-in [:prusalink :status] data)
                            (assoc-in [:prusalink :last-updated] (:received-at ev))
                            (assoc-in [:prusalink :error] nil))]
      (cond
        (nil? status-job)
        (assoc-in updated-state [:prusalink :job] nil)

        (and cached-job (not= (:id cached-job) (:id status-job)))
        (assoc-in updated-state [:prusalink :job] nil)

        cached-job
        (assoc-in updated-state [:prusalink :job] (merge cached-job status-job))

        :else
        (assoc-in updated-state [:prusalink :job] status-job)))

    :prusalink/job-success
    (-> state
        (assoc-in [:prusalink :connection] :connected)
        (assoc-in [:prusalink :job] (:data ev))
        (assoc-in [:prusalink :last-job-updated] (:received-at ev))
        (assoc-in [:prusalink :error] nil))

    :prusalink/job-clear
    (-> state
        (assoc-in [:prusalink :job] nil)
        (assoc-in [:prusalink :last-job-updated] (:received-at ev)))

    :prusalink/print-state-success
    (-> state
        (assoc-in [:prusalink :print-state] (:data ev))
        (assoc-in [:prusalink :last-print-state-updated] (:received-at ev))
        (assoc-in [:prusalink :error] nil))

    :prusalink/error
    (-> state
        (assoc-in [:prusalink :connection] :error)
        (assoc-in [:prusalink :error] (:message ev))
        (assoc-in [:prusalink :last-updated] (:received-at ev)))

	    :ws/message
	    (handle-ws-message state ev)

    :view/set
    (assoc state :view-mode (:mode ev))

    :view/set-cycle
    (let [next (case (:view-mode state)
                 :latest  :packets
                 :packets :timeline
                 :timeline :latest
                 :dashboard :latest
                 :latest)]
      (assoc state :view-mode next))

    :data/clear
    (assoc state :telemetry-events [])

    :pause/toggle
    (update state :paused not)

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

    :data/load-file-replace
    (if-let [packets (:packets ev)]
      (do
        (process-replay-packets! (:archive ev) packets)
        state)
      (do
        (println "Warning: :data/load-file-replace event missing :packets")
        state))

    :replay/load-start
    (do
      (reset! batch-processing-state {:token (:token ev)})
      (-> state
          (assoc :telemetry-events [])
          (assoc :selected-packet-msg nil)
          (assoc :selected-filename nil)
          (assoc :timeline-playing false)
          (assoc-in [:replay :selected-run] (:archive ev))
          (assoc-in [:replay :loading?] true)
          (assoc-in [:replay :load-progress]
                    (cond-> {:processed 0
                             :total nil
                             :bytes-loaded 0}
                      (:bytes-total ev)
                      (assoc :bytes-total (:bytes-total ev))))
          (assoc-in [:replay :data] nil)
          (assoc-in [:replay :error] nil)))

    :replay/load-error
    (if (or (nil? (:token ev))
            (replay-stream-token-active? (:token ev)))
      (do
        (reset! batch-processing-state nil)
        (-> state
            (assoc-in [:replay :loading?] false)
            (assoc-in [:replay :load-progress] nil)
            (assoc-in [:replay :error] (:message ev))))
      state)

    :replay/stream-start
    (start-replay-stream-load state ev)

    :replay/stream-batch
    (append-replay-stream-batch state ev)

    :replay/stream-progress
    (update-replay-stream-progress state ev)

    :replay/stream-complete
    (complete-replay-stream-load state ev)

    :replay/select-run
    (assoc-in state [:replay :selected-run] (:archive ev))

    :replay/gcode-loading
    (-> state
        (assoc-in [:replay :gcode] nil)
        (assoc-in [:replay :gcode-file-name] (:file-name ev))
        (assoc-in [:replay :gcode-loading?] true)
        (assoc-in [:replay :gcode-error] nil))

    :replay/gcode-loaded
    (-> state
        (assoc-in [:replay :gcode] (:gcode ev))
        (assoc-in [:replay :gcode-file-name] (:file-name ev))
        (assoc-in [:replay :gcode-loading?] false)
        (assoc-in [:replay :gcode-error] nil))

    :replay/gcode-error
    (-> state
        (assoc-in [:replay :gcode-loading?] false)
        (assoc-in [:replay :gcode-error] (:message ev)))

    :replay/clear-gcode
    (-> state
        (assoc-in [:replay :gcode] nil)
        (assoc-in [:replay :gcode-file-name] nil)
        (assoc-in [:replay :gcode-loading?] false)
        (assoc-in [:replay :gcode-error] nil))

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
