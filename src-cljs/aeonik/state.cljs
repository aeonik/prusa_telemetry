(ns aeonik.state
  (:require [reagent.core :as r]
            [reagent.ratom :as ratom]))

(def ^:private storage-key "prusa-telemetry-state")

(defonce app-state
  (r/atom
   {:telemetry-events   []      ; All telemetry events, sorted by time
    :available-files    []      ; Available telemetry files from disk
    :view-mode          :latest ; :latest | :packets | :timeline
    :selected-packet-msg nil     ; Selected packet msg number for timeline scrubbing
    :selected-filename  nil     ; Selected file identifier (format: "date:filename")
    :timeline-playing   false})) ; Timeline auto-play state

;; Cached timeline data - updated only when :telemetry-events changes
(defonce cached-timeline-data (r/atom {}))

;; Internal implementation - derive timeline-data map (print_filename -> packets) from telemetry-events
;; Packets are grouped by msg number, with events sorted by device-time-us within each packet
(defn- get-timeline-data-impl
  [events]
  (let [events-with-filename (filter #(some? (:print-filename %)) events)
        ;; Filter out events without packet-msg - they can't be used in timeline view
        events-with-packet (filter #(some? (:packet-msg %)) events-with-filename)
        grouped-by-filename (group-by :print-filename events-with-packet)]
    (reduce-kv (fn [acc filename events]
                 ;; Group events by packet-msg, then sort packets by msg number
                 (let [packets-by-msg (group-by :packet-msg events)
                       sorted-packets (->> packets-by-msg
                                           (map (fn [[msg packet-events]]
                                                  {:packet-msg msg
                                                   :received-at (some :received-at packet-events)
                                                   :events (sort-by :device-time-us packet-events)}))
                                           (sort-by :packet-msg))]
                   (assoc acc filename sorted-packets)))
               {}
               grouped-by-filename)))

;; Initialize cache with current events
(reset! cached-timeline-data (get-timeline-data-impl (:telemetry-events @app-state)))

;; Watch :telemetry-events and update cache only when it changes
(add-watch app-state :timeline-data-cache
           (fn [_ _ old-state new-state]
             (let [old-events (:telemetry-events old-state)
                   new-events (:telemetry-events new-state)]
               (when (not= old-events new-events)
                 (reset! cached-timeline-data (get-timeline-data-impl new-events))))))

;; Helper functions to derive views from :telemetry-events

(defn get-latest-values
  "Derive latest-values map from telemetry-events"
  [events]
  (let [grouped (group-by (fn [e] (str (:sender e) "/" (:name e))) events)
        latest-map (reduce-kv (fn [acc k events]
                                (let [sorted (sort-by (fn [e] (or (:device-time-us e) 0)) events)
                                      latest (last sorted)]
                                  (assoc acc k latest)))
                              {}
                              grouped)]
    latest-map))

(defn get-timeline-data
  "Derive timeline-data map (print_filename -> metrics) from telemetry-events.
   If events is provided, computes directly (for backwards compatibility).
   If nil, returns cached value that only updates when :telemetry-events changes."
  [events]
  (if (nil? events)
    @cached-timeline-data
    (get-timeline-data-impl events)))

(defn get-telemetry-packets
  "Derive telemetry-data (packets view) from telemetry-events"
  [events limit]
  (let [grouped-by-packet (partition-by (fn [e] [(:sender e) (:wall-time-str e)]) events)
        packets (map (fn [packet-events]
                       (let [first-event (first packet-events)]
                         {:sender (:sender first-event)
                          :wall-time-str (:wall-time-str first-event)
                          :metrics (map (fn [e]
                                          {:name (:name e)
                                           :value (:value e)
                                           :fields (:fields e)
                                           :error (:error e)
                                           :type (:type e)
                                           :tick (:tick e)
                                           :device-time-us (:device-time-us e)
                                           :device-time-str (:device-time-str e)})
                                        packet-events)}))
                     grouped-by-packet)]
    (vec (take-last limit packets))))

(defn- get-persistable-state
  "Extract only UI preferences that should be persisted"
  [state]
  (select-keys state [:selected-time
                      :selected-filename
                      :view-mode]))

(defn save-state-to-storage!
  "Save small UI preferences to localStorage (synchronous, fast for small data)"
  []
  (try
    (let [state @app-state
          persistable (get-persistable-state state)]
      (.setItem js/localStorage storage-key (js/JSON.stringify (clj->js persistable)))
      true)
    (catch :default e
      (println "Error saving state to localStorage:" e)
      (js/console.error e)
      false)))

;; Set up auto-save on state changes (debounced to avoid excessive writes)
(defonce save-timeout (atom nil))
(defonce loading-state? (atom false))

(defn load-state-from-storage!
  "Load small UI preferences from localStorage and merge into current state.
   Note: telemetry-events are NOT loaded - they must be loaded from server files."
  []
  (try
    (reset! loading-state? true)
    (when-let [stored (.getItem js/localStorage storage-key)]
      (let [parsed (js->clj (js/JSON.parse stored) :keywordize-keys true)
            current @app-state
            ;; Ensure view-mode is a keyword if present
            parsed (if (contains? parsed :view-mode)
                     (update parsed :view-mode #(if (string? %) (keyword %) %))
                     parsed)
            ;; Merge: parsed UI preferences override current defaults
            ;; but preserve non-persisted fields (like telemetry-events) from current
            merged-state (merge current parsed)]
        (reset! app-state merged-state)
        (println (str "UI preferences loaded from localStorage - view-mode: " (:view-mode parsed)))
        (js/setTimeout #(reset! loading-state? false) 100)
        true))
    (catch :default e
      (println "Error loading state from localStorage:" e)
      (js/console.error e)
      (reset! loading-state? false)
      false)))

;; Note: loading-state? must be defined before load-state-from-storage! uses it

(defn- schedule-save!
  "Schedule a save operation with debouncing"
  []
  ;; Don't save if we're currently loading state
  (when-not @loading-state?
    (when-let [timeout @save-timeout]
      (js/clearTimeout timeout))
    (reset! save-timeout
            (js/setTimeout
             (fn []
               (save-state-to-storage!)
               (reset! save-timeout nil))
             1000)))) ; Save 1 second after last change

;; Watch the app-state atom and auto-save on changes
(add-watch app-state :persist
           (fn [_ _ _ _]
             (schedule-save!)))

(defn dump-state-to-json
  "Export current app-state to JSON and trigger download"
  []
  (let [state @app-state
        json-str (js/JSON.stringify (clj->js state) nil 2)
        blob (js/Blob. #js [json-str] #js {:type "application/json"})
        url (.createObjectURL js/URL blob)
        link (.createElement js/document "a")]
    (set! (.-href link) url)
    (set! (.-download link) (str "prusa-telemetry-state-" (.toISOString (js/Date.)) ".json"))
    (.appendChild (.-body js/document) link)
    (.click link)
    (.removeChild (.-body js/document) link)
    (.revokeObjectURL js/URL url)
    (println "State exported to JSON")))

(defn- indent [level]
  (apply str (repeat (* level 2) " ")))

(defn- pretty-print-value [value level]
  (cond
    (map? value)
    (if (empty? value)
      "{}"
      (let [entries (seq value)
            entries-str (map (fn [[k v]]
                               (str (indent (inc level))
                                    (pretty-print-value k (inc level))
                                    " "
                                    (pretty-print-value v (inc level))))
                             entries)]
        (str "{\n"
             (apply str (interpose ",\n" entries-str))
             "\n" (indent level) "}")))

    (vector? value)
    (if (empty? value)
      "[]"
      (let [items-str (map (fn [item]
                             (str (indent (inc level))
                                  (pretty-print-value item (inc level))))
                           value)]
        (str "[\n"
             (apply str (interpose ",\n" items-str))
             "\n" (indent level) "]")))

    (seq? value)
    (if (empty? value)
      "()"
      (let [items-str (map (fn [item]
                             (str (indent (inc level))
                                  (pretty-print-value item (inc level))))
                           value)]
        (str "(\n"
             (apply str (interpose ",\n" items-str))
             "\n" (indent level) ")")))

    (string? value)
    (pr-str value)

    (nil? value)
    "nil"

    :else
    (pr-str value)))

(defn dump-state-to-edn
  "Export current app-state to EDN (pretty-printed) and trigger download"
  []
  (let [state @app-state
        edn-str (pretty-print-value state 0)
        blob (js/Blob. #js [edn-str] #js {:type "text/plain"})
        url (.createObjectURL js/URL blob)
        link (.createElement js/document "a")]
    (set! (.-href link) url)
    (set! (.-download link) (str "prusa-telemetry-state-" (.toISOString (js/Date.)) ".edn"))
    (.appendChild (.-body js/document) link)
    (.click link)
    (.removeChild (.-body js/document) link)
    (.revokeObjectURL js/URL url)
    (println "State exported to EDN (pretty-printed)")))

(defn dump-state-to-console
  "Print current app-state to console (for debugging)"
  []
  (js/console.log "App State:" @app-state)
  (println "State printed to console"))

(defn clear-stored-state!
  "Clear the persisted UI preferences from localStorage"
  []
  (try
    (.removeItem js/localStorage storage-key)
    (println "Stored UI preferences cleared from localStorage")
    true
    (catch :default e
      (println "Error clearing stored state:" e)
      false)))

(defn pick
  "Select one item from coll.
   selector can be:
   - nil                => first
   - keyword            => first item where (truthy? (get item selector))
   - map                => first item where all kv pairs match (=)
   - fn (predicate)     => first item where (selector item) is truthy
   - number             => nth (safe-ish; nil if out of range)"
  ([coll] (first coll))
  ([coll selector]
   (cond
     (nil? selector)
     (first coll)

     (number? selector)
     (nth coll selector nil)

     (keyword? selector)
     (some #(when (get % selector) %) coll)

     (map? selector)
     (some (fn [x]
             (when (every? (fn [[k v]] (= (get x k) v)) selector)
               x))
           coll)

     (fn? selector)
     (some #(when (selector %) %) coll)

     :else
     (first coll))))

(defn state-summary
  "Return a small, printable view of app-state.
   opts can include selectors for vector fields:
   {:telemetry-event <selector>
    :available-file  <selector>}"
  ([] (state-summary {}))
  ([{:keys [telemetry-event available-file]
     :or   {telemetry-event nil
            available-file  nil}}]
   (let [s @app-state]
     {:telemetry-event      (pick (:telemetry-events s) telemetry-event)
      :available-file       (pick (:available-files s) available-file)
      :view-mode            (:view-mode s)
      :selected-packet-msg  (:selected-packet-msg s)
      :selected-filename    (:selected-filename s)
      :timeline-playing     (:timeline-playing s)})))

(comment
  (state-summary)

  (state-summary
   {:telemetry-event #(= 1007147 (:packet-msg %))}))
