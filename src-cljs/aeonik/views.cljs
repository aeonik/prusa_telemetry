(ns aeonik.views
  (:require [aeonik.util :as u]
            [aeonik.events :refer [dispatch!]]))

(defn status-view [state]
  (if (:connected state)
    [:span {:class "connected"} "● Connected"]
    [:span {:class "disconnected"} "● Disconnected"]))

(defn latest-view [state]
  (let [values (vals (:latest-values state))
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

(defn packets-view [state]
  (let [packets (:telemetry-data state)]
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

(defn- timeline-filename-selector [filenames current-filename]
  [:div {:class "filename-selector"}
   [:label {:for "filename-select"} "Print File: "]
   [:select {:id "filename-select"
             :value current-filename
             :onchange (fn [e]
                        (dispatch! {:type :timeline/set-filename
                                   :filename (aget e "target" "value")}))}
    (map (fn [fname]
           [:option {:value fname} fname])
         filenames)]])

(defn- timeline-time-range-display [time-range]
  [:div {:class "time-range-display"}
   [:span {:class "time-label"} "Time Range: "]
   [:span {:class "time-min"} (u/format-wall-time-ms (:min time-range))]
   [:span " → "]
   [:span {:class "time-max"} (u/format-wall-time-ms (:max time-range))]])

(defn- timeline-slider [time-range current-time slider-dragging?]
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
                       ;; Update display immediately with new time for real-time scrubbing
                       (when-let [update-fn (aget js/window "updateTimelineDisplay")]
                         (update-fn new-time))
                       ;; Update state after display update
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

(defn timeline-view [state]
  (let [filenames (keys (:timeline-data state))
        current-filename (or (:selected-filename state) (first filenames))
        all-metrics (get (:timeline-data state) current-filename [])
        time-range (compute-time-range all-metrics)
        current-time (or (:selected-time state) (when time-range (:max time-range)))
        metrics-at-time (if (and current-filename current-time)
                         (u/get-metrics-at-time (:timeline-data state) current-filename current-time)
                         [])
        sorted-metrics (sort-by (fn [m] (str (:sender m) "/" (:name m))) metrics-at-time)]
    [:div {:class "timeline-view"}
     [:div {:class "timeline-controls"}
      (timeline-filename-selector filenames current-filename)
      (timeline-scrubber time-range current-time (:timeline-playing state) (:slider-dragging state))]
     (timeline-metrics-table sorted-metrics)]))

(defn main-view [state]
  (case (:view-mode state)
    :latest  (latest-view state)
    :packets (packets-view state)
    :timeline (timeline-view state)
    (latest-view state)))
