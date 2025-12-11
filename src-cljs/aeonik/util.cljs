(ns aeonik.util)

(defn format-metric-value [metric]
  (let [metric-type (:type metric)
        metric-value (:value metric)]
    (case metric-type
      "numeric" (if (number? metric-value)
                  (if (integer? metric-value)
                    (str metric-value)
                    (.toFixed metric-value 3))
                  (str metric-value))
      "error" (str "ERROR: " (:error metric))
      "structured" (let [fields (:fields metric)]
                     (cond
                       (nil? fields) ""
                       ;; Handle JavaScript objects
                       (and (object? fields) (not (array? fields)))
                       (let [keys (js/Object.keys fields)]
                         (str "{" (apply str (interpose ", " (map (fn [k] (str k ": " (aget fields k))) keys))) "}"))
                       (array? fields)
                       (str "[" (apply str (interpose ", " fields)) "]")
                       :else (str fields)))
      (str metric-value))))

(defn pad-number [n width]
  "Pad a number with leading zeros"
  (let [s (str n)]
    (if (< (count s) width)
      (str (apply str (repeat (- width (count s)) "0")) s)
      s)))

(defn format-time [time-us]
  "Format time in microseconds to MM:SS.mmm"
  (if time-us
    (let [seconds (/ time-us 1000000.0)
          total-seconds (int seconds)
          minutes (int (/ total-seconds 60))
          secs-int (mod total-seconds 60)
          secs-frac (int (* 1000 (mod seconds 1)))]
      (str (pad-number minutes 2) ":" (pad-number secs-int 2) "." (pad-number secs-frac 3)))
    "--------"))

(defn get-metrics-at-time [timeline-data filename time-us]
  "Get the latest metric values at or before the given time"
  (let [all-metrics (get timeline-data filename [])
        filtered (filter #(<= (:device-time-us %) time-us) all-metrics)
        grouped (group-by (fn [m] (str (:sender m) "/" (:name m))) filtered)]
    (map (fn [[_key metrics]]
           (let [latest (last (sort-by :device-time-us metrics))]
             latest))
         grouped)))
