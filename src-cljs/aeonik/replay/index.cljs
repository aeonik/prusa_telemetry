(ns aeonik.replay.index
  (:require [aeonik.telemetry-events :as te]
            [aeonik.util :as u]))

(defn empty-build
  "Create an incremental replay index build."
  [archive total]
  {:archive archive
   :total total
   :packet-count 0
   :event-count 0
   :packets []
   :packet-index {}
   :series {}
   :series-order []
   :print-filename nil
   :min-msg nil
   :max-msg nil})

(defn- packet-message
  [build packet]
  (or (get-in packet [:prelude :msg])
      (:packet-count build)))

(defn- update-range
  [build packet-msg]
  (if (number? packet-msg)
    (-> build
        (update :min-msg #(if (number? %) (min % packet-msg) packet-msg))
        (update :max-msg #(if (number? %) (max % packet-msg) packet-msg)))
    build))

(defn- metric-key
  [sender metric-name]
  [(or sender "") (or metric-name "")])

(defn- empty-series
  [key sender metric-name metric-type print-filename]
  {:key key
   :sender sender
   :name metric-name
   :type metric-type
   :print-filename print-filename
   :packet-msgs (array)
   :values (array)
   :fields (array)
   :errors (array)
   :offset-us (array)
   :device-time-us (array)})

(defn- sample-count
  [series]
  (alength (:packet-msgs series)))

(defn- sample-at-index
  [series idx]
  (when (and (number? idx)
             (<= 0 idx)
             (< idx (sample-count series)))
    (cond-> {:packet-msg (aget (:packet-msgs series) idx)}
      (some? (aget (:values series) idx))
      (assoc :value (aget (:values series) idx))

      (some? (aget (:fields series) idx))
      (assoc :fields (aget (:fields series) idx))

      (some? (aget (:errors series) idx))
      (assoc :error (aget (:errors series) idx))

      (some? (aget (:offset-us series) idx))
      (assoc :offset-us (aget (:offset-us series) idx))

      (some? (aget (:device-time-us series) idx))
      (assoc :device-time-us (aget (:device-time-us series) idx)))))

(defn- append-sample!
  [series {:keys [packet-msg value fields error offset-us device-time-us]}]
  (.push (:packet-msgs series) packet-msg)
  (.push (:values series) value)
  (.push (:fields series) fields)
  (.push (:errors series) error)
  (.push (:offset-us series) offset-us)
  (.push (:device-time-us series) device-time-us)
  series)

(defn sample->event
  "Rehydrate one compact sample into the event shape expected by existing views."
  [series sample]
  (when sample
    (cond-> (assoc sample
                   :sender (:sender series)
                   :name (:name series)
                   :type (:type series)
                   :print-filename (:print-filename series))
      (and (:device-time-us sample)
           (nil? (:device-time-str sample)))
      (assoc :device-time-str (u/format-device-time-us (:device-time-us sample))))))

(defn- sample-number
  [sample]
  (let [value (:value sample)]
    (when (te/finite-number? value)
      value)))

(defn- update-numeric-stats
  [series value]
  (let [sample-count (inc (or (:sample-count series) 0))
        first-value (if (contains? series :first-value)
                      (:first-value series)
                      value)
        sum (+ (or (:sum series) 0) value)]
    (assoc series
           :numeric? true
           :sample-count sample-count
           :sum sum
           :first-value first-value
           :latest-number value
           :min (if (number? (:min series)) (min (:min series) value) value)
           :max (if (number? (:max series)) (max (:max series) value) value))))

(defn- add-sample-to-series
  [series key sender metric-name metric-type print-filename sample]
  (let [value (sample-number sample)
        series (append-sample! (or series
                                   (empty-series key
                                                 sender
                                                 metric-name
                                                 metric-type
                                                 print-filename))
                               sample)
        series (-> series
                   (assoc :latest-idx (dec (sample-count series)))
                   (update :event-count (fnil inc 0)))]
    (if (number? value)
      (update-numeric-stats series value)
      series)))

(defn- add-sample
  [build sender print-filename metric-name metric-type sample]
  (let [key (metric-key sender metric-name)
        existing-series (get-in build [:series key])
        new-series? (nil? existing-series)]
    (cond-> (assoc-in build [:series key]
                      (add-sample-to-series existing-series
                                            key
                                            sender
                                            metric-name
                                            metric-type
                                            print-filename
                                            sample))
      true
      (update :event-count (fnil inc 0))

      new-series?
      (update :series-order conj key))))

(defn- field-name-str
  [field-name]
  (if (keyword? field-name)
    (name field-name)
    (str field-name)))

(defn- add-metric-samples
  [build sender print-filename packet-msg metric]
  (let [metric-name (:name metric)
        metric-type (te/metric-type-name (:type metric))
        fields (te/field-entries (:fields metric))
        structured-field-samples (if (= metric-type "structured")
                                   (->> fields
                                        (keep (fn [[field-name field-value]]
                                                (when-let [numeric-value (te/parse-numeric-like field-value)]
                                                  (let [field-name (field-name-str field-name)]
                                                    {:name (str metric-name "." field-name)
                                                     :sample {:packet-msg packet-msg
                                                              :value numeric-value
                                                              :device-time-us (:device-time-us metric)}}))))
                                        vec)
                                   [])]
    (reduce (fn [acc {:keys [name sample]}]
              (add-sample acc sender print-filename name "numeric" sample))
            (if (seq structured-field-samples)
              build
              (add-sample build
                          sender
                          print-filename
                          metric-name
                          metric-type
                          {:packet-msg packet-msg
                           :value (:value metric)
                           :fields (:fields metric)
                           :error (:error metric)
                           :offset-us (:offset-us metric)
                           :device-time-us (:device-time-us metric)}))
            structured-field-samples)))

(defn add-packet
  "Add one telemetry packet to an incremental replay index build."
  [build packet]
  (let [sender (:sender packet)
        metrics (:metrics packet)
        wall-time-str (:wall-time-str packet)
        packet-msg (packet-message build packet)
        received-at (:received-at packet)
        print-filename (or (te/extract-print-filename-from-metrics metrics)
                           (:print-filename build))
        packet-meta {:packet-msg packet-msg
                     :received-at received-at
                     :wall-time-str wall-time-str
                     :sender sender
                     :metric-count nil}
        packet-index (count (:packets build))
        event-count-before (:event-count build)
        updated-build (reduce (fn [acc metric]
                                (add-metric-samples acc
                                                    sender
                                                    print-filename
                                                    packet-msg
                                                    metric))
                              build
                              metrics)
        sample-count (- (:event-count updated-build) event-count-before)]
    (-> updated-build
        (update :packet-count inc)
        (update :packets conj (assoc packet-meta :metric-count sample-count))
        (assoc-in [:packet-index packet-msg] packet-index)
        (cond-> print-filename (assoc :print-filename print-filename))
        (update-range packet-msg))))

(defn- series-stats
  [series]
  (when (:numeric? series)
    {:latest (:latest-number series)
     :min (:min series)
     :max (:max series)
     :avg (/ (:sum series) (:sample-count series))
     :delta (- (:latest-number series) (:first-value series))
     :samples (:sample-count series)}))

(defn- finalize-series
  [series]
  (let [latest (sample->event series (sample-at-index series (:latest-idx series)))]
    (cond-> (assoc series :latest latest)
      (:numeric? series)
      (assoc :stats (series-stats series)))))

(defn memory-summary
  "Return count-based memory diagnostics for a replay index."
  [index]
  {:packets (:packet-count index)
   :metric-series (count (:metric-cards index))
   :metric-samples (:event-count index)
   :packet-metadata (count (:packets index))})

(defn finalize
  "Finalize an incremental replay index build for app-state."
  [build]
  (let [packet-range (when (and (number? (:min-msg build))
                                (number? (:max-msg build)))
                       {:min (:min-msg build)
                        :max (:max-msg build)})
        cards (->> (:series-order build)
                   (keep #(get-in build [:series %]))
                   (map finalize-series)
                   vec)
        index {:archive (:archive build)
               :print-filename (:print-filename build)
               :packet-count (:packet-count build)
               :event-count (:event-count build)
               :packet-range packet-range
               :packets (:packets build)
               :packet-index (:packet-index build)
               :metric-cards cards}]
    (assoc index :memory-summary (memory-summary index))))

(defn packet-at
  "Return packet metadata for packet-msg."
  [index packet-msg]
  (when (and index packet-msg)
    (get (:packets index)
         (get (:packet-index index) packet-msg))))

(defn last-sample-index-at
  "Return the last sample index at or before packet-msg."
  [series packet-msg]
  (let [packet-msgs (:packet-msgs series)
        sample-count (sample-count series)]
    (when (and (pos? sample-count) (number? packet-msg))
      (loop [lo 0
             hi (dec sample-count)
             best nil]
        (if (> lo hi)
          best
          (let [mid (js/Math.floor (/ (+ lo hi) 2))
                sample-msg (aget packet-msgs mid)]
            (if (and (number? sample-msg) (<= sample-msg packet-msg))
              (recur (inc mid) hi mid)
              (recur lo (dec mid) best))))))))

(defn sample-at-or-before
  "Return the last compact sample at or before packet-msg."
  [series packet-msg]
  (when-let [idx (last-sample-index-at series packet-msg)]
    (sample-at-index series idx)))

(defn event-at-or-before
  "Return the last event-shaped sample at or before packet-msg."
  [series packet-msg]
  (sample->event series (sample-at-or-before series packet-msg)))

(defn sample-window-at
  "Return up to limit compact samples ending at packet-msg."
  [series packet-msg limit]
  (let [idx (last-sample-index-at series packet-msg)]
    (if (number? idx)
      (let [end (inc idx)
            start (max 0 (- end limit))]
        (mapv #(sample-at-index series %) (range start end)))
      [])))

(defn numeric-window-values-at
  "Return up to limit numeric values ending at packet-msg."
  [series packet-msg limit]
  (let [values (:values series)
        idx (last-sample-index-at series packet-msg)]
    (if (number? idx)
      (let [end (inc idx)
            start (max 0 (- end limit))]
        (->> (range start end)
             (keep (fn [sample-idx]
                     (let [value (aget values sample-idx)]
                       (when (te/finite-number? value)
                         value))))
             vec))
      [])))

(defn event-window-at
  "Return up to limit event-shaped samples ending at packet-msg."
  [series packet-msg limit]
  (map #(sample->event series %)
       (sample-window-at series packet-msg limit)))

(defn events-at-packet
  "Return event-shaped samples that occurred exactly at packet-msg."
  [index packet-msg]
  (if-not (number? packet-msg)
    []
    (->> (:metric-cards index)
         (mapcat
         (fn [series]
            (let [packet-msgs (:packet-msgs series)
                  idx (last-sample-index-at series packet-msg)]
              (loop [idx idx
                     acc '()]
                (if (and (number? idx)
                         (>= idx 0)
                         (= packet-msg (aget packet-msgs idx)))
                  (recur (dec idx)
                         (conj acc (sample->event series (sample-at-index series idx))))
                  acc)))))
         (sort-by (fn [event]
                    [(str (:sender event))
                     (str (:name event))
                     (or (:device-time-us event) 0)]))
         vec)))
