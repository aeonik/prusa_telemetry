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

(defn parse-wall-time-str
  "Parse wall-time string (HH:mm:ss.SSS) to milliseconds since midnight"
  [time-str]
  (when time-str
    (try
      (let [parts (.split time-str ":")
            hours (js/parseInt (aget parts 0) 10)
            minutes (js/parseInt (aget parts 1) 10)
            seconds-str (aget parts 2)
            seconds-parts (.split seconds-str (js/RegExp. "\\."))
            seconds (js/parseInt (aget seconds-parts 0) 10)
            millis (if (> (.-length seconds-parts) 1)
                     (js/parseInt (aget seconds-parts 1) 10)
                     0)]
        (+ (* hours 3600000)
           (* minutes 60000)
           (* seconds 1000)
           millis))
      (catch :default e
        (println "Error parsing wall-time:" time-str e)
        nil))))

(defn format-wall-time-ms
  "Format milliseconds since midnight to HH:mm:ss.SSS"
  [time-ms]
  (when time-ms
    (let [total-seconds (int (/ time-ms 1000))
          hours (int (/ total-seconds 3600))
          minutes (int (/ (mod total-seconds 3600) 60))
          seconds (mod total-seconds 60)
          millis (mod time-ms 1000)]
      (str (pad-number hours 2) ":" (pad-number minutes 2) ":" (pad-number seconds 2) "." (pad-number millis 3)))))

(defn get-metrics-at-time [timeline-data filename time-ms]
  "Get the latest metric values at or before the given time (in milliseconds)"
  (let [all-metrics (get timeline-data filename [])
        ;; Filter out metrics without wall-time-ms and those after the selected time
        filtered (filter #(and (some? (:wall-time-ms %))
                              (<= (:wall-time-ms %) time-ms)) all-metrics)
        grouped (group-by (fn [m] (str (:sender m) "/" (:name m))) filtered)]
    (map (fn [[_key metrics]]
           (let [latest (last (sort-by :wall-time-ms metrics))]
             latest))
         grouped)))
