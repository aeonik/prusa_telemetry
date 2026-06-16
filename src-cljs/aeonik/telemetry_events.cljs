(ns aeonik.telemetry-events
  (:require [aeonik.util :as u]
            [clojure.string :as str]))

(defn metric-type-name
  "Return a string metric type name."
  [metric-type]
  (cond
    (keyword? metric-type) (name metric-type)
    (nil? metric-type) nil
    :else (str metric-type)))

(declare field-entries)

(defn tag-entries
  "Return stable tag entries for display and metric identity."
  [tags]
  (let [tags (field-entries tags)]
    (->> tags
         (map (fn [[k v]]
                [(if (keyword? k) (name k) (str k)) v]))
         (sort-by first)
         vec)))

(defn tag-key
  "Return a stable key fragment for metric tags."
  [tags]
  (pr-str (tag-entries tags)))

(defn metric-display-name
  "Return a metric name with a compact tag suffix for display."
  [metric]
  (let [entries (tag-entries (:tags metric))]
    (if (seq entries)
      (str (:name metric)
           "["
           (str/join "," (map (fn [[k v]] (str k "=" v)) entries))
           "]")
      (:name metric))))

(defn metric-key
  "Return the stable identity for a metric event."
  [metric]
  [(or (:sender metric) "")
   (or (:name metric) "")
   (tag-key (:tags metric))])

(defn finite-number?
  "Return true when value is a finite JavaScript number."
  [value]
  (and (number? value)
       (not (js/isNaN value))
       (js/isFinite value)))

(defn metric-number
  "Return the numeric metric value when it is finite."
  [metric]
  (let [value (:value metric)]
    (when (finite-number? value)
      value)))

(defn parse-numeric-like
  "Parse numbers that arrive inside structured metric field values."
  [value]
  (cond
    (number? value) value
    (string? value) (let [s (str/trim value)]
                      (when (re-matches #"[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?" s)
                        (let [n (js/parseFloat s)]
                          (when-not (js/isNaN n)
                            n))))
    :else nil))

(defn field-entries
  "Return structured field entries as a Clojure map when possible."
  [fields]
  (cond
    (map? fields) fields
    (and (object? fields) (not (array? fields))) (js->clj fields :keywordize-keys false)
    :else nil))

(defn metric-event
  "Build one metric event with packet metadata."
  [sender wall-time-str wall-time-ms print-filename packet-msg received-at metric]
  {:sender          sender
   :name            (:name metric)
   :value           (:value metric)
   :tags            (:tags metric)
   :fields          (:fields metric)
   :error           (:error metric)
   :type            (metric-type-name (:type metric))
   :offset-us       (:offset-us metric)
   :offset-ms       (:offset-ms metric)
   :device-time-us  (:device-time-us metric)
   :device-time-str (:device-time-str metric)
   :wall-time-str   wall-time-str
   :wall-time-ms    wall-time-ms
   :packet-msg      packet-msg
   :received-at     received-at
   :print-filename  print-filename})

(defn structured-field-events
  "Expand numeric structured metric fields into synthetic metric events."
  [base-event]
  (let [entries (field-entries (:fields base-event))]
    (->> entries
         (keep (fn [[field-name field-value]]
                 (when-let [numeric-value (parse-numeric-like field-value)]
                   (let [field-name-str (if (keyword? field-name)
                                          (name field-name)
                                          (str field-name))]
                     (-> base-event
                         (assoc :name (str (:name base-event) "." field-name-str)
                                :value numeric-value
                                :fields nil
                                :type "numeric"
                                :parent-name (:name base-event)
                                :field-name field-name-str
                                :synthetic-field? true)
                         (dissoc :error))))))
         vec)))

(defn expand-metric-event
  "Return a base metric event and any synthetic field events."
  [base-event]
  (let [field-events (when (= (:type base-event) "structured")
                       (structured-field-events base-event))]
    (if (seq field-events)
      (into [(assoc base-event :has-numeric-fields? true)] field-events)
      [base-event])))

(defn create-events
  "Create event records from metrics, one per metric or numeric structured field."
  [sender metrics wall-time-str print-filename packet-msg received-at]
  (let [wall-time-ms (or (u/parse-wall-time-str wall-time-str)
                         (when wall-time-str
                           (println "Warning: Failed to parse wall-time-str:" wall-time-str)
                           nil))]
    (vec (mapcat (fn [m]
                   (expand-metric-event
                    (metric-event sender wall-time-str wall-time-ms print-filename packet-msg received-at m)))
                 metrics))))

(defn strip-quotes
  "Remove leading/trailing quotes from a string."
  [s]
  (when s
    (-> (str s)
        (str/replace #"^[\"']+" "")
        (str/replace #"[\"']+$" "")
        str/trim)))

(defn normalize-filename-for-matching
  "Try to match filename to one in timeline-filenames, handling format differences."
  [filename timeline-filenames]
  (let [normalize (fn [f]
                    (-> f
                        strip-quotes
                        (str/replace #"^_" "")
                        (str/replace #"\.edn$" "")))
        normalized-input (normalize filename)
        find-match (fn [timeline-fname]
                     (= normalized-input (normalize timeline-fname)))]
    (or (some #(when (= filename %) %) timeline-filenames)
        (some #(when (find-match %) %) timeline-filenames)
        filename)))

(defn extract-print-filename-from-metrics
  "Extract print_filename from a list of metrics, stripping quotes and normalizing."
  [metrics]
  (let [print-filename-metric (first (filter #(= (:name %) "print_filename") metrics))
        raw-value (when print-filename-metric
                    (or (:value print-filename-metric)
                        (when-let [fields (:fields print-filename-metric)]
                          (if (map? fields)
                            (or (get fields "value")
                                (get fields :value)
                                (first (vals fields)))
                            nil))))
        cleaned (when raw-value
                  (-> (str raw-value)
                      (str/replace #"^[\"']" "")
                      (str/replace #"[\"']$" "")
                      str/trim))]
    cleaned))

(defn event-time
  "Get device-time-us from event, defaulting to 0."
  [event]
  (or (:device-time-us event) 0))

(defn merge-sorted-events
  "Efficiently merge two sorted event vectors into one sorted vector."
  [existing-events new-events]
  (cond
    (empty? new-events) existing-events
    (empty? existing-events) (if (vector? new-events) new-events (vec new-events))
    :else
    (let [existing-vec (if (vector? existing-events) existing-events (vec existing-events))
          new-vec (if (vector? new-events) new-events (vec new-events))
          existing-time (event-time (last existing-vec))
          new-time (event-time (first new-vec))]
      (if (>= new-time existing-time)
        (into existing-vec new-vec)
        (loop [result (transient [])
               existing-idx 0
               new-idx 0
               existing-len (count existing-vec)
               new-len (count new-vec)]
          (cond
            (>= existing-idx existing-len)
            (persistent! (reduce conj! result (subvec new-vec new-idx)))

            (>= new-idx new-len)
            (persistent! (reduce conj! result (subvec existing-vec existing-idx)))

            :else
            (let [existing-time (event-time (get existing-vec existing-idx))
                  new-time (event-time (get new-vec new-idx))]
              (if (<= existing-time new-time)
                (recur (conj! result (get existing-vec existing-idx))
                       (inc existing-idx) new-idx existing-len new-len)
                (recur (conj! result (get new-vec new-idx))
                       existing-idx (inc new-idx) existing-len new-len)))))))))

(defn packets-to-events
  "Convert telemetry packets to event records sorted by device-time-us."
  [packets]
  (let [events-vec (reduce (fn [acc packet]
                             (let [sender (:sender packet)
                                   metrics (:metrics packet)
                                   wall-time-str (:wall-time-str packet)
                                   prelude (:prelude packet)
                                   packet-msg (:msg prelude)
                                   received-at (:received-at packet)
                                   print-filename (extract-print-filename-from-metrics metrics)
                                   new-events (create-events sender metrics wall-time-str print-filename packet-msg received-at)]
                               (when (and (seq new-events) (nil? (:device-time-us (first new-events))))
                                 (println "Warning: Packet has no device-time-us. wall-time-str:" wall-time-str
                                          "sender:" sender "metrics count:" (count metrics)))
                               (reduce conj! acc new-events)))
                           (transient [])
                           packets)]
    (vec (sort-by event-time (persistent! events-vec)))))

(defn trim-events
  "Retain only the most recent limit events."
  [events limit]
  (let [events (if (vector? events) events (vec events))
        event-count (count events)]
    (if (> event-count limit)
      (subvec events (- event-count limit))
      events)))
