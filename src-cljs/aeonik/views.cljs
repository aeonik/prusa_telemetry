(ns aeonik.views
  (:require [aeonik.util :as u]
            [aeonik.events :refer [dispatch!]]
            [aeonik.telemetry-events :as te]
            [aeonik.replay.index :as replay-index]
            [aeonik.state :as state :refer [app-state]]
            [aeonik.files :as files]
            [aeonik.gcode :as gcode]
            [aeonik.views.gcode :as gcode-view]
            [clojure.string :as str]))

(defn status-view [_app-state]
  (let [connected? (= (:connection _app-state) :connected)]
    [:span {:class "status"}
     [:span {:class (if connected? "connected" "disconnected")} "●"]
     " "
     (if connected? "Connected" "Disconnected")]))

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
  "Compute time range from metrics using device-time-us (microseconds)"
  [metrics]
  (when (seq metrics)
    (let [metrics-with-time (filter #(some? (:device-time-us %)) metrics)]
      (when (seq metrics-with-time)
        (let [times (map :device-time-us metrics-with-time)
              min-time (apply min times)
              max-time (apply max times)]
          {:min min-time :max max-time})))))

(defn- timeline-packet-range-display [packet-range]
  [:div {:class "time-range-display"}
   [:span {:class "time-label"} "Packet Range: "]
   [:span {:class "time-min"} (str (:min packet-range))]
   [:span " → "]
   [:span {:class "time-max"} (str (:max packet-range))]])

(defonce timeline-scrub-frame (atom nil))
(defonce timeline-scrub-packet-msg (atom nil))

(defn- dispatch-timeline-packet-msg!
  "Coalesce high-frequency slider input to one state update per animation frame."
  [packet-msg]
  (when (and (number? packet-msg) (not (js/isNaN packet-msg)))
    (reset! timeline-scrub-packet-msg packet-msg)
    (when-not @timeline-scrub-frame
      (reset! timeline-scrub-frame
              (js/requestAnimationFrame
               (fn []
                 (let [next-msg @timeline-scrub-packet-msg]
                   (reset! timeline-scrub-frame nil)
                   (reset! timeline-scrub-packet-msg nil)
                   (when (and (number? next-msg) (not (js/isNaN next-msg)))
                     (dispatch! {:type :timeline/set-packet-msg
                                 :packet-msg next-msg})))))))))

(defn- timeline-slider
  "Timeline slider works with packet msg numbers.
   Step is 1 packet."
  [packet-range current-packet-msg]
  (let [value (or current-packet-msg (:min packet-range) 0)]
    [:input {:type "range"
             :id "time-slider"
             :class "time-slider"
             :min (:min packet-range)
             :max (:max packet-range)
             :value value
             :step 1
             :on-mouse-down #(dispatch! {:type :timeline/stop})
             :on-touch-start #(dispatch! {:type :timeline/stop})
             :on-change (fn [e]
                         (let [target (.-target e)
                               new-msg (js/parseInt (.-value target))]
                           (dispatch-timeline-packet-msg! new-msg)))
             :on-input (fn [e]
                        (let [target (.-target e)
                              new-msg (js/parseInt (.-value target))]
                          (dispatch-timeline-packet-msg! new-msg)))}]))

(defn- timeline-packet-display
  "Display current packet msg number and progress percentage."
  [current-packet-msg packet-range]
  [:div {:class "time-display-container"}
   [:span {:id "time-display"
           :class "time-display"} (str "Packet " current-packet-msg)]
   [:span {:id "time-progress"
           :class "time-progress"}
    (if packet-range
      (let [progress (* 100.0 (/ (- current-packet-msg (:min packet-range))
                                 (- (:max packet-range) (:min packet-range))))]
        (str "(" (.toFixed progress 1) "%)"))
      "")]])

(defn- timeline-buttons [packet-range timeline-playing?]
  [:div {:class "timeline-buttons"}
   [:button {:class "timeline-btn"
             :on-click (fn []
                        (when packet-range
                          (dispatch! {:type :timeline/step-backward
                                      :packet-range packet-range})))}
    "⏮"]
   [:button {:class (str "timeline-btn " (when timeline-playing? "playing"))
             :on-click (fn []
                        (if timeline-playing?
                          (dispatch! {:type :timeline/stop})
                          (dispatch! {:type :timeline/play})))}
    (if timeline-playing? "⏸" "▶")]
   [:button {:class "timeline-btn"
             :on-click (fn []
                        (when packet-range
                          (dispatch! {:type :timeline/step-forward
                                      :packet-range packet-range})))}
    "⏭"]
   [:button {:class "timeline-btn"
             :on-click (fn []
                        (when packet-range
                          (dispatch! {:type :timeline/jump-to-start
                                      :packet-range packet-range})))}
    "⏪"]
   [:button {:class "timeline-btn"
             :on-click (fn []
                        (when packet-range
                          (dispatch! {:type :timeline/jump-to-end
                                      :packet-range packet-range})))}
    "⏩"]])

(defn- timeline-scrubber [packet-range current-packet-msg timeline-playing?]
  (when packet-range
    [:div {:class "timeline-scrubber"}
     (timeline-packet-range-display packet-range)
     [:div {:class "slider-container"}
      (timeline-slider packet-range current-packet-msg)
      (timeline-packet-display current-packet-msg packet-range)]
     (timeline-buttons packet-range timeline-playing?)]))

(defn- timeline-metrics-table [sorted-metrics]
  (if (empty? sorted-metrics)
    [:div {:class "empty"} "No metrics at selected packet"]
    [:table {:class "metrics"}
     [:thead
      [:tr
       [:th "Sender"]
       [:th "Metric"]
       [:th "Value"]
       [:th "Type"]
       [:th "Device Time"]
       [:th "Wall Time"]]]
     [:tbody
      (map-indexed (fn [idx metric]
             [:tr {:key (str (:sender metric) "/" (:name metric) "/" (or (:device-time-us metric) (:offset-us metric) idx))}
              [:td (:sender metric)]
              [:td (:name metric)]
              [:td (u/format-metric-value metric)]
              [:td (:type metric)]
              [:td (or (:device-time-str metric) "--------")]
              [:td (or (:calculated-wall-time-str metric) "--------")]])
           sorted-metrics)]]))

(defn timeline-filename-selector
  "Simple dropdown to select and load a file"
  [available-files]
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

(defn timeline-view
  "Simple timeline view - reactive to app-state, uses packet msg numbers"
  []
  (let [app-state-val @app-state
        timeline-data (state/get-timeline-data nil) ; nil triggers memoized reaction
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
        packets (get timeline-data print-filename [])
        packet-range (when (seq packets)
                       (let [msg-numbers (map :packet-msg packets)]
                         {:min (apply min msg-numbers)
                          :max (apply max msg-numbers)}))
        ;; Ensure current-packet-msg is within the valid range, defaulting to max
        current-packet-msg (let [selected-packet-msg (:selected-packet-msg app-state-val)
                                 default-msg (when packet-range (:max packet-range))]
                             (cond
                               (and selected-packet-msg packet-range
                                    (>= selected-packet-msg (:min packet-range))
                                    (<= selected-packet-msg (:max packet-range)))
                               selected-packet-msg
                               :else
                               default-msg))
        ;; Update state if packet-msg is out of range
        _ (when (and packet-range current-packet-msg
                     (not= current-packet-msg (:selected-packet-msg app-state-val)))
            (js/setTimeout #(dispatch! {:type :timeline/set-packet-msg :packet-msg current-packet-msg}) 0))
        metrics-at-packet (if (and print-filename current-packet-msg packet-range
                                   (>= current-packet-msg (:min packet-range))
                                   (<= current-packet-msg (:max packet-range)))
                           (u/get-metrics-at-packet timeline-data print-filename current-packet-msg)
                           [])
        sorted-metrics (sort-by (fn [m] (str (:sender m) "/" (:name m))) metrics-at-packet)]
    [:div {:class "timeline-view"}
     [:div {:class "timeline-controls"}
      (timeline-filename-selector available-files)
      (timeline-scrubber packet-range current-packet-msg (:timeline-playing app-state-val))]
     (timeline-metrics-table sorted-metrics)]))

(def ^:private dashboard-event-window 6000)
(def ^:private dashboard-history-limit 90)

(defn- dashboard-visible-metric? [metric]
  (not (and (= (:type metric) "structured")
            (:has-numeric-fields? metric))))

(defn- format-dashboard-number [value]
  (if-not (te/finite-number? value)
    "--"
    (let [abs-value (js/Math.abs value)]
      (cond
        (>= abs-value 1000) (.toFixed value 0)
        (>= abs-value 100) (.toFixed value 1)
        (>= abs-value 10) (.toFixed value 2)
        (>= abs-value 1) (.toFixed value 3)
        :else (.toFixed value 4)))))

(defn- format-age [received-at]
  (if-not (number? received-at)
    "--"
    (let [age-ms (max 0 (- (.now js/Date) received-at))]
      (cond
        (< age-ms 1000) (str (.toFixed age-ms 0) " ms")
        (< age-ms 60000) (str (.toFixed (/ age-ms 1000) 1) " s")
        :else (str (.toFixed (/ age-ms 60000) 1) " m")))))

(defn- format-duration [seconds]
  (if-not (te/finite-number? seconds)
    "--"
    (let [seconds (max 0 (js/Math.round seconds))
          hours (js/Math.floor (/ seconds 3600))
          minutes (js/Math.floor (/ (mod seconds 3600) 60))
          secs (mod seconds 60)]
      (cond
        (pos? hours) (str hours "h " minutes "m")
        (pos? minutes) (str minutes "m " secs "s")
        :else (str secs "s")))))

(defn- format-percent [value]
  (if-not (te/finite-number? value)
    "--"
    (str (.toFixed value 1) "%")))

(defn- percent-width [value]
  (str (min 100 (max 0 (or value 0))) "%"))

(defn- clamp-ratio [value]
  (min 1 (max 0 (or value 0))))

(def ^:private layer-metric-names
  #{"layer"
    "layer_num"
    "layer_nr"
    "layer_idx"
    "current_layer"
    "print_layer"
    "gcode_layer"})

(defn- latest-metric-by-name [latest-values names]
  (some (fn [metric]
          (when (contains? names (str/lower-case (or (:name metric) "")))
            metric))
        (vals latest-values)))

(defn- proxy-thumbnail-src [thumbnail-path]
  (when (and thumbnail-path (str/starts-with? thumbnail-path "/thumb/"))
    (str "/api/prusalink/proxy" thumbnail-path)))

(defn- print-stat [label value]
  [:div {:class "print-stat"}
   [:span {:class "print-stat-label"} label]
   [:strong value]])

(defn- run-display [run-id]
  (let [run-id (str run-id)]
    (cond
      (str/blank? run-id)
      "--"

      (re-find #"_run-\d{8}-(\d{2})(\d{2})(\d{2})" run-id)
      (let [[_ hours minutes seconds] (re-find #"_run-\d{8}-(\d{2})(\d{2})(\d{2})" run-id)]
        (str hours ":" minutes ":" seconds))

      :else
      run-id)))

(defn- prusalink-print-panel [app-state latest-values]
  (let [prusalink (:prusalink app-state)
        status (:status prusalink)
        print-state (:print-state prusalink)
        status-job (:job status)
        cached-job (:job prusalink)
        job (cond
              (and status-job cached-job (= (:id status-job) (:id cached-job)))
              (merge cached-job status-job)

              status-job
              status-job

              :else
              cached-job)
        printer (:printer status)
        file (:file job)
        refs (:refs file)
        progress (:progress job)
        state-label (or (:state printer)
                        (:state job)
                        (:state print-state)
                        (some-> (:connection prusalink) name)
                        "--")
        display-name (or (:display_name file) (:display-name file) (:name file) "--")
        thumbnail-src (proxy-thumbnail-src (:thumbnail refs))
        layer-metric (latest-metric-by-name latest-values layer-metric-names)
        layer-value (:value layer-metric)
        z-value (:axis_z printer)
        layer-label (if layer-metric "layer" "z")
        layer-display (if layer-metric
                        (u/format-metric-value layer-metric)
                        (if (te/finite-number? z-value)
                          (str (format-dashboard-number z-value) " mm")
                          "--"))]
    [:section {:class "print-panel"}
     [:div {:class "print-thumb"}
      (if thumbnail-src
        [:img {:src thumbnail-src :alt ""}]
        [:div {:class "print-thumb-empty"}])]
     [:div {:class "print-main"}
      [:div {:class "print-title-row"}
       [:div {:class "print-title-wrap"}
        [:span {:class "print-label"} "print"]
        [:h2 {:class "print-title" :title display-name} display-name]]
       [:span {:class (str "print-state "
                           (if (= state-label "PRINTING") "print-state-ok" "print-state-muted"))}
        state-label]]
      [:div {:class "print-progress-row"}
       [:div {:class "print-progress-track"}
        [:div {:class "print-progress-fill"
               :style {:width (percent-width progress)}}]]
       [:strong {:class "print-progress-value"} (format-percent progress)]]
      (when-let [error (:error prusalink)]
        [:div {:class "print-error"} error])]
    [:div {:class "print-stats"}
      [print-stat "job" (if (some? (:id job)) (str "#" (:id job)) "--")]
      [print-stat "run" (run-display (:run-id print-state))]
      [print-stat "remaining" (format-duration (:time_remaining job))]
      [print-stat "elapsed" (format-duration (:time_printing job))]
      [print-stat layer-label layer-display]
      [print-stat "speed" (if (some? (:speed printer)) (str (:speed printer) "%") "--")]
      [print-stat "flow" (if (some? (:flow printer)) (str (:flow printer) "%") "--")]
      [print-stat "nozzle" (let [nozzle (:temp_nozzle printer)]
                             (if (some? nozzle)
                               (str (format-dashboard-number nozzle) " / "
                                    (format-dashboard-number (:target_nozzle printer)))
                               "--"))]]]))

(defn- metric-stats [values]
  (when (seq values)
    (let [values (vec values)
          latest (last values)
          first-value (first values)
          sample-count (count values)
          min-value (apply min values)
          max-value (apply max values)
          avg-value (/ (reduce + values) sample-count)]
      {:latest latest
       :min min-value
       :max max-value
       :avg avg-value
       :delta (- latest first-value)
       :samples sample-count})))

(defn- svg-num [n]
  (.toFixed n 2))

(defn- sparkline [values]
  (let [values (vec values)
        width 180
        height 52
        pad 5
        mid-y (/ height 2)]
    [:svg {:class "sparkline"
           :viewBox (str "0 0 " width " " height)
           :preserveAspectRatio "none"
           :role "img"}
     [:line {:class "sparkline-mid"
             :x1 0
             :x2 width
             :y1 mid-y
             :y2 mid-y}]
     (if (< (count values) 2)
       [:line {:class "sparkline-path"
               :x1 pad
               :x2 (- width pad)
               :y1 mid-y
               :y2 mid-y}]
       (let [min-value (apply min values)
             max-value (apply max values)
             span (max 0.000001 (- max-value min-value))
             x-step (/ (- width (* 2 pad)) (dec (count values)))
             point (fn [idx value]
                     (let [x (+ pad (* idx x-step))
                           normalized (/ (- value min-value) span)
                           y (- (- height pad) (* normalized (- height (* 2 pad))))]
                       [(svg-num x) (svg-num y)]))
             path (str/join
                   " "
                   (map-indexed
                    (fn [idx value]
                      (let [[x y] (point idx value)]
                        (str (if (zero? idx) "M" "L") x " " y)))
                    values))
             [last-x last-y] (point (dec (count values)) (last values))]
         [:<>
          [:path {:class "sparkline-path" :d path}]
          [:circle {:class "sparkline-dot"
                    :cx last-x
                    :cy last-y
                    :r 2.7}]]))]))

(defn- stat-cell [label value]
  [:div {:class "metric-stat"}
   [:span {:class "metric-stat-label"} label]
   [:span {:class "metric-stat-value"} value]])

(defn- metric-card [latest history]
  (let [values (->> history
                    (keep te/metric-number)
                    (take-last dashboard-history-limit)
                    vec)
        stats (metric-stats values)
        numeric? (seq values)
        current-value (if numeric?
                        (format-dashboard-number (:latest stats))
                        (u/format-metric-value latest))
        sender (:sender latest)
        metric-name (:name latest)]
    [:article {:class (str "metric-card" (when-not numeric? " metric-card-text"))}
     [:div {:class "metric-card-head"}
      [:div {:class "metric-title-wrap"}
       [:h2 {:class "metric-title" :title metric-name} metric-name]
       [:div {:class "metric-sender" :title sender} sender]]
      [:span {:class "metric-type"} (:type latest)]]
     [:div {:class "metric-current" :title current-value} current-value]
     (if numeric?
       [sparkline values]
       [:div {:class "metric-text-value" :title current-value} current-value])
     [:div {:class "metric-stats"}
      (if numeric?
        [:<>
         [stat-cell "min" (format-dashboard-number (:min stats))]
         [stat-cell "avg" (format-dashboard-number (:avg stats))]
         [stat-cell "max" (format-dashboard-number (:max stats))]
         [stat-cell "delta" (format-dashboard-number (:delta stats))]]
        [:<>
         [stat-cell "samples" (str (count history))]
         [stat-cell "age" (format-age (:received-at latest))]])]
     [:div {:class "metric-foot"}
      [:span (str "samples " (count history))]
      [:span (str "age " (format-age (:received-at latest)))]]]))

(defn- last-print-filename [events]
  (some :print-filename (reverse events)))

(defn- dashboard-summary [app-state metric-count packet-count last-event]
  (let [events (:telemetry-events app-state)]
    [:section {:class "dashboard-summary"}
     [:div {:class "summary-card"}
      [:span {:class "summary-label"} "connection"]
      [:strong {:class (if (= (:connection app-state) :connected) "summary-ok" "summary-bad")}
       (name (:connection app-state))]]
     [:div {:class "summary-card"}
      [:span {:class "summary-label"} "metrics"]
      [:strong metric-count]]
     [:div {:class "summary-card"}
      [:span {:class "summary-label"} "packets"]
      [:strong packet-count]]
     [:div {:class "summary-card"}
      [:span {:class "summary-label"} "last"]
      [:strong (format-age (:received-at last-event))]]
     [:div {:class "summary-card summary-print"}
      [:span {:class "summary-label"} "print"]
      [:strong {:title (or (last-print-filename events) "--")}
       (or (last-print-filename events) "--")]]]))

(defn dashboard-view [app-state]
  (let [events (:telemetry-events app-state)
        recent-events (vec (take-last dashboard-event-window events))
        latest-values (state/get-latest-values events)
        histories (group-by te/metric-key recent-events)
        packet-count (count (set (keep :packet-msg recent-events)))
        metric-count (count latest-values)
        last-event (last events)
        cards (->> (vals latest-values)
                   (filter dashboard-visible-metric?)
                   (sort-by (fn [metric]
                              [(if (te/metric-number metric) 0 1)
                               (str/lower-case (or (:name metric) ""))
                               (str (:sender metric))])))]
	    [:div {:class "dashboard-view"}
     [prusalink-print-panel app-state latest-values]
	     (dashboard-summary app-state metric-count packet-count last-event)
     (if (empty? cards)
       [:div {:class "dashboard-empty"} "Waiting for telemetry data..."]
       [:section {:class "dashboard-grid"}
        (map (fn [metric]
               (let [key (te/metric-key metric)
                     history (get histories key [])]
                 ^{:key (str (first key) "/" (second key))}
                 [metric-card metric history]))
             cards)])]))

(defn- replay-archive-label [file-info]
  (str (:date file-info) " - " (:filename file-info)
       " (" (.toFixed (/ (:size file-info) 1024) 1) " KB)"))

(defn- replay-run-selector [available-files selected-run]
  [:div {:class "replay-control replay-run-select"}
   [:label {:for "replay-run-select"} "Run"]
   [:select {:id "replay-run-select"
             :value (or selected-run "")
             :on-change (fn [e]
                          (let [value (aget e "target" "value")]
                            (when (not= value "")
                              (let [file-info (first (filter #(= value (str (:date %) ":" (:filename %)))
                                                             available-files))]
                                (when file-info
                                  (dispatch! {:type :replay/select-run :archive value})
                                  (files/load-telemetry-file-replace (:date file-info) (:filename file-info)))))))}
    [:option {:key "empty" :value ""} "-- Select run --"]
    (map (fn [file-info]
           (let [archive (str (:date file-info) ":" (:filename file-info))]
             [:option {:key archive :value archive}
              (replay-archive-label file-info)]))
         available-files)]])

(defn- load-gcode-file! [file]
  (when file
    (let [reader (js/FileReader.)]
      (dispatch! {:type :replay/gcode-loading
                 :file-name (.-name file)})
      (set! (.-onload reader)
            (fn [event]
              (js/setTimeout
               (fn []
                 (try
                   (let [text (.. event -target -result)
                         parsed (assoc (gcode/parse-gcode text)
                                       :cache-key (str (.-name file)
                                                       ":"
                                                       (.-size file)
                                                       ":"
                                                       (.-lastModified file)))]
                     (dispatch! {:type :replay/gcode-loaded
                                :file-name (.-name file)
                                :gcode parsed}))
                   (catch :default e
                     (dispatch! {:type :replay/gcode-error
                                :message (or (.-message e) (str e))}))))
               0)))
      (set! (.-onerror reader)
            (fn [_]
              (dispatch! {:type :replay/gcode-error
                         :message "Unable to read G-code file"})))
      (.readAsText reader file))))

(defn- replay-gcode-loader [{:keys [gcode gcode-file-name gcode-loading? gcode-error]}]
  [:div {:class "replay-control replay-gcode-loader"}
   [:label {:for "replay-gcode-file"} "G-code"]
   [:div {:class "replay-file-row"}
    [:input {:id "replay-gcode-file"
             :type "file"
             :accept ".gcode,.gco,.gc,.txt"
             :on-change (fn [e]
                          (when-let [file (aget (.-files (.-target e)) 0)]
                            (load-gcode-file! file)))}]
    (when gcode
      [:button {:class "secondary"
                :on-click #(dispatch! {:type :replay/clear-gcode})}
       "Clear"])]
   [:div {:class (str "replay-file-status" (when gcode-error " replay-file-error"))}
    (cond
      gcode-error gcode-error
      gcode-loading? (str "Loading " gcode-file-name)
      gcode (str gcode-file-name " / "
                 (count (:segments gcode)) " moves"
                 (when-let [total-layers (:total-layers gcode)]
                   (str " / " total-layers " layers")))
      gcode-file-name (str "Previous G-code: " gcode-file-name)
      :else "No G-code loaded")]])

(defn- current-packet-msg-for [selected-packet-msg packet-range]
  (when packet-range
    (if (and selected-packet-msg
             (>= selected-packet-msg (:min packet-range))
             (<= selected-packet-msg (:max packet-range)))
      selected-packet-msg
      (:min packet-range))))

(defn- packet-ratio [packet-range current-packet-msg]
  (if (and packet-range current-packet-msg (> (:max packet-range) (:min packet-range)))
    (/ (- current-packet-msg (:min packet-range))
       (- (:max packet-range) (:min packet-range)))
    0))

(defn- metric-name-matches? [metric names]
  (let [metric-name (str/lower-case (or (:name metric) ""))]
    (or (contains? names metric-name)
        (some #(str/ends-with? metric-name (str "." %)) names))))

(defn- metric-by-names [metrics names]
  (some (fn [metric]
          (when (metric-name-matches? metric names)
            metric))
        metrics))

(def ^:private sdpos-metric-names
  #{"sdpos" "sd_pos" "file_position" "gcode_offset" "gcode_pos"})

(defn- metric-at-or-before [metric-cards names packet-msg]
  (when-let [summary (metric-by-names metric-cards names)]
    (replay-index/event-at-or-before summary packet-msg)))

(defn- replay-correlation [gcode-data replay-data packet-range current-packet-msg]
  (let [segments (:segments gcode-data)
        sdpos-metric (metric-at-or-before (:metric-cards replay-data)
                                          sdpos-metric-names
                                          current-packet-msg)
        sdpos (te/metric-number sdpos-metric)
        packet-ratio (packet-ratio packet-range current-packet-msg)
        sdpos-ratio (when (and (number? sdpos)
                               (pos? (or (:byte-length gcode-data) 0)))
                      (/ sdpos (:byte-length gcode-data)))
        ratio (clamp-ratio (or sdpos-ratio packet-ratio))
        segment-index (or (gcode/segment-index-at-offset segments sdpos)
                          (gcode/segment-index-at-ratio segments ratio))]
    {:sdpos sdpos
     :ratio ratio
     :segment-index segment-index
     :mode (if sdpos "sdpos" "packet ratio")}))

(def ^:private replay-spark-window 120)

(defn- sample-number [sample]
  (let [value (:value sample)]
    (when (te/finite-number? value)
      value)))

(defn- replay-metric-card [summary current-packet-msg]
  (let [latest (:latest summary)
        selected-sample (replay-index/sample-at-or-before summary current-packet-msg)
        numeric-values (replay-index/numeric-window-values-at
                        summary
                        current-packet-msg
                        replay-spark-window)
        numeric? (:numeric? summary)
        stats (metric-stats numeric-values)
        selected-value (cond
                         (and numeric? selected-sample)
                         (format-dashboard-number (sample-number selected-sample))

                         selected-sample
                         (u/format-metric-value
                          (replay-index/sample->event summary selected-sample))

                         :else "--")
        sender (:sender latest)
        metric-name (:name latest)]
    [:article {:class (str "replay-metric-card" (when-not numeric? " replay-metric-card-text"))}
     [:div {:class "metric-card-head"}
      [:div {:class "metric-title-wrap"}
       [:h2 {:class "metric-title" :title metric-name} metric-name]
       [:div {:class "metric-sender" :title sender} sender]]
      [:span {:class "metric-type"} (:type latest)]]
     [:div {:class "replay-metric-current" :title selected-value} selected-value]
     (if numeric?
       [sparkline (vec numeric-values)]
       [:div {:class "metric-text-value" :title selected-value} selected-value])
     [:div {:class "metric-stats"}
      (if numeric?
        [:<>
         [stat-cell "min" (format-dashboard-number (:min stats))]
         [stat-cell "avg" (format-dashboard-number (:avg stats))]
         [stat-cell "max" (format-dashboard-number (:max stats))]
         [stat-cell "delta" (format-dashboard-number (:delta stats))]]
        [:<>
         [stat-cell "samples" (str (or (:event-count summary) 0))]
         [stat-cell "seen" (if selected-sample
                              (str (:packet-msg selected-sample))
                              "--")]])]]))

(defn- replay-toolpath-panel [gcode-data replay-data packet-range current-packet-msg]
  [gcode-view/replay-toolpath-panel
   gcode-data
   (when gcode-data
     (replay-correlation gcode-data replay-data packet-range current-packet-msg))])

(defonce replay-autoload-run (atom nil))

(defn- replay-packet-at [replay-data packet-msg]
  (replay-index/packet-at replay-data packet-msg))

(defn- replay-load-status [{:keys [loading? load-progress error]}]
  (cond
    error
    [:div {:class "replay-empty replay-error"} error]

    loading?
    (let [{:keys [processed total]} load-progress
          pct (if (and (pos? (or total 0)) (number? processed))
                (* 100 (/ processed total))
                0)]
      [:div {:class "replay-empty replay-loading"}
       [:div {:class "replay-loading-body"}
        [:strong "Loading replay data"]
        [:span (str (or processed 0) " / " (or total "--") " packets")]
        [:div {:class "print-progress-track"}
         [:div {:class "print-progress-fill"
                :style {:width (percent-width pct)}}]]]])

    :else
    nil))

(defn- replay-index-summary-label [replay-data]
  (if-let [{:keys [packets metric-series metric-samples]} (:memory-summary replay-data)]
    (str packets " packets / " metric-series " series / " metric-samples " samples")
    "no run"))

(defn replay-view [app-state]
  (let [available-files (:available-files app-state)
        replay (:replay app-state)
        selected-run (:selected-run replay)
        replay-data (:data replay)
        print-filename (:print-filename replay-data)
        packet-range (:packet-range replay-data)
        current-packet-msg (current-packet-msg-for (:selected-packet-msg app-state) packet-range)
        _ (when (and selected-run
                     (seq available-files)
                     (nil? replay-data)
                     (not (:loading? replay))
                     (not (:error replay))
                     (not= @replay-autoload-run selected-run))
            (when-let [file-info (first (filter #(= selected-run (str (:date %) ":" (:filename %)))
                                                available-files))]
              (reset! replay-autoload-run selected-run)
              (js/setTimeout #(files/load-telemetry-file-replace (:date file-info)
                                                                 (:filename file-info))
                             0)))
        _ (when (and packet-range current-packet-msg
                     (not= current-packet-msg (:selected-packet-msg app-state)))
            (js/setTimeout #(dispatch! {:type :timeline/set-packet-msg :packet-msg current-packet-msg}) 0))
        packet (replay-packet-at replay-data current-packet-msg)
        metrics-at-packet (replay-index/events-at-packet replay-data current-packet-msg)
        sorted-metrics (sort-by (fn [m] (str (:sender m) "/" (:name m))) metrics-at-packet)
        cards (->> (:metric-cards replay-data)
                   (filter #(dashboard-visible-metric? (:latest %)))
                   (sort-by (fn [metric]
                              [(if (:numeric? metric) 0 1)
                               (str/lower-case (or (:name metric) ""))
                               (str (:sender metric))])))
        status-view (replay-load-status replay)]
    [:div {:class "replay-view"}
     [:section {:class "replay-toolbar"}
      [replay-run-selector available-files selected-run]
      [replay-gcode-loader replay]
      [:div {:class "replay-scrubber-wrap"}
       (timeline-scrubber packet-range current-packet-msg (:timeline-playing app-state))]]
     [:section {:class "replay-grid"}
      [replay-toolpath-panel (:gcode replay) replay-data packet-range current-packet-msg]
      [:div {:class "replay-scroll-column"}
       [:section {:class "replay-metrics-panel"}
        [:div {:class "replay-panel-head"}
         [:h2 "Metrics"]
         [:span {:title (or print-filename "")}
          (replay-index-summary-label replay-data)]]
        (cond
          status-view
          status-view

          (empty? cards)
          [:div {:class "replay-empty"} "No replay data loaded"]

          :else
          [:div {:class "replay-metrics-grid"}
           (map (fn [summary]
                  (let [[sender metric-name] (:key summary)]
                    ^{:key (str sender "/" metric-name)}
                    [replay-metric-card summary current-packet-msg]))
                cards)])]
       [:section {:class "replay-current-panel"}
        [:div {:class "replay-panel-head"}
         [:h2 "Packet"]
         [:span (if current-packet-msg
                  (str "packet " current-packet-msg
                       (when-let [metric-count (:metric-count packet)]
                         (str " / " metric-count " metrics")))
                  "no packet")]]
        [timeline-metrics-table sorted-metrics]]]]]))

(defn main-view [app-state]
  (case (:view-mode app-state)
    :latest  (latest-view app-state)
    :packets (packets-view app-state)
    :timeline (timeline-view)
    :dashboard (dashboard-view app-state)
    :replay (replay-view app-state)
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
  "Parameters: app-state map and page booleans.
   Returns: hiccup vector describing the header controls."
  [app-state timeline-page? dashboard-page? replay-page?]
  (let [{:keys [paused view-mode]} app-state
        toggle-label (view-toggle-label view-mode)]
    [:div {:class "header-controls"}
     [:div {:class "status"}
      (if replay-page?
        [:span "Archive Replay"]
        (status-view app-state))]
     (cond
       timeline-page?
       [:a {:href "/" :style {:text-decoration "none"}}
        [:button {:class "secondary"} "Back to Dashboard"]]

       replay-page?
       [:<>
        [:a {:href "/dashboard" :style {:text-decoration "none"}}
         [:button {:class "secondary"} "Live Metrics"]]
        [:a {:href "/timeline" :style {:text-decoration "none"}}
         [:button {:class "secondary"} "Timeline"]]]

       dashboard-page?
       [:<>
        [:a {:href "/" :style {:text-decoration "none"}}
         [:button {:class "secondary"} "Tables"]]
        [:a {:href "/timeline" :style {:text-decoration "none"}}
         [:button {:class "secondary"} "Timeline"]]
        [:a {:href "/replay" :style {:text-decoration "none"}}
         [:button {:class "secondary"} "Replay"]]
        [:button {:id "pause-btn"
                  :on-click #(dispatch! {:type :pause/toggle})}
         (if paused "Resume" "Pause")]
        [:button {:id "clear-btn"
                  :class "secondary"
                  :on-click #(dispatch! {:type :data/clear})}
         "Clear"]]

       :else
       [:<>
        [:a {:href "/dashboard" :style {:text-decoration "none"}}
         [:button {:class "secondary"} "Metrics"]]
        [:a {:href "/replay" :style {:text-decoration "none"}}
         [:button {:class "secondary"} "Replay"]]
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
        dashboard-page? (= path "/dashboard")
        replay-page? (= path "/replay")
        normal-view (if (contains? #{:dashboard :replay} (:view-mode app-state))
                      :latest
                      (:view-mode app-state))
        enforced-view (cond
                        timeline-page? :timeline
                        dashboard-page? :dashboard
                        replay-page? :replay
                        :else normal-view)
        content-state (assoc app-state :view-mode enforced-view)]
    [:div {:class "container"}
     [:div {:class "header"}
      [:h1 (cond
             timeline-page? "Prusa Telemetry Timeline"
             dashboard-page? "Prusa Metrics Dashboard"
             replay-page? "Prusa Replay"
             :else "Prusa Telemetry Dashboard")]
      (header-controls content-state timeline-page? dashboard-page? replay-page?)]
     [:div {:class (if (or dashboard-page? replay-page?) "dashboard-content" "content")}
      (main-view content-state)]]))
