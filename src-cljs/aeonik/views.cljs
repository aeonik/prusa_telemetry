(ns aeonik.views
  (:require [aeonik.util :as u]
            [aeonik.events :refer [dispatch!]]
            [aeonik.state :as state :refer [app-state]]
            [aeonik.files :as files]
            [clojure.string :as str]))

(defn status-view [_app-state]
  [:span {:class "status"} "● Ready"])

(defn latest-view [app-state]
  (let [latest-values (state/get-latest-values (:telemetry-events app-state))
        values (vals latest-values)
        sorted-values (sort-by (fn [v] (str (:sender v) "/" (:name v))) values)]
    (if (empty? sorted-values)
      [:div {:class "empty"} "Waiting for telemetry data..."]
      [:table {:class "metrics"}
       [:thead
        [:tr
         [:th "Sender"]
         [:th "Metric"]
         [:th "Value"]
         [:th "Type"]
         [:th "Time"]]]
       [:tbody
        (map (fn [metric]
               [:tr {:key (str (:sender metric) "/" (:name metric))}
                [:td (:sender metric)]
                [:td (:name metric)]
                [:td (u/format-metric-value metric)]
                [:td (:type metric)]
                [:td (or (:device-time-str metric) "--------")]])
             sorted-values)]])))

(def ^:private packet-history-limit 50)

(defn packets-view [app-state]
  (let [packets (state/get-telemetry-packets (:telemetry-events app-state) packet-history-limit)]
    (if (empty? packets)
      [:div {:class "empty"} "Waiting for telemetry data..."]
      (map-indexed (fn [idx packet]
                      (let [wall-time (:wall-time-str packet)
                            sender (:sender packet)
                            metrics (:metrics packet)]
                        [:div {:key (str "packet-" idx "-" wall-time "-" sender)
                              :class "packet"}
                         [:div {:class "packet-header"}
                          [:span {:class "time"} (or wall-time "--------")]
                          [:span {:class "sender"} "From: " sender]
                          [:span {:class "metric-count"} (str (count metrics)) " metrics"]]
                         (when (and metrics (> (count metrics) 0))
                           [:table {:class "metrics"}
                            [:thead
                             [:tr
                              [:th "Time"]
                              [:th "Metric"]
                              [:th "Value"]]]
                            [:tbody
                             (map-indexed (fn [m-idx metric]
                                           [:tr {:key (str "metric-" idx "-" m-idx "-" (:name metric))}
                                            [:td (or (:device-time-str metric) "--------")]
                                            [:td (:name metric)]
                                            [:td (u/format-metric-value metric)]])
                                         metrics)]])]))
                    (reverse packets)))))

;; Timeline helper functions

(defn- compute-time-range
  [metrics]
  (when (seq metrics)
    (let [metrics-with-time (filter #(some? (:wall-time-ms %)) metrics)]
      (when (seq metrics-with-time)
        (let [times (map :wall-time-ms metrics-with-time)
              min-time (apply min times)
              max-time (apply max times)]
          {:min min-time :max max-time})))))


(defn- timeline-time-range-display [time-range]
  [:div {:class "time-range-display"}
   [:span {:class "time-label"} "Time Range: "]
   [:span {:class "time-min"} (u/format-wall-time-ms (:min time-range))]
   [:span " → "]
   [:span {:class "time-max"} (u/format-wall-time-ms (:max time-range))]])

(defn- timeline-slider [time-range current-time]
  [:input {:type "range"
           :id "time-slider"
           :class "time-slider"
           :min (:min time-range)
           :max (:max time-range)
           :value current-time
           :step 100
           :onmousedown #(dispatch! {:type :timeline/stop})
           :ontouchstart #(dispatch! {:type :timeline/stop})
           :oninput (fn [e]
                     (let [new-time (js/parseInt (aget e "target" "value"))]
                       (dispatch! {:type :timeline/set-time :time new-time})))}])

(defn- timeline-time-display [current-time time-range]
  [:div {:class "time-display-container"}
   [:span {:id "time-display"
           :class "time-display"} (u/format-wall-time-ms current-time)]
   [:span {:id "time-progress"
           :class "time-progress"}
    (if time-range
      (let [progress (* 100.0 (/ (- current-time (:min time-range))
                                (- (:max time-range) (:min time-range))))]
        (str "(" (.toFixed progress 1) "%)"))
      "")]])

(defn- timeline-buttons [time-range timeline-playing?]
  [:div {:class "timeline-buttons"}
   [:button {:class "timeline-btn"
             :onclick (fn []
                       (when time-range
                         (dispatch! {:type :timeline/step-backward
                                    :time-range time-range})))}
    "⏮"]
   [:button {:class (str "timeline-btn " (when timeline-playing? "playing"))
             :onclick (fn []
                       (if timeline-playing?
                         (dispatch! {:type :timeline/stop})
                         (dispatch! {:type :timeline/play})))}
    (if timeline-playing? "⏸" "▶")]
   [:button {:class "timeline-btn"
             :onclick (fn []
                       (when time-range
                         (dispatch! {:type :timeline/step-forward
                                    :time-range time-range})))}
    "⏭"]
   [:button {:class "timeline-btn"
             :onclick (fn []
                       (when time-range
                         (dispatch! {:type :timeline/jump-to-start
                                    :time-range time-range})))}
    "⏪"]
   [:button {:class "timeline-btn"
             :onclick (fn []
                       (when time-range
                         (dispatch! {:type :timeline/jump-to-end
                                    :time-range time-range})))}
    "⏩"]])

(defn- timeline-scrubber [time-range current-time timeline-playing?]
  (when time-range
    [:div {:class "timeline-scrubber"}
     (timeline-time-range-display time-range)
     [:div {:class "slider-container"}
      (timeline-slider time-range current-time)
      (timeline-time-display current-time time-range)]
     (timeline-buttons time-range timeline-playing?)]))

(defn- timeline-metrics-table [sorted-metrics]
  (if (empty? sorted-metrics)
    [:div {:class "empty"} "No metrics at selected time"]
    [:table {:class "metrics"}
     [:thead
      [:tr
       [:th "Sender"]
       [:th "Metric"]
       [:th "Value"]
       [:th "Type"]
       [:th "Time"]]]
     [:tbody
      (map (fn [metric]
             [:tr {:key (str (:sender metric) "/" (:name metric))}
              [:td (:sender metric)]
              [:td (:name metric)]
              [:td (u/format-metric-value metric)]
              [:td (:type metric)]
              [:td (or (:device-time-str metric) "--------")]])
           sorted-metrics)]]))

(defn timeline-filename-selector [available-files]
  "Simple dropdown to select and load a file"
  (let [selected-value (or (:selected-filename @app-state) "")]
    [:div {:class "filename-selector"}
     [:label {:for "filename-select"} "Print File: "]
     [:select {:id "filename-select"
               :value selected-value
               :on-change (fn [e]
                            (let [value (aget e "target" "value")]
                              (when (not= value "")
                                (let [file-info (first (filter #(= value (str (:date %) ":" (:filename %))) available-files))]
                                  (when file-info
                                    (dispatch! {:type :timeline/set-filename :filename value})
                                    (files/load-telemetry-file (:date file-info) (:filename file-info)))))))}
      [:option {:key "empty" :value ""} "-- Select a file --"]
      (map (fn [file-info]
             [:option {:key (str (:date file-info) "-" (:filename file-info))
                       :value (str (:date file-info) ":" (:filename file-info))}
              (str (:date file-info) " - " (:filename file-info) " (" (.toFixed (/ (:size file-info) 1024) 1) " KB)")])
           available-files)]]))

(defn timeline-view []
  "Simple timeline view - reactive to app-state"
  (let [app-state-val @app-state
        timeline-data (state/get-timeline-data (:telemetry-events app-state-val))
        available-files (:available-files app-state-val)
        selected-filename (:selected-filename app-state-val)
        ;; Normalize selected-filename to match timeline-data keys, or use first available
        timeline-filenames (keys timeline-data)
        print-filename (or (when (and selected-filename (seq timeline-filenames))
                            (let [normalize (fn [f]
                                             (-> (str f)
                                                 (str/replace #"^[\"']+" "")
                                                 (str/replace #"[\"']+$" "")
                                                 (str/trim)
                                                 (str/replace #"^[^:]+:" "") ; Remove date: prefix if present
                                                 (str/replace #"^_" "")
                                                 (str/replace #"\.edn$" "")))
                                  normalized-selected (normalize selected-filename)
                                  matched (some #(when (= normalized-selected (normalize %)) %) timeline-filenames)]
                              (when (not matched)
                                (println "Warning: Could not match selected-filename" selected-filename
                                         "to timeline-data keys:" timeline-filenames))
                              matched))
                          (first timeline-filenames))
        all-metrics (get timeline-data print-filename [])
        time-range (compute-time-range all-metrics)
        ;; Ensure current-time is within the valid range, defaulting to max
        current-time (let [selected-time (:selected-time app-state-val)
                           default-time (when time-range (:max time-range))]
                       (cond
                         (and selected-time time-range
                              (>= selected-time (:min time-range))
                              (<= selected-time (:max time-range)))
                         selected-time
                         default-time
                         :else
                         default-time))
        ;; Update state if time is out of range
        _ (when (and time-range current-time
                    (not= current-time (:selected-time app-state-val)))
            (js/setTimeout #(dispatch! {:type :timeline/set-time :time current-time}) 0))
        metrics-at-time (if (and print-filename current-time time-range
                                (>= current-time (:min time-range))
                                (<= current-time (:max time-range)))
                         (u/get-metrics-at-time timeline-data print-filename current-time)
                         [])
        sorted-metrics (sort-by (fn [m] (str (:sender m) "/" (:name m))) metrics-at-time)]
    [:div {:class "timeline-view"}
     [:div {:class "timeline-controls"}
      (timeline-filename-selector available-files)
      (timeline-scrubber time-range current-time (:timeline-playing app-state-val))]
     (timeline-metrics-table sorted-metrics)]))

(defn main-view [app-state]
  (case (:view-mode app-state)
    :latest  (latest-view app-state)
    :packets (packets-view app-state)
    :timeline (timeline-view)
    (latest-view app-state)))

(defn view-toggle-label
  "Parameters: view-mode keyword representing the current view.
   Returns: string label for the view toggle button."
  [view-mode]
  (case view-mode
    :latest "Show Packets"
    :packets "Show Timeline"
    :timeline "Show Latest"
    "Show Packets"))

(defn header-controls
  "Parameters: app-state map, timeline-page? boolean flag.
   Returns: hiccup vector describing the header controls."
  [app-state timeline-page?]
  (let [{:keys [paused view-mode]} app-state
        toggle-label (view-toggle-label view-mode)]
    [:div {:class "header-controls"}
     [:div {:class "status"}
      (status-view app-state)]
     (if timeline-page?
       [:a {:href "/" :style {:text-decoration "none"}}
        [:button {:class "secondary"} "Back to Dashboard"]]
       [:<>
        [:button {:id "view-toggle"
                  :on-click #(dispatch! {:type :view/set-cycle})}
         toggle-label]
        [:button {:id "pause-btn"
                  :on-click #(dispatch! {:type :pause/toggle})}
         (if paused "Resume" "Pause")]
        [:button {:id "clear-btn"
                  :class "secondary"
                  :on-click #(dispatch! {:type :data/clear})}
         "Clear"]])]))

(defn app-shell
  "Parameters: app-state map, path string for the current location.
   Returns: hiccup vector for the full application shell."
  [app-state path]
  (let [timeline-page? (= path "/timeline")
        enforced-view (if timeline-page? :timeline (:view-mode app-state))
        content-state (assoc app-state :view-mode enforced-view)]
    [:div {:class "container"}
     [:div {:class "header"}
      [:h1 (if timeline-page?
             "Prusa Telemetry Timeline"
             "Prusa Telemetry Dashboard")]
      (header-controls content-state timeline-page?)]
     [:div {:class "content"}
      (main-view content-state)]]))