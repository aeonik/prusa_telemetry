(ns aeonik.views
  (:require [aeonik.util :as u]
            [aeonik.events :refer [dispatch!]]
            [aeonik.state :as state]
            [aeonik.files :as files]
            [clojure.string :as str]
            [reagent.core :as r]))

(defn status-view [app-state]
  (if (:connected app-state)
    [:span {:class "connected"} "● Connected"]
    [:span {:class "disconnected"} "● Disconnected"]))

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
               [:tr
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
      (map (fn [packet]
             (let [wall-time (:wall-time-str packet)
                   sender (:sender packet)
                   metrics (:metrics packet)]
               [:div {:class "packet"}
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
                    (map (fn [metric]
                           [:tr
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

(defn- timeline-slider [time-range current-time _slider-dragging?]
  [:input {:type "range"
           :id "time-slider"
           :class "time-slider"
           :min (:min time-range)
           :max (:max time-range)
           :value current-time
           :step 100
           :onmousedown (fn [_]
                         (dispatch! {:type :slider/drag-start})
                         (dispatch! {:type :timeline/stop}))
           :onmouseup (fn [_]
                       (dispatch! {:type :slider/drag-end}))
           :ontouchstart (fn [_]
                          (dispatch! {:type :slider/drag-start})
                          (dispatch! {:type :timeline/stop}))
           :ontouchend (fn [_]
                       (dispatch! {:type :slider/drag-end}))
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

(defn- timeline-scrubber [time-range current-time timeline-playing? slider-dragging?]
  (when time-range
    [:div {:class "timeline-scrubber"}
     (timeline-time-range-display time-range)
     [:div {:class "slider-container"}
      (timeline-slider time-range current-time slider-dragging?)
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
             [:tr
              [:td (:sender metric)]
              [:td (:name metric)]
              [:td (u/format-metric-value metric)]
              [:td (:type metric)]
              [:td (or (:device-time-str metric) "--------")]])
           sorted-metrics)]]))

(defn- format-file-option-label [file-info]
  "Format file info for display in dropdown"
  (str (:date file-info) " - " (:filename file-info) " (" (.toFixed (/ (:size file-info) 1024) 1) " KB)"))

(defn timeline-filename-selector [filenames current-filename available-files]
  (let [filename-to-file-info (reduce (fn [acc file-info]
                                        (assoc acc (:filename file-info) file-info))
                                      {}
                                      available-files)
        loaded-options (map (fn [fname]
                              [:option {:value fname} fname])
                            filenames)
        available-options (map (fn [file-info]
                                 [:option {:value (str "file:" (:filename file-info))}
                                  (format-file-option-label file-info)])
                               available-files)
        options (concat
                 [[:option {:value ""} "-- Select a file --"]]
                 loaded-options
                 available-options)]
    [:div {:class "filename-selector"}
     [:label {:for "filename-select"} "Print File: "]
     [:select {:id "filename-select"
               :value (or current-filename "")
               :onmousedown (fn [_]
                             (dispatch! {:type :dropdown/interact-start}))
               :onblur (fn [_]
                        (dispatch! {:type :dropdown/interact-end}))
               :onchange (fn [e]
                          (let [selected-value (aget e "target" "value")]
                            (js/setTimeout
                             (fn []
                               (dispatch! {:type :dropdown/interact-end}))
                             100)
                            (if (str/starts-with? selected-value "file:")
                              (let [actual-filename (subs selected-value 5)
                                    file-info (get filename-to-file-info actual-filename)]
                                (when file-info
                                  (files/load-telemetry-file! (:date file-info) actual-filename)))
                              (when (not= selected-value "")
                                (dispatch! {:type :timeline/set-filename
                                           :filename selected-value})))))}
      options]]))

(defn timeline-view [app-state]
  (let [timeline-data (state/get-timeline-data (:telemetry-events app-state))
        filenames (keys timeline-data)
        current-filename (or (:selected-filename app-state) (first filenames))
        available-files (:available-files app-state)
        all-metrics (get timeline-data current-filename [])
        time-range (compute-time-range all-metrics)
        current-time (or (:selected-time app-state) (when time-range (:max time-range)))
        metrics-at-time (if (and current-filename current-time)
                         (u/get-metrics-at-time timeline-data current-filename current-time)
                         [])
        sorted-metrics (sort-by (fn [m] (str (:sender m) "/" (:name m))) metrics-at-time)]
    (r/use-effect
     (fn []
       (when (empty? available-files)
         (files/fetch-available-files!))
       js/undefined)
     #js [])
    [:div {:class "timeline-view"}
     [:div {:class "timeline-controls"}
      (timeline-filename-selector filenames current-filename available-files)
      (timeline-scrubber time-range current-time (:timeline-playing app-state) (:slider-dragging app-state))]
     (timeline-metrics-table sorted-metrics)]))

(defn main-view [app-state]
  (case (:view-mode app-state)
    :latest  (latest-view app-state)
    :packets (packets-view app-state)
    :timeline (timeline-view app-state)
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
