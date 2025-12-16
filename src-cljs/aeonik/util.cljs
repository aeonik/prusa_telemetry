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

(defn pad-number
  "Pad a number with leading zeros"
  [n width]
  (let [s (str n)]
    (if (< (count s) width)
      (str (apply str (repeat (- width (count s)) "0")) s)
      s)))

(defn format-time
  "Format time in microseconds to MM:SS.mmm"
  [time-us]
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

(defn device-time-us-to-ms
  "Convert device-time-us (microseconds) to milliseconds for timeline operations"
  [time-us]
  (when time-us
    (int (/ time-us 1000))))

(defn format-device-time-us
  "Format device-time-us (microseconds) to MM:SS.mmm for display.
   This is the same as format-time but with a clearer name."
  [time-us]
  (format-time time-us))

(defn calculate-wall-time-ms
  "Calculate wall time (milliseconds) for a metric based on packet received-at and device-time progression.
   Uses the first metric's device-time-us as baseline and calculates relative wall time."
  [packet-received-at-ms first-device-time-us metric-device-time-us]
  (when (and packet-received-at-ms first-device-time-us metric-device-time-us)
    (let [device-time-delta-us (- metric-device-time-us first-device-time-us)
          device-time-delta-ms (/ device-time-delta-us 1000.0)]
      (+ packet-received-at-ms device-time-delta-ms))))

(defn format-wall-time-from-ms
  "Format milliseconds timestamp to HH:mm:ss.SSS"
  [time-ms]
  (when time-ms
    (let [date (js/Date. time-ms)
          hours (.getHours date)
          minutes (.getMinutes date)
          seconds (.getSeconds date)
          millis (.getMilliseconds date)]
      (str (pad-number hours 2) ":" (pad-number minutes 2) ":" (pad-number seconds 2) "." (pad-number millis 3)))))

(defn get-metrics-at-packet
  "Get all metrics from a specific packet by packet-msg number.
   Returns metrics with calculated wall-time added."
  [timeline-data filename packet-msg]
  (let [packets (get timeline-data filename [])
        packet (first (filter #(= (:packet-msg %) packet-msg) packets))]
    (if packet
      (let [events (:events packet)
            received-at (:received-at packet)
            ;; Handle received-at: could be Date object, milliseconds number, or nil
            received-at-ms (cond
                            (number? received-at) received-at
                            (instance? js/Date received-at) (.getTime received-at)
                            :else nil)
            first-device-time-us (some :device-time-us events)]
        (map (fn [event]
               (assoc event
                      :calculated-wall-time-ms (calculate-wall-time-ms received-at-ms first-device-time-us (:device-time-us event))
                      :calculated-wall-time-str (when-let [wt (calculate-wall-time-ms received-at-ms first-device-time-us (:device-time-us event))]
                                                  (format-wall-time-from-ms wt))))
             events))
      [])))
