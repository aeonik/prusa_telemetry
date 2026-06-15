(ns aeonik.gcode
  (:require [clojure.string :as str]))

(defn- parse-float [s]
  (when s
    (let [n (js/parseFloat s)]
      (when-not (js/isNaN n)
        n))))

(defn- words [code]
  (->> (re-seq #"([A-Za-z])([-+]?(?:\d+(?:\.\d*)?|\.\d+))" code)
       (map (fn [[_ letter value]]
              [(str/upper-case letter) (parse-float value)]))
       (into {})))

(defn- split-comment [line]
  (let [idx (.indexOf line ";")]
    (if (neg? idx)
      [(str/trim line) nil]
      [(str/trim (subs line 0 idx)) (str/trim (subs line (inc idx)))])))

(defn- feature-from-comment [comment current-feature]
  (cond
    (nil? comment) current-feature
    (str/starts-with? comment "TYPE:") (subs comment 5)
    (str/starts-with? comment "WIDTH:") current-feature
    (str/blank? comment) current-feature
    :else current-feature))

(defn- update-bounds [bounds x y]
  (if (and (number? x) (number? y))
    (if bounds
      (assoc bounds
             :min-x (min (:min-x bounds) x)
             :max-x (max (:max-x bounds) x)
             :min-y (min (:min-y bounds) y)
             :max-y (max (:max-y bounds) y))
      {:min-x x :max-x x :min-y y :max-y y})
    bounds))

(defn- update-z-bounds [bounds z]
  (if (and bounds (number? z))
    (assoc bounds
           :min-z (if (number? (:min-z bounds)) (min (:min-z bounds) z) z)
           :max-z (if (number? (:max-z bounds)) (max (:max-z bounds) z) z))
    bounds))

(defn- update-bounds-for-segment [bounds {:keys [x1 y1 x2 y2 z1 z2]}]
  (-> bounds
      (update-bounds x1 y1)
      (update-bounds x2 y2)
      (update-z-bounds z1)
      (update-z-bounds z2)))

(defn- next-position [state word-map]
  (let [{:keys [x y z e f absolute-xyz? absolute-e?]} state
        coord (fn [axis current absolute?]
                (if-let [v (get word-map axis)]
                  (if absolute? v (+ (or current 0) v))
                  current))]
    {:x (coord "X" x absolute-xyz?)
     :y (coord "Y" y absolute-xyz?)
     :z (coord "Z" z absolute-xyz?)
     :e (coord "E" e absolute-e?)
     :f (or (get word-map "F") f)}))

(defn- move-segment [state next-pos line-number start-offset end-offset code]
  (let [{:keys [x y z e f layer feature]} state
        {:keys [x y z e f]} next-pos
        has-xy? (and (number? x) (number? y)
                     (number? (:x state)) (number? (:y state))
                     (or (not= x (:x state)) (not= y (:y state))))
        extrusion (when (and (number? e) (number? (:e state)))
                    (- e (:e state)))]
    (when has-xy?
      {:line-number line-number
       :start-offset start-offset
       :end-offset end-offset
       :x1 (:x state)
       :y1 (:y state)
       :z1 (:z state)
       :x2 x
       :y2 y
       :z2 z
       :e e
       :f f
       :extrusion extrusion
       :extruding? (and (number? extrusion) (pos? extrusion))
       :layer layer
       :feature feature
       :command code})))

(defn- update-layer [state next-z]
  (if (and (number? next-z)
           (number? (:z state))
           (> next-z (:z state)))
    (update state :layer inc)
    state))

(defn parse-gcode
  "Parse text G-code into lightweight toolpath segments for replay.
   Offsets are character offsets, which match byte offsets for normal ASCII G-code."
  [text]
  (let [lines (str/split (or text "") #"\r?\n")]
    (loop [remaining lines
           line-number 1
           offset 0
           state {:x nil
                  :y nil
                  :z nil
                  :e 0
                  :f nil
                  :layer 0
                  :feature nil
                  :absolute-xyz? true
                  :absolute-e? true}
           segments []
           bounds nil]
      (if-not (seq remaining)
        {:segments segments
         :segments-by-layer (group-by :layer segments)
         :bounds bounds
         :line-count (dec line-number)
         :byte-length (count (or text ""))
         :layers (->> segments (map :layer) distinct sort vec)}
        (let [line (first remaining)
              [code comment] (split-comment line)
              command (some-> (first (str/split code #"\s+")) str/upper-case)
              word-map (words code)
              end-offset (+ offset (count line))
              state-with-feature (assoc state :feature (feature-from-comment comment (:feature state)))
              [next-state maybe-segment]
              (cond
                (= command "G90")
                [(assoc state-with-feature :absolute-xyz? true) nil]

                (= command "G91")
                [(assoc state-with-feature :absolute-xyz? false) nil]

                (= command "M82")
                [(assoc state-with-feature :absolute-e? true) nil]

                (= command "M83")
                [(assoc state-with-feature :absolute-e? false) nil]

                (= command "G92")
                [(merge state-with-feature
                        (select-keys (next-position state-with-feature word-map) [:x :y :z :e]))
                 nil]

                (#{"G0" "G00" "G1" "G01"} command)
                (let [next-pos (next-position state-with-feature word-map)
                      moved-state (-> state-with-feature
                                      (update-layer (:z next-pos))
                                      (merge next-pos))
                      segment (move-segment state-with-feature
                                            next-pos
                                            line-number
                                            offset
                                            end-offset
                                            code)]
                  [moved-state segment])

                :else
                [state-with-feature nil])
              segment (when maybe-segment
                        (assoc maybe-segment :index (count segments)))]
          (recur (rest remaining)
                 (inc line-number)
                 (inc end-offset)
                 next-state
                 (cond-> segments segment (conj segment))
                 (if segment (update-bounds-for-segment bounds segment) bounds)))))))

(defn segment-index-at-offset [segments offset]
  (when (and (seq segments) (number? offset))
    (loop [lo 0
           hi (dec (count segments))
           best 0]
      (if (> lo hi)
        best
        (let [mid (js/Math.floor (/ (+ lo hi) 2))
              segment (nth segments mid)]
          (if (<= (:end-offset segment) offset)
            (recur (inc mid) hi mid)
            (recur lo (dec mid) best)))))))

(defn segment-index-at-ratio [segments ratio]
  (when (seq segments)
    (let [idx (js/Math.floor (* (max 0 (min 1 (or ratio 0)))
                                (dec (count segments))))]
      idx)))
