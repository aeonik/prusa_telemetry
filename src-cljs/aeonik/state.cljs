(ns aeonik.state)

(defonce app-state
  (atom
   {:ws                 nil
    :connected          false
    :telemetry-data     []
    :latest-values      {}      ; Map of metric-name -> latest value
    :paused             false
    :view-mode          :latest ; :latest | :packets | :timeline
    :timeline-data      {}      ; Map of print_filename -> sorted list of all metrics with timestamps
    :selected-time      nil     ; Selected time for scrubbing (in microseconds)
    :selected-filename  nil     ; Selected print filename
    :timeline-playing   false
    :timeline-interval  nil     ; Interval ID for auto-play
    :slider-dragging    false
    :user-interacting   false}))

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
