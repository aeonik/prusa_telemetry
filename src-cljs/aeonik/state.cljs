(ns aeonik.state)

(def ^:private storage-key "prusa-telemetry-state")

(defonce app-state
  (atom
   {:ws                 nil
    :connected          false
    :telemetry-events   []      ; Single vector of all telemetry events (live + loaded), sorted by time
    :available-files    []      ; List of available telemetry files from disk
    :paused             false
    :view-mode          :latest ; :latest | :packets | :timeline
    :selected-time      nil     ; Selected time for scrubbing (in milliseconds)
    :selected-filename  nil     ; Selected print filename
    :timeline-playing   false
    :timeline-interval  nil     ; Interval ID for auto-play
    :slider-dragging    false
    :dropdown-interacting false ; Track when dropdown is being interacted with
    :user-interacting   false}))

;; Helper functions to derive views from :telemetry-events

(defn get-latest-values
  "Derive latest-values map from telemetry-events"
  [events]
  (let [grouped (group-by (fn [e] (str (:sender e) "/" (:name e))) events)
        latest-map (reduce-kv (fn [acc k events]
                                 (let [sorted (sort-by (fn [e] (or (:wall-time-ms e) 0)) events)
                                       latest (last sorted)]
                                   (assoc acc k latest)))
                               {}
                               grouped)]
    latest-map))

(defn get-timeline-data
  "Derive timeline-data map (print_filename -> metrics) from telemetry-events"
  [events]
  (let [events-with-filename (filter #(some? (:print-filename %)) events)
        grouped-by-filename (group-by :print-filename events-with-filename)]
    (reduce-kv (fn [acc filename events]
                 (assoc acc filename (sort-by (fn [e] (or (:wall-time-ms e) 0)) events)))
               {}
               grouped-by-filename)))

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

(defn- get-persistable-state [state]
  "Extract only the small UI preferences that should be persisted.
   Note: telemetry-events are NOT persisted - they're loaded from server when needed."
  (select-keys state [:selected-time
                      :selected-filename
                      :view-mode
                      :paused
                      ;; Note: telemetry-events and available-files are NOT persisted
                      ;; - events are loaded from server files when needed
                      ;; - available-files is fetched fresh on load
                      ]))

(defn save-state-to-storage! []
  "Save small UI preferences to localStorage (synchronous, fast for small data)"
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

(defn load-state-from-storage! []
  "Load small UI preferences from localStorage and merge into current state.
   Note: telemetry-events are NOT loaded - they must be loaded from server files."
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

(defn- schedule-save! []
  "Schedule a save operation with debouncing"
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
        ;; Remove non-serializable values
        clean-state (dissoc state :ws :timeline-interval)
        json-str (js/JSON.stringify (clj->js clean-state) nil 2)
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
        ;; Remove non-serializable values
        clean-state (dissoc state :ws :timeline-interval)
        ;; Pretty print the EDN
        edn-str (pretty-print-value clean-state 0)
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
  (let [state @app-state
        clean-state (dissoc state :ws :timeline-interval)]
    (js/console.log "App State:" clean-state)
    (println "State printed to console")))

(defn clear-stored-state! []
  "Clear the persisted UI preferences from localStorage"
  (try
    (.removeItem js/localStorage storage-key)
    (println "Stored UI preferences cleared from localStorage")
    true
    (catch :default e
      (println "Error clearing stored state:" e)
      false)))
