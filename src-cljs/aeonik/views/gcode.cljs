(ns aeonik.views.gcode
  (:require [aeonik.telemetry-events :as te]
            [clojure.string :as str]
            [reagent.core :as r]))

(defn- format-number [value]
  (if-not (te/finite-number? value)
    "--"
    (let [abs-value (js/Math.abs value)]
      (cond
        (>= abs-value 1000) (.toFixed value 0)
        (>= abs-value 100) (.toFixed value 1)
        (>= abs-value 10) (.toFixed value 2)
        :else (.toFixed value 3)))))

(defn- format-percent [value]
  (if-not (te/finite-number? value)
    "--"
    (str (.toFixed value 1) "%")))

(defn- print-stat [label value]
  [:div {:class "print-stat"}
   [:span label]
   [:strong value]])

(defn- svg-num [n]
  (if (number? n) (.toFixed n 3) "0"))

(defn- display-y [bounds y]
  (- (+ (:min-y bounds) (:max-y bounds)) y))

(defn- display-z [bounds z]
  (- (+ (or (:min-z bounds) 0) (or (:max-z bounds) 1)) (or z 0)))

(defn- iso-point [x y z]
  [(* 0.8660254 (- x y))
   (- (* 0.5 (+ x y)) (or z 0))])

(defn- projected-point [projection bounds x y z]
  (case projection
    :side [x (display-z bounds z)]
    :iso (iso-point x y z)
    :top [x (display-y bounds y)]))

(defn- segment-path [projection bounds {:keys [x1 y1 z1 x2 y2 z2]}]
  (when (and (number? x1) (number? y1) (number? x2) (number? y2))
    (let [[sx sy] (projected-point projection bounds x1 y1 z1)
          [ex ey] (projected-point projection bounds x2 y2 z2)]
      (str "M" (svg-num sx) " " (svg-num sy)
           "L" (svg-num ex) " " (svg-num ey)))))

(defn- path-for-segments [projection bounds segments]
  (->> segments
       (keep #(segment-path projection bounds %))
       (str/join " ")))

(defonce model-path-cache (atom {}))

(defn- cache-key [gcode-data]
  (or (:cache-key gcode-data)
      [(:byte-length gcode-data)
       (:line-count gcode-data)
       (count (:segments gcode-data))]))

(defn- cached-model-path [gcode-data projection]
  (let [cache-key [(cache-key gcode-data) projection]]
    (if-let [path (get @model-path-cache cache-key)]
      path
      (let [path (path-for-segments projection
                                    (:bounds gcode-data)
                                    (filter :extruding? (:segments gcode-data)))]
        (swap! model-path-cache
               (fn [cache]
                 (assoc (if (> (count cache) 12) {} cache)
                        cache-key
                        path)))
        path))))

(defn- projection-bounds [{:keys [min-x max-x min-y max-y min-z max-z] :as bounds} projection]
  (if (= projection :iso)
    (let [points (for [x [min-x max-x]
                       y [min-y max-y]
                       z [(or min-z 0) (or max-z 0)]]
                   (projected-point :iso bounds x y z))
          xs (map first points)
          ys (map second points)]
      {:min-x (apply min xs)
       :max-x (apply max xs)
       :min-y (apply min ys)
       :max-y (apply max ys)})
    (let [[min-px min-py] (projected-point projection bounds min-x min-y min-z)
          [max-px max-py] (projected-point projection bounds max-x max-y max-z)]
      {:min-x (min min-px max-px)
       :max-x (max min-px max-px)
       :min-y (min min-py max-py)
       :max-y (max min-py max-py)})))

(defn- viewbox [bounds projection]
  (let [{:keys [min-x max-x min-y max-y]} (projection-bounds bounds projection)
        span-x (max 1 (- max-x min-x))
        span-y (max 1 (- max-y min-y))
        pad (* 0.1 (max span-x span-y))]
    (str (svg-num (- min-x pad)) " "
         (svg-num (- min-y pad)) " "
         (svg-num (+ span-x (* 2 pad))) " "
         (svg-num (+ span-y (* 2 pad))))))

(def ^:private static-model-svg
  (r/create-class
   {:display-name "static-gcode-model-svg"
    :should-component-update (fn [& _] false)
    :reagent-render
    (fn [viewbox model-path]
      [:svg {:class "gcode-stage gcode-stage-model"
             :viewBox viewbox
             :preserveAspectRatio "xMidYMid meet"
             :role "img"}
       [:path {:class "gcode-path-model"
               :d model-path}]])}))

(defn- stage-svg
  ([label projection bounds segments segment-index current-segment]
   (stage-svg label projection bounds segments segment-index current-segment {}))
  ([label projection bounds segments segment-index current-segment
    {:keys [model-path model-key current-only? cursor-radius show-travel? show-upcoming?]
     :or {show-travel? true
          show-upcoming? true
          cursor-radius 0.9}}]
   (let [viewbox (viewbox bounds projection)
         printed-segments (if current-only?
                            (cond-> []
                              (:extruding? current-segment) (conj current-segment))
                            (filter #(and (:extruding? %)
                                          (<= (:index %) segment-index))
                                    segments))
         upcoming-segments (when show-upcoming?
                             (filter #(and (:extruding? %)
                                           (> (:index %) segment-index))
                                     segments))
         travel-segments (when show-travel?
                           (filter #(not (:extruding? %)) segments))
         [tool-x tool-y] (when current-segment
                           (projected-point projection
                                            bounds
                                            (:x2 current-segment)
                                            (:y2 current-segment)
                                            (:z2 current-segment)))]
     [:div {:class (str "gcode-stage-panel gcode-stage-" (name projection))}
      [:span {:class "gcode-stage-label"} label]
      (when-not (str/blank? model-path)
        ^{:key (str "static-model-" model-key)}
        [static-model-svg viewbox model-path])
      [:svg {:class (str "gcode-stage"
                         (when-not (str/blank? model-path) " gcode-stage-overlay"))
             :viewBox viewbox
             :preserveAspectRatio "xMidYMid meet"
             :role "img"}
       (when (seq travel-segments)
         [:path {:class "gcode-path-travel"
                 :d (path-for-segments projection bounds travel-segments)}])
       (when (seq upcoming-segments)
         [:path {:class "gcode-path-upcoming"
                 :d (path-for-segments projection bounds upcoming-segments)}])
       [:path {:class "gcode-path-printed"
               :d (path-for-segments projection bounds printed-segments)}]
       (when (and (number? tool-x) (number? tool-y))
         [:circle {:class "gcode-cursor"
                   :cx (svg-num tool-x)
                   :cy (svg-num tool-y)
                   :r cursor-radius}])]])))

(def ^:private highlight-segment-limit 64)

(defn- recent-segments [segments segment-index limit]
  (let [start (max 0 (- (inc segment-index) limit))
        end (min (count segments) (inc segment-index))]
    (if (< start end)
      (subvec segments start end)
      [])))

(defn- stage [gcode-data correlation]
  (let [segments (vec (:segments gcode-data))
        bounds (:bounds gcode-data)
        segment-index (or (:segment-index correlation) 0)
        current-segment (get segments segment-index)
        current-layer (:layer current-segment)
        current-layer-segments (or (get (:segments-by-layer gcode-data) current-layer)
                                   (filter #(= (:layer %) current-layer) segments))
        highlight-segments (recent-segments segments segment-index highlight-segment-limit)
        model-key (cache-key gcode-data)]
    (if (and bounds current-segment)
      [:div {:class "gcode-stage-grid"}
       [stage-svg "top / current layer" :top bounds current-layer-segments segment-index current-segment]
       [stage-svg "side / full shape" :side bounds highlight-segments segment-index current-segment
        {:model-path (cached-model-path gcode-data :side)
         :model-key [model-key :side]
         :cursor-radius 1.9
         :show-travel? false
         :show-upcoming? false}]
       [stage-svg "isometric / full shape" :iso bounds highlight-segments segment-index current-segment
        {:model-path (cached-model-path gcode-data :iso)
         :model-key [model-key :iso]
         :cursor-radius 1.9
         :show-travel? false
         :show-upcoming? false}]]
      [:div {:class "replay-empty"} "No drawable toolpath"])))

(defn- context-panel [gcode-data correlation]
  (let [segments (vec (:segments gcode-data))
        segment (get segments (:segment-index correlation))]
    [:div {:class "replay-gcode-context"}
     [print-stat "correlation" (:mode correlation)]
     [print-stat "sdpos" (if-let [sdpos (:sdpos correlation)]
                           (format-number sdpos)
                           "--")]
     [print-stat "line" (or (:line-number segment) "--")]
     [print-stat "layer" (or (:layer segment) "--")]
     [print-stat "feature" (or (:feature segment) "--")]
     [print-stat "moves" (str (count segments))]
     [print-stat "lines" (str (:line-count gcode-data))]
     [print-stat "progress" (format-percent (* 100 (:ratio correlation)))]]))

(defn replay-toolpath-panel
  "Render the replay G-code toolpath panel."
  [gcode-data correlation]
  [:section {:class "replay-part-panel"}
   [:div {:class "replay-panel-head"}
    [:h2 "Toolpath"]
    [:span "current layer"]]
   (if (and gcode-data correlation)
     [:<>
      [stage gcode-data correlation]
      [context-panel gcode-data correlation]]
     [:div {:class "replay-empty replay-empty-large"} "No G-code loaded"])])
