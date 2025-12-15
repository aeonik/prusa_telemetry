(ns aeonik.render
  (:require [aeonik.state :refer [app-state] :as state]
            [aeonik.views :as views]
            [aeonik.util :as u]))

(def ^:private debug? false) ; Set to true to enable debug logging

(defonce render-state
  (atom {:render-scheduled? false
         :last-render-ms    0
         :throttle-ms       50}))

(defn hiccup->dom
  "Convert Hiccup-style vector to DOM element"
  [hiccup]
  (cond
    (string? hiccup) (.createTextNode js/document hiccup)
    (number? hiccup) (.createTextNode js/document (str hiccup))
    (vector? hiccup)
    (let [[tag & args] hiccup
          _ (when-not (keyword? tag)
              (throw (js/Error. (str "Hiccup tag must be a keyword, got: " tag))))
          [attrs children] (if (and (seq args) (map? (first args)))
                            [(first args) (cljs.core/rest args)]
                            [{} (if (and (= (count args) 1) (seq? (first args)))
                                  (first args)
                                  args)])
          elem (.createElement js/document (name tag))]
      ;; Set attributes
      (doseq [[k v] attrs]
        (cond
          (= k :class) (set! (.-className elem) (if (string? v) v (apply str (interpose " " v))))
          (= k :id) (set! (.-id elem) v)
          (= k :onclick) (set! (.-onclick elem) v)
          (= k :onchange) (set! (.-onchange elem) v)
          (= k :oninput) (set! (.-oninput elem) v)
          (= k :onmousedown) (set! (.-onmousedown elem) v)
          (= k :onmouseup) (set! (.-onmouseup elem) v)
          (= k :onfocus) (set! (.-onfocus elem) v)
          (= k :onblur) (set! (.-onblur elem) v)
          (= k :ontouchstart) (set! (.-ontouchstart elem) v)
          (= k :ontouchend) (set! (.-ontouchend elem) v)
          (= k :value) (set! (.-value elem) v)
          (string? k) (.setAttribute elem k v)
          (keyword? k) (.setAttribute elem (name k) (str v))
          :else (.setAttribute elem (str k) (str v))))
      ;; Add children
      (doseq [child children]
        (when (some? child)
          (cond
            (vector? child) (let [dom-child (hiccup->dom child)]
                             (when dom-child
                               (.appendChild elem dom-child)))
            (seq? child) 
            (doseq [c child]
              (when (some? c)
                (let [dom-c (hiccup->dom c)]
                  (when dom-c
                    (.appendChild elem dom-c)))))
            (string? child) (.appendChild elem (.createTextNode js/document child))
            (number? child) (.appendChild elem (.createTextNode js/document (str child)))
            :else (let [dom-child (hiccup->dom child)]
                    (when dom-child
                      (.appendChild elem dom-child))))))
      elem)
    (seq? hiccup) (let [frag (.createDocumentFragment js/document)]
                    (doseq [item hiccup]
                      (.appendChild frag (hiccup->dom item)))
                    frag)
    :else nil))

(defn replace-children! [elem hiccup]
  (set! (.-innerHTML elem) "")
  (when-let [node (hiccup->dom hiccup)]
    (.appendChild elem node)))

(defn update-table-tbody! [table-selector metrics]
  "Update only the tbody rows, preserving table structure and updating individual cells"
  (let [table (.querySelector js/document table-selector)
        tbody (when table (.querySelector table "tbody"))]
    (when tbody
      (let [existing-rows (array-seq (.-children tbody))
            row-count (count existing-rows)
            metric-count (count metrics)]
        ;; Update or create rows
        (doseq [[idx metric] (map-indexed vector metrics)]
          (let [existing-row (when (< idx row-count)
                              (aget (.-children tbody) idx))
                row-key (str (:sender metric) "/" (:name metric))]
            (if (and existing-row
                     (.-cells existing-row)
                     (>= (.-length (.-cells existing-row)) 2)
                     (= (str (.-textContent (aget (.-cells existing-row) 0)) "/" 
                             (.-textContent (aget (.-cells existing-row) 1))) row-key))
              ;; Update existing row - only update cells that changed
              (let [cells-array (.-cells existing-row)
                    cell-count (.-length cells-array)]
                (when (>= cell-count 5)
                  (let [value-cell (aget cells-array 2)
                        type-cell (aget cells-array 3)
                        time-cell (aget cells-array 4)
                        new-value (u/format-metric-value metric)
                        new-type (:type metric)
                        new-time (or (:device-time-str metric) "--------")]
                    (when (and value-cell (not= (.-textContent value-cell) new-value))
                      (set! (.-textContent value-cell) new-value))
                    (when (and type-cell (not= (.-textContent type-cell) new-type))
                      (set! (.-textContent type-cell) new-type))
                    (when (and time-cell (not= (.-textContent time-cell) new-time))
                      (set! (.-textContent time-cell) new-time)))))
              ;; Create new row
              (let [row (.createElement js/document "tr")
                    create-cell (fn [text]
                                  (let [td (.createElement js/document "td")]
                                    (set! (.-textContent td) (str text))
                                    td))]
                (.appendChild row (create-cell (:sender metric)))
                (.appendChild row (create-cell (:name metric)))
                (.appendChild row (create-cell (u/format-metric-value metric)))
                (.appendChild row (create-cell (:type metric)))
                (.appendChild row (create-cell (or (:device-time-str metric) "--------")))
                (if existing-row
                  (.replaceChild tbody row existing-row)
                  (.appendChild tbody row))))))
        ;; Remove extra rows
        (when (> row-count metric-count)
          (loop [i (dec row-count)]
            (when (>= i metric-count)
              (let [row (aget (.-children tbody) i)]
                (when row
                  (.removeChild tbody row)))
              (recur (dec i)))))))))

(defn update-timeline-display-only! [& [override-time]]
  "Update only timeline display elements without full DOM replacement.
   If override-time is provided, use it instead of reading from state."
  (let [app-state-val @app-state
        timeline-data (state/get-timeline-data (:telemetry-events app-state-val))
        filenames (keys timeline-data)
        current-filename (or (:selected-filename app-state-val) (first filenames))
        all-metrics (get timeline-data current-filename [])
        time-range (if (seq all-metrics)
                    (let [metrics-with-time (filter #(some? (:wall-time-ms %)) all-metrics)]
                      (when (seq metrics-with-time)
                        (let [times (map :wall-time-ms metrics-with-time)
                              min-time (apply min times)
                              max-time (apply max times)]
                          {:min min-time :max max-time})))
                    nil)
        current-time (or override-time (:selected-time app-state-val))
        metrics-at-time (if (and current-filename current-time)
                         (u/get-metrics-at-time timeline-data current-filename current-time)
                         [])
        sorted-metrics (sort-by (fn [m] (str (:sender m) "/" (:name m))) metrics-at-time)]
    ;; Update slider value
    (when-let [slider (.getElementById js/document "time-slider")]
      (set! (.-value slider) (str current-time)))
    ;; Update time display
    (when-let [time-display (.getElementById js/document "time-display")]
      (set! (.-textContent time-display) (u/format-wall-time-ms current-time)))
    ;; Update progress
    (when-let [progress-span (.getElementById js/document "time-progress")]
      (if time-range
        (let [progress (* 100.0 (/ (- current-time (:min time-range))
                                    (- (:max time-range) (:min time-range))))]
          (set! (.-textContent progress-span) (str "(" (.toFixed progress 1) "%)")))
        (set! (.-textContent progress-span) "")))
    ;; Update metrics table tbody only
    (update-table-tbody! "#content table.metrics" sorted-metrics)))

(defn render! []
  (try
    (let [state @app-state
          _ (when debug? (println "render! called, available-files count:" (count (:available-files state))))
          status-el (.getElementById js/document "status")
          content-el (.getElementById js/document "content")]
      ;; Don't do full render if user is dragging slider in timeline view
      (if (and (:slider-dragging state) (= (:view-mode state) :timeline))
        (update-timeline-display-only!)
        (do
          (when status-el
            (replace-children! status-el (views/status-view state)))
          (when content-el
            (let [timeline-data (state/get-timeline-data (:telemetry-events state))
                  filenames (keys timeline-data)
                  current-filename (or (:selected-filename state) (first filenames))
                  all-metrics (get timeline-data current-filename [])
                  time-range (if (seq all-metrics)
                              (let [metrics-with-time (filter #(some? (:wall-time-ms %)) all-metrics)]
                                (when (seq metrics-with-time)
                                  (let [times (map :wall-time-ms metrics-with-time)
                                        min-time (apply min times)
                                        max-time (apply max times)]
                                    {:min min-time :max max-time})))
                              nil)
                  has-table (.querySelector content-el "table.metrics")
                  has-slider (.getElementById js/document "time-slider")]
              (if (and (= (:view-mode state) :timeline)
                       has-table
                       (or has-slider (not time-range)))
                ;; Timeline view with existing table - update tbody and slider only (if slider exists)
                (let [current-time (or (:selected-time state) (when time-range (:max time-range)))
                      metrics-at-time (if (and current-filename current-time)
                                       (u/get-metrics-at-time timeline-data current-filename current-time)
                                       [])
                      sorted-metrics (sort-by (fn [m] (str (:sender m) "/" (:name m))) metrics-at-time)]
                  ;; Update only the table tbody, preserve rest of DOM
                  (update-table-tbody! "#content table.metrics" sorted-metrics)
                  ;; Update slider if it exists and time-range exists
                  (when (and time-range current-time has-slider)
                    (let [slider (.getElementById js/document "time-slider")]
                      (set! (.-value slider) (str current-time))
                      (set! (.-min slider) (str (:min time-range)))
                      (set! (.-max slider) (str (:max time-range)))))
                  ;; Update other timeline elements if needed
                  (when-let [time-display (.getElementById js/document "time-display")]
                    (set! (.-textContent time-display) (u/format-wall-time-ms current-time)))
                  (when-let [progress-span (.getElementById js/document "time-progress")]
                    (if time-range
                      (let [progress (* 100.0 (/ (- current-time (:min time-range))
                                                  (- (:max time-range) (:min time-range))))]
                        (set! (.-textContent progress-span) (str "(" (.toFixed progress 1) "%)")))
                      (set! (.-textContent progress-span) ""))))
                ;; Full render for other views, initial timeline render, or when slider needs to be created
                (replace-children! content-el (views/main-view state)))))))
      (swap! render-state assoc
             :render-scheduled? false
             :last-render-ms (js/Date.now)))
    (catch :default e
      (println "Error in render:" e)
      (js/console.error e))))

(defn schedule-render! []
  (let [{:keys [render-scheduled? last-render-ms throttle-ms]} @render-state
        now (js/Date.now)
        time-since-last (- now last-render-ms)]
    (when debug?
      (println "schedule-render! called - render-scheduled?:" render-scheduled? "time-since-last:" time-since-last "throttle-ms:" throttle-ms))
    (cond
      render-scheduled?
      (when debug? (println "Render already scheduled, skipping"))
      
      (< time-since-last throttle-ms)
      (when debug? (println "Throttling render, time since last:" time-since-last "ms"))
      
      :else
      (do
        (when debug? (println "Scheduling render via requestAnimationFrame"))
        (swap! render-state assoc :render-scheduled? true)
        (js/requestAnimationFrame render!)))))

;; Expose update-timeline-display-only! globally so views can call it
(set! (.-updateTimelineDisplay js/window) update-timeline-display-only!)

;; Watch app-state for changes and schedule renders
;; Skip render scheduling when slider is being dragged or dropdown is being interacted with
(add-watch app-state :render-watcher
           (fn [_ _ old new]
             (let [available-files-changed? (not= (:available-files old) (:available-files new))]
               (when (and (not= old new)
                         (not (:slider-dragging new))
                         (not (:dropdown-interacting new)))
                 (when available-files-changed?
                   (when debug? (println "available-files changed, forcing immediate render"))
                   ;; Force immediate render for available-files changes (don't throttle)
                   (swap! render-state assoc :render-scheduled? false :last-render-ms 0)
                   (schedule-render!))
                 (when-not available-files-changed?
                   (schedule-render!))))))

;; Expose schedule-render! globally so other components can trigger renders
(set! (.-scheduleRender js/window) schedule-render!)
