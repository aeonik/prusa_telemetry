(ns aeonik.gcode
  (:require [clojure.string :as str]))

(defn- parse-float [s]
  (when s
    (let [n (js/parseFloat s)]
      (when-not (js/isNaN n)
        n))))

(defn- parse-int [s]
  (when s
    (let [n (js/parseInt s 10)]
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

(defn- total-layer-count [text]
  (some-> (or (second (re-find #"(?im)^;\s*total layer number:\s*(\d+)\s*$" text))
              (second (re-find #"(?im)^;\s*total layers count\s*=\s*(\d+)\s*$" text)))
          parse-int))

(defn- has-explicit-layer-markers? [text]
  (boolean
   (or (re-find #"(?im)^;\s*LAYER_CHANGE\s*$" text)
       (re-find #"(?im)^;\s*LAYER:\d+\s*$" text))))

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

(defn- update-layer-from-comment [state comment]
  (let [comment (some-> comment str/trim str/upper-case)]
    (cond
      (= "LAYER_CHANGE" comment)
      (update state :layer #(if (number? %) (inc %) 0))

      (re-matches #"LAYER:\d+" (or comment ""))
      (assoc state :layer (parse-int (subs comment (count "LAYER:"))))

      :else state)))

(defn- update-layer-from-z [state next-z]
  (if (and (not (:explicit-layer-markers? state))
           (number? next-z)
           (number? (:z state))
           (> next-z (:z state)))
    (update state :layer inc)
    state))

(defn parse-gcode
  "Parse text G-code into lightweight toolpath segments for replay.
   Offsets are character offsets, which match byte offsets for normal ASCII G-code."
  [text]
  (let [text (or text "")
        lines (str/split text #"\r?\n")
        explicit-layers? (has-explicit-layer-markers? text)
        total-layers (total-layer-count text)]
    (loop [remaining lines
           line-number 1
           offset 0
           state {:x nil
                  :y nil
                  :z nil
                  :e 0
                  :f nil
                  :layer (when-not explicit-layers? 0)
                  :feature nil
                  :explicit-layer-markers? explicit-layers?
                  :absolute-xyz? true
                  :absolute-e? true}
           segments []
           bounds nil]
      (if-not (seq remaining)
        (let [layers (->> segments (keep :layer) distinct sort vec)]
          {:segments segments
           :segments-by-layer (group-by :layer segments)
           :bounds bounds
           :line-count (dec line-number)
           :byte-length (count text)
           :layers layers
           :total-layers (or total-layers
                             (when (seq layers) (count layers)))})
        (let [line (first remaining)
              [code comment] (split-comment line)
              command (some-> (first (str/split code #"\s+")) str/upper-case)
              word-map (words code)
              end-offset (+ offset (count line))
              state-with-comment (-> state
                                     (assoc :feature (feature-from-comment comment (:feature state)))
                                     (update-layer-from-comment comment))
              [next-state maybe-segment]
              (cond
                (= command "G90")
                [(assoc state-with-comment :absolute-xyz? true) nil]

                (= command "G91")
                [(assoc state-with-comment :absolute-xyz? false) nil]

                (= command "M82")
                [(assoc state-with-comment :absolute-e? true) nil]

                (= command "M83")
                [(assoc state-with-comment :absolute-e? false) nil]

                (= command "G92")
                [(merge state-with-comment
                        (select-keys (next-position state-with-comment word-map) [:x :y :z :e]))
                 nil]

                (#{"G0" "G00" "G1" "G01"} command)
                (let [next-pos (next-position state-with-comment word-map)
                      moved-state (-> state-with-comment
                                      (update-layer-from-z (:z next-pos))
                                      (merge next-pos))
                      segment (move-segment state-with-comment
                                            next-pos
                                            line-number
                                            offset
                                            end-offset
                                            code)]
                  [moved-state segment])

                :else
                [state-with-comment nil])
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
