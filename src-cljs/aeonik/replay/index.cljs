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

(defn- metric-sample
  [wall-time-str wall-time-ms packet-msg received-at metric]
  (cond-> {:value (:value metric)
           :fields (:fields metric)
           :error (:error metric)
           :type (te/metric-type-name (:type metric))
           :offset-us (:offset-us metric)
           :offset-ms (:offset-ms metric)
           :device-time-us (:device-time-us metric)
           :device-time-str (:device-time-str metric)
           :wall-time-str wall-time-str
           :wall-time-ms wall-time-ms
           :packet-msg packet-msg
           :received-at received-at}
    (:has-numeric-fields? metric)
    (assoc :has-numeric-fields? true)))

(defn sample->event
  "Rehydrate one compact sample into the event shape expected by existing views."
  [series sample]
  (when sample
    (assoc sample
           :sender (:sender series)
           :name (:name series)
           :print-filename (:print-filename series))))

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
        series (-> (or series {})
                   (assoc :key key
                          :sender sender
                          :name metric-name
                          :type metric-type
                          :print-filename print-filename
                          :latest-sample sample)
                   (update :event-count (fnil inc 0))
                   (update :samples (fnil conj []) sample))]
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
      new-series?
      (update :series-order conj key))))

(defn- field-name-str
  [field-name]
  (if (keyword? field-name)
    (name field-name)
    (str field-name)))

(defn- add-metric-samples
  [build sender print-filename wall-time-str wall-time-ms packet-msg received-at metric]
  (let [metric-name (:name metric)
        metric-type (te/metric-type-name (:type metric))
        fields (te/field-entries (:fields metric))
        structured-field-samples (when (= metric-type "structured")
                                   (keep (fn [[field-name field-value]]
                                           (when-let [numeric-value (te/parse-numeric-like field-value)]
                                             (let [field-name (field-name-str field-name)]
                                               {:name (str metric-name "." field-name)
                                                :sample (-> (metric-sample wall-time-str
                                                                           wall-time-ms
                                                                           packet-msg
                                                                           received-at
                                                                           metric)
                                                            (assoc :value numeric-value
                                                                   :fields nil
                                                                   :type "numeric"
                                                                   :parent-name metric-name
                                                                   :field-name field-name
                                                                   :synthetic-field? true)
                                                            (dissoc :error))})))
                                         fields))
        base-metric (cond-> metric
                      (seq structured-field-samples)
                      (assoc :has-numeric-fields? true))
        base-sample (metric-sample wall-time-str wall-time-ms packet-msg received-at base-metric)]
    (reduce (fn [acc {:keys [name sample]}]
              (add-sample acc sender print-filename name "numeric" sample))
            (add-sample build sender print-filename metric-name metric-type base-sample)
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
        wall-time-ms (u/parse-wall-time-str wall-time-str)
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
                                                    wall-time-str
                                                    wall-time-ms
                                                    packet-msg
                                                    received-at
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
  (let [latest (sample->event series (:latest-sample series))]
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
  (let [samples (:samples series)]
    (when (and (seq samples) (number? packet-msg))
      (loop [lo 0
             hi (dec (count samples))
             best nil]
        (if (> lo hi)
          best
          (let [mid (js/Math.floor (/ (+ lo hi) 2))
                sample (nth samples mid)
                sample-msg (:packet-msg sample)]
            (if (and (number? sample-msg) (<= sample-msg packet-msg))
              (recur (inc mid) hi mid)
              (recur lo (dec mid) best))))))))

(defn sample-at-or-before
  "Return the last compact sample at or before packet-msg."
  [series packet-msg]
  (when-let [idx (last-sample-index-at series packet-msg)]
    (nth (:samples series) idx)))

(defn event-at-or-before
  "Return the last event-shaped sample at or before packet-msg."
  [series packet-msg]
  (sample->event series (sample-at-or-before series packet-msg)))

(defn sample-window-at
  "Return up to limit compact samples ending at packet-msg."
  [series packet-msg limit]
  (let [samples (:samples series)
        idx (last-sample-index-at series packet-msg)]
    (if (and (number? idx) (vector? samples))
      (let [end (inc idx)
            start (max 0 (- end limit))]
        (subvec samples start end))
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
            (let [samples (:samples series)
                  idx (last-sample-index-at series packet-msg)]
              (loop [idx idx
                     acc '()]
                (if (and (number? idx)
                         (>= idx 0)
                         (= packet-msg (:packet-msg (nth samples idx))))
                  (recur (dec idx)
                         (conj acc (sample->event series (nth samples idx))))
                  acc)))))
         (sort-by (fn [event]
                    [(str (:sender event))
                     (str (:name event))
                     (or (:device-time-us event) 0)]))
         vec)))
