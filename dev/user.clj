(ns user
  "Development utilities for starting/stopping services in the REPL.
   
   Best practices:
   - Services are stored in atoms for easy access
   - Start/stop functions are idempotent (safe to call multiple times)
   - Services can be restarted individually or together
   - Auto-start on REPL connection (via Calva auto-evaluate)"
  (:require
   [aeonik.prusa-telemetry :as telemetry]
   [aeonik.web-server :as web]
   [aeonik.xtdb :as xtdb]
   [shadow.cljs.devtools.server :as server]
   [shadow.cljs.devtools.api :as shadow]
   [manifold.stream :as s]))

(defn cljs-repl
  "Connects to a given build-id. Defaults to `:app`."
  ([]
   (cljs-repl :app))
  ([build-id]
   (server/start!)
   (shadow/watch build-id)
   (shadow/nrepl-select build-id)))

;; Service state atoms
(defonce telemetry-server (atom nil))
(defonce web-server (atom nil))
(defonce xtdb-node (atom nil))

;; Packet inspection state
;; Buffer of recent packets at different stages (max 100 packets each)
(defonce packet-buffer-parsed (atom []))
(defonce packet-buffer-processed (atom []))
(defonce buffer-max-size 100)

;; Tap streams for non-destructive inspection
(defonce tap-parsed (atom nil))
(defonce tap-processed (atom nil))

;; Metric stream (unwrapped, time-ordered metrics)
(defonce metric-stream (atom nil))
(defonce metric-buffer (atom []))
(defonce metric-buffer-max-size 100000) ; Large buffer for long-term analytics

;; Persistent storage for long-term analysis (bounded vectors)
(defonce packet-store-max-size 1000000) ; Max 1M packets
(defonce metric-store-max-size 10000000) ; Max 10M metrics
(defonce packet-store (atom [])) ; Persistent storage for all processed packets
(defonce metric-store (atom [])) ; Persistent storage for all unwrapped metrics (sorted by device-time-us)
(defonce packet-registry (atom {})) ; packet-id -> full packet data

(defn- buffer-item!
  "Add an item to a buffer atom, maintaining max size by dropping oldest items."
  [buffer-atom item max-size]
  (swap! buffer-atom
         (fn [buf]
           (let [buf' (conj buf item)]
             (if (> (count buf') max-size)
               (vec (drop 1 buf'))
               buf')))))

(defn- append-bounded!
  "Append item to a bounded vector atom, dropping oldest items if exceeds max-size.
   Returns the new vector."
  [store-atom item max-size]
  (swap! store-atom
         (fn [store]
           (let [store' (conj store item)]
             (if (> (count store') max-size)
               (vec (drop (- (count store') max-size) store'))
               store')))))

(defn- binary-search-insertion-point
  "Find the insertion point for a metric in a sorted vector using binary search.
   Returns the index where the metric should be inserted to maintain sort order.
   Assumes vector is sorted by :device-time-us (ascending)."
  [store metric]
  (let [target-time (or (:device-time-us metric) 0)
        compare-fn (fn [idx]
                    (let [metric-time (or (:device-time-us (nth store idx)) 0)]
                      (compare target-time metric-time)))]
    (loop [low 0
           high (count store)]
      (if (>= low high)
        low
        (let [mid (bit-shift-right (+ low high) 1)
              cmp (compare-fn mid)]
          (cond
            (< cmp 0) (recur low mid)
            (> cmp 0) (recur (inc mid) high)
            :else mid))))))

(defn- append-metric-sorted!
  "Insert metric into metric-store at the correct position to maintain sort order.
   Uses binary search to find insertion point for efficiency.
   Metrics are sorted by device-time-us (ascending)."
  [metric]
  (swap! metric-store
         (fn [store]
           (let [new-device-time (:device-time-us metric)
                 store-size (count store)]
             (if (zero? store-size)
               ;; Empty store, just add it
               [metric]
               (let [last-metric (last store)
                     last-device-time (:device-time-us last-metric)]
                 (cond
                   ;; Fast path: metric is after the last one (most common case)
                   (and last-device-time new-device-time (>= new-device-time last-device-time))
                   (let [store' (conj store metric)]
                     (if (> (count store') metric-store-max-size)
                       (vec (drop 1 store'))
                       store'))
                   
                   ;; Need to insert in correct position
                   :else
                   (let [insertion-point (binary-search-insertion-point store metric)
                         store' (-> store
                                   (subvec 0 insertion-point)
                                   (into [metric])
                                   (into (subvec store insertion-point)))]
                     (if (> (count store') metric-store-max-size)
                       (vec (drop (- (count store') metric-store-max-size) store'))
                       store')))))))))

(defn- close-old-tap!
  "Close an old tap stream if it exists."
  [tap-atom]
  (when-let [old-tap @tap-atom]
    (s/close! old-tap)))

(defn- register-packet-in-registry!
  "Register full packet data in the packet registry if not already present.
   Also appends to persistent packet-store."
  [packet]
  (let [pid (telemetry/packet-id packet)]
    (when-not (contains? @packet-registry pid)
      ;; Store full packet in registry
      (swap! packet-registry assoc pid packet)
      ;; Also append to persistent packet-store
      (append-bounded! packet-store packet packet-store-max-size))))

(defn- setup-tap!
  "Set up a tap stream connected to a source stream.
   
   Options:
   - :stream-creator - function (source-stream) -> tap-stream, defaults to creating a regular stream
   - :item-processor - function (item) -> nil, processes each item (default: buffers to buffer-atom)
   - :connect-to-source - boolean, whether to connect source-stream to tap-stream (default: true)
   
   Returns the created tap stream."
  [source-stream tap-atom buffer-atom & {:keys [stream-creator item-processor connect-to-source]
                                         :or {stream-creator (fn [_] (s/stream 100))
                                              item-processor (fn [item] (buffer-item! buffer-atom item buffer-max-size))
                                              connect-to-source true}}]
  (close-old-tap! tap-atom)
  (let [tap-stream (stream-creator source-stream)]
    (reset! tap-atom tap-stream)
    (when connect-to-source
      (s/connect source-stream tap-stream))
    (s/consume item-processor tap-stream)
    tap-stream))

(defn- setup-parsed-packet-tap!
  "Set up tap for parsed packets."
  [source-stream]
  (setup-tap! source-stream tap-parsed packet-buffer-parsed
              :item-processor (fn [packet]
                                (when-not (:error packet)
                                  (buffer-item! packet-buffer-parsed packet buffer-max-size)))))

(defn- setup-processed-packet-tap!
  "Set up tap for processed packets.
   Appends to both inspection buffer and persistent packet-store."
  [source-stream]
  (setup-tap! source-stream tap-processed packet-buffer-processed
              :item-processor (fn [packet]
                               (when-not (:error packet)
                                 (buffer-item! packet-buffer-processed packet buffer-max-size)
                                 (register-packet-in-registry! packet)))))

(defn- setup-metric-stream-tap!
  "Set up tap for metric stream (unwrapped, time-ordered metrics).
   Appends to both inspection buffer and persistent metric-store with sanity check."
  [source-stream]
  (setup-tap! source-stream metric-stream metric-buffer
              :stream-creator (fn [_] (telemetry/create-metric-stream source-stream 2))
              :item-processor (fn [metric]
                               (buffer-item! metric-buffer metric metric-buffer-max-size)
                               (append-metric-sorted! metric))
              :connect-to-source false))

(defn- setup-packet-taps!
  "Set up tap streams to buffer packets for inspection.
   Called automatically when telemetry server starts."
  []
  (when-let [srv @telemetry-server]
    (setup-parsed-packet-tap! (:stream srv))
    (setup-processed-packet-tap! (:processed srv))
    (setup-metric-stream-tap! (:stream srv))
    (println "✓ Packet inspection taps set up")
    (println "✓ Metric stream (unwrapped, time-ordered) set up")))

(defn start-telemetry!
  "Start the telemetry server (UDP listener).
   Idempotent - safe to call multiple times.
   
   Options:
   - :port (default 8514)
   - :processed-buffer-size (default 200000)"
  ([]
   (start-telemetry! {:port 8514}))
  ([opts]
   (when (nil? @telemetry-server)
     (println "Starting telemetry server...")
     (reset! telemetry-server (telemetry/start-telemetry-server opts))
     (let [port (:port opts 8514)
           buffer-size (:processed-buffer-size opts 200000)]
       (println "✓ Telemetry server started on port" port
                "(processed buffer:" buffer-size "items)"))
    ;; Set up packet inspection taps
     (setup-packet-taps!))
   @telemetry-server))

(defn stop-telemetry!
  "Stop the telemetry server.
   Idempotent - safe to call multiple times."
  []
  (when-let [srv @telemetry-server]
    (println "Stopping telemetry server...")
    ((:stop! srv))
    (reset! telemetry-server nil)
    (println "✓ Telemetry server stopped")))

(defn start-xtdb!
  "Start XTDB node for storing telemetry metrics.
   Idempotent - safe to call multiple times.
   
   Options:
   - :in-memory? boolean if true, use in-memory storage (default: false)"
  ([]
   (start-xtdb! {}))
  ([opts]
   (when (nil? @xtdb-node)
     (println "Starting XTDB node...")
     (try
       (reset! xtdb-node (xtdb/start-node! opts))
       (println "✓ XTDB node started" (if (:in-memory? opts) "(in-memory)" ""))
       (catch Exception e
         (println "WARNING: Failed to start XTDB node:" (.getMessage e))
         (println "  This is optional - telemetry will still work without XTDB")
         (reset! xtdb-node nil))))
   @xtdb-node))

(defn stop-xtdb!
  "Stop the XTDB node.
   Idempotent - safe to call multiple times."
  []
  (when-let [node @xtdb-node]
    (println "Stopping XTDB node...")
    (try
      ((:stop! node))
      (catch Exception e
        (println "Error stopping XTDB node:" (.getMessage e))))
    (reset! xtdb-node nil)
    (println "✓ XTDB node stopped")))

(defn start-web!
  "Start the web server (HTTP + WebSocket).
   Idempotent - safe to call multiple times.
   Automatically starts telemetry server if not running."
  ([]
   (start-web! {:port 8080}))
  ([opts]
   (when (nil? @web-server)
     ;; Ensure telemetry server is running
     (when (nil? @telemetry-server)
       (start-telemetry!))

     (println "Starting web server...")
     (let [web-opts (assoc opts
                           :telemetry-stream (:processed @telemetry-server))]
       (reset! web-server (web/start-web-server web-opts)))
     (println "✓ Web server started on port" (:port opts 8080))
     (println "  WebSocket endpoint: ws://localhost:" (:port opts 8080) "/ws"))
   @web-server))

(defn stop-web!
  "Stop the web server.
   Idempotent - safe to call multiple times."
  []
  (when-let [srv @web-server]
    (println "Stopping web server...")
    ((:stop! srv))
    (reset! web-server nil)
    (println "✓ Web server stopped")))

(defn start!
  "Start all services (telemetry + web + optionally XTDB).
   Idempotent - safe to call multiple times.
   
   Options:
   - :telemetry-port (default 8514)
   - :telemetry-opts map of telemetry options (merged with port)
     - :processed-buffer-size (default 200000)
   - :web-port (default 8080)
   - :xtdb-opts map of XTDB options (if provided, starts XTDB)
     - :db-dir string (default: \"data/xtdb\")
     - :in-memory? boolean (default: false)"
  ([]
   (start! {:telemetry-port 8514 :web-port 8080}))
  ([{:keys [telemetry-port telemetry-opts web-port xtdb-opts]
     :or {telemetry-port 8514 web-port 8080}}]
   (let [telemetry-opts-final (merge {:port telemetry-port} telemetry-opts)]
     (start-telemetry! telemetry-opts-final))
   (when xtdb-opts
     (start-xtdb! xtdb-opts))
   (start-web! {:port web-port})
   {:telemetry @telemetry-server
    :web @web-server
    :xtdb @xtdb-node}))

(defn stop!
  "Stop all services.
   Idempotent - safe to call multiple times."
  []
  (stop-web!)
  (stop-telemetry!)
  (stop-xtdb!))

(defn restart!
  "Restart all services.
   Convenience function that stops then starts everything."
  ([]
   (restart! {:telemetry-port 8514 :web-port 8080}))
  ([opts]
   (stop!)
   (Thread/sleep 500) ; Brief pause to ensure cleanup
   (start! opts)))

(defn status
  "Show status of all services."
  []
  {:telemetry (if @telemetry-server :running :stopped)
   :web (if @web-server :running :stopped)
   :xtdb (if @xtdb-node :running :stopped)})

;; ---- Packet Inspection Functions ----

(defn clear-buffers!
  "Clear all packet buffers and metric buffers."
  []
  (reset! packet-buffer-parsed [])
  (reset! packet-buffer-processed [])
  (reset! metric-buffer [])
  (reset! packet-registry {})
  (println "All buffers cleared"))

(defn buffer-stats
  "Get statistics about buffered packets, metrics, and persistent stores."
  []
  {:inspection-buffers
   {:parsed-count (count @packet-buffer-parsed)
    :processed-count (count @packet-buffer-processed)
    :metric-count (count @metric-buffer)
    :max-size buffer-max-size}
   :persistent-stores
   {:packet-store-count (count @packet-store)
    :packet-store-max-size packet-store-max-size
    :metric-store-count (count @metric-store)
    :metric-store-max-size metric-store-max-size
    :packet-registry-size (count @packet-registry)}})

(defn peek-parsed
  "Get the most recent N parsed packets (default 1).
   Returns packets in order from oldest to newest."
  ([]
   (peek-parsed 1))
  ([n]
   (take-last n @packet-buffer-parsed)))

(defn peek-processed
  "Get the most recent N processed packets (default 1).
   Returns packets in order from oldest to newest."
  ([]
   (peek-processed 1))
  ([n]
   (take-last n @packet-buffer-processed)))

(defn sample-parsed
  "Sample one packet from the parsed stream (non-blocking).
   Returns the packet or ::timeout if none available within timeout-ms (default 1000)."
  ([]
   (sample-parsed 1000))
  ([timeout-ms]
   (when-let [tap @tap-parsed]
     (let [result @(s/try-take! tap ::drained timeout-ms ::timeout)]
       (when (not= result ::drained)
         result)))))

(defn sample-processed
  "Sample one packet from the processed stream (non-blocking).
   Returns the packet or ::timeout if none available within timeout-ms (default 1000)."
  ([]
   (sample-processed 1000))
  ([timeout-ms]
   (when-let [tap @tap-processed]
     (let [result @(s/try-take! tap ::drained timeout-ms ::timeout)]
       (when (not= result ::drained)
         result)))))

(defn latest-parsed
  "Get the latest parsed packet from buffer, or nil if buffer is empty."
  []
  (last @packet-buffer-parsed))

(defn latest-processed
  "Get the latest processed packet from buffer, or nil if buffer is empty."
  []
  (last @packet-buffer-processed))

(defn inspect-parsed
  "Inspect a parsed packet with formatted output.
   If no packet provided, uses the latest from buffer."
  ([]
   (inspect-parsed (latest-parsed)))
  ([packet]
   (if packet
     (do
       (println "=== Parsed Packet ===")
       (println "Sender:" (:sender packet))
       (println "Received at:" (:received-at packet))
       (println "Prelude:" (:prelude packet))
       (println "Metrics count:" (count (:metrics packet)))
       (println "\nMetrics:")
       (doseq [m (:metrics packet)]
         (println "  -" (:name m) ":" (pr-str (select-keys m [:type :value :error :fields :offset-ms :device-time-us]))))
       (when (:error packet)
         (println "\nParse error:" (:error packet)))
       (when (:raw packet)
         (println "\nRaw data (first 200 chars):")
         (println (subs (:raw packet) 0 (min 200 (count (:raw packet))))))
       packet)
     (println "No parsed packets available"))))

(defn inspect-processed
  "Inspect a processed packet with formatted output.
   If no packet provided, uses the latest from buffer."
  ([]
   (inspect-processed (latest-processed)))
  ([packet]
   (if packet
     (do
       (println "=== Processed Packet ===")
       (println "Sender:" (:sender packet))
       (println "Received at:" (:received-at packet))
       (println "Wall time:" (:wall-time-str packet))
       (println "Prelude:" (:prelude packet))
       (println "Metrics count:" (count (:metrics packet)))
       (println "\nDisplay lines:")
       (doseq [line (:display-lines packet)]
         (println "  " line))
       (println "\nMetrics (first 10):")
       (doseq [m (take 10 (:metrics packet))]
         (println "  -" (:name m)
                  "| type:" (:type m)
                  "| value:" (pr-str (:value m))
                  "| device-time:" (:device-time-str m)))
       (when (> (count (:metrics packet)) 10)
         (println "  ... and" (- (count (:metrics packet)) 10) "more"))
       packet)
     (println "No processed packets available"))))

(defn list-metric-names
  "List all unique metric names from recent processed packets.
   Optionally filter by sender."
  ([]
   (list-metric-names nil))
  ([sender-filter]
   (let [packets (if sender-filter
                   (filter #(= (str (:sender %)) (str sender-filter)) @packet-buffer-processed)
                   @packet-buffer-processed)]
     (->> packets
          (mapcat :metrics)
          (map :name)
          distinct
          sort))))

(defn find-metrics
  "Find all metrics with a given name from recent processed packets.
   Optionally filter by sender."
  ([metric-name]
   (find-metrics metric-name nil))
  ([metric-name sender-filter]
   (let [packets (if sender-filter
                   (filter #(= (str (:sender %)) (str sender-filter)) @packet-buffer-processed)
                   @packet-buffer-processed)]
     (->> packets
          (mapcat (fn [p]
                    (map #(assoc % :packet-sender (:sender p)
                                 :packet-time (:wall-time-str p))
                         (filter #(= (:name %) metric-name) (:metrics p)))))
          (sort-by :device-time-us)))))

(defn packet-stats
  "Get statistics about recent packets."
  []
  (let [parsed @packet-buffer-parsed
        processed @packet-buffer-processed
        parsed-by-sender (group-by :sender parsed)
        processed-by-sender (group-by :sender processed)]
    {:parsed {:total (count parsed)
              :by-sender (into {} (map (fn [[sender packets]]
                                         [sender {:count (count packets)
                                                  :total-metrics (reduce + (map #(count (:metrics %)) packets))}])
                                       parsed-by-sender))}
     :processed {:total (count processed)
                 :by-sender (into {} (map (fn [[sender packets]]
                                            [sender {:count (count packets)
                                                     :total-metrics (reduce + (map #(count (:metrics %)) packets))}])
                                          processed-by-sender))}
     :unique-metric-names (count (list-metric-names))}))

;; ---- Metric Stream Statistics (Unwrapped Metrics) ----

(defn- calculate-metric-stats
  "Helper function to calculate statistics from a collection of metrics.
   Returns a map with total-metrics, unique-names, by-name, and time-range."
  [metrics include-span-us?]
  (let [by-name (group-by :name metrics)
        all-times (keep :device-time-us metrics)]
    {:total-metrics (count metrics)
     :unique-names (count (keys by-name))
     :by-name (into {} (map (fn [[name metric-list]]
                             (let [times (keep :device-time-us metric-list)]
                               (cond-> {:count (count metric-list)
                                        :min-time (when (seq times) (apply min times))
                                        :max-time (when (seq times) (apply max times))}
                                 include-span-us?
                                 (assoc :span-us (when (and (seq times) (> (count times) 1))
                                                  (- (apply max times) (apply min times)))))))
                           by-name))
     :time-range (when (seq all-times)
                  {:min-time (apply min all-times)
                   :max-time (apply max all-times)
                   :span-us (- (apply max all-times) (apply min all-times))})}))

(defn processed-stream-buffer-stats
  "Get statistics about metrics currently in the metric buffer (last N metrics).
   Analyzes the inspection buffer, not the full persistent store."
  []
  (calculate-metric-stats @metric-buffer false))

(defn processed-stream-buffer-analysis
  "Get detailed analysis of metrics currently in the metric buffer.
   Includes time spans for each metric name."
  []
  (calculate-metric-stats @metric-buffer true))

(defn reset-processed-stream-stats!
  "Clear the metric buffer and persistent stores."
  []
  (reset! metric-buffer [])
  (reset! metric-store [])
  (reset! packet-store [])
  (reset! packet-registry {})
  (println "Metric buffer and persistent stores cleared"))

(defn processed-packet-stats
  "Get statistics about ALL metrics in the persistent metric-store.
   Analyzes the entire persistent store, not just the inspection buffer."
  []
  (calculate-metric-stats @metric-store false))

(defn processed-packet-metric-stats
  "Get detailed statistics about ALL metrics in the persistent metric-store.
   Includes time spans for each metric name."
  []
  (calculate-metric-stats @metric-store true))

(defn processed-packet-metric-by-name
  "Get statistics for a specific metric name from the persistent metric-store."
  [metric-name]
  (let [metrics @metric-store
        matching-metrics (filter #(= (:name %) metric-name) metrics)
        times (keep :device-time-us matching-metrics)]
    (when (seq matching-metrics)
      {:metric-name metric-name
       :count (count matching-metrics)
       :min-time (when (seq times) (apply min times))
       :max-time (when (seq times) (apply max times))
       :span-us (when (and (seq times) (> (count times) 1))
                 (- (apply max times) (apply min times)))
       :values (distinct (keep :value matching-metrics))
       :sample-values (take 10 (distinct (keep :value matching-metrics)))})))

(defn processed-packet-summary
  "Get summary statistics about ALL metrics in the persistent metric-store.
   Returns top 10 metrics by count."
  []
  (let [metrics @metric-store
        by-name (group-by :name metrics)]
    {:metric-count (count metrics)
     :unique-names (count (keys by-name))
     :top-metrics (->> by-name
                       (map (fn [[name metric-list]] [name (count metric-list)]))
                       (sort-by second >)
                       (take 10)
                       (into {}))}))

(defn- calculate-store-projections
  "Calculate projections for store capacity and fill time.
   Returns map with estimated fill time, remaining capacity, etc."
  [current-count max-size items-per-second]
  (let [fill-percentage (when (and max-size (> max-size 0))
                         (* (/ (double current-count) max-size) 100.0))
        remaining-capacity (when (and max-size current-count)
                            (- max-size current-count))
        time-until-full-hours (when (and items-per-second remaining-capacity
                                        (> items-per-second 0) (> remaining-capacity 0))
                               (/ (/ remaining-capacity items-per-second) 3600.0))
        days-until-full (when time-until-full-hours
                         (/ time-until-full-hours 24.0))
        estimated-memory-mb (when current-count
                             ;; Rough estimate: ~2KB per packet/metric
                             (/ (* current-count 2.0) 1024.0))]
    {:fill-percentage fill-percentage
     :remaining-capacity remaining-capacity
     :time-until-full-hours time-until-full-hours
     :days-until-full days-until-full
     :estimated-memory-mb estimated-memory-mb}))

(defn- calculate-fill-rate
  "Calculate fill rate (items per second) based on time range and count.
   Returns items per second or nil if insufficient data."
  [metrics]
  (when (>= (count metrics) 2)
    (let [first-metric (first metrics)
          last-metric (last metrics)
          first-time (:device-time-us first-metric)
          last-time (:device-time-us last-metric)]
      (when (and first-time last-time (> last-time first-time))
        (let [time-span-seconds (/ (- last-time first-time) 1000000.0)]
          (when (> time-span-seconds 0)
            (/ (count metrics) time-span-seconds)))))))

(defn packet-store-analysis
  "Get comprehensive analysis of the packet-store including fill predictions.
   Shows current state, fill rate, and projections for when it will fill up."
  []
  (let [packets @packet-store
        current-count (count packets)
        now (System/currentTimeMillis)
        ;; Calculate fill rate from packet received-at times
        fill-rate-per-second (when (>= current-count 2)
                              (let [first-packet (first packets)
                                    last-packet (last packets)
                                    first-time (when first-packet
                                                (.getTime (:received-at first-packet)))
                                    last-time (when last-packet
                                               (.getTime (:received-at last-packet)))]
                                (when (and first-time last-time (> last-time first-time))
                                  (let [time-span-seconds (/ (- last-time first-time) 1000.0)]
                                    (when (> time-span-seconds 0)
                                      (/ current-count time-span-seconds))))))
        projections (calculate-store-projections current-count
                                                 packet-store-max-size
                                                 fill-rate-per-second)]
    {:store-configuration
     {:current-count current-count
      :max-size packet-store-max-size
      :fill-percentage (:fill-percentage projections)}
     :fill-rate
     {:packets-per-second fill-rate-per-second
      :packets-per-hour (when fill-rate-per-second (* fill-rate-per-second 3600.0))
      :packets-per-day (when fill-rate-per-second (* fill-rate-per-second 86400.0))}
     :projections
     {:remaining-capacity (:remaining-capacity projections)
      :time-until-full-hours (:time-until-full-hours projections)
      :days-until-full (:days-until-full projections)
      :estimated-memory-mb (:estimated-memory-mb projections)}
     :time-range
     (when (>= current-count 1)
       (let [first-packet (first packets)
             last-packet (last packets)]
         {:first-packet-time (:received-at first-packet)
          :last-packet-time (:received-at last-packet)
          :span-minutes (when (and first-packet last-packet)
                         (let [first-time (.getTime (:received-at first-packet))
                               last-time (.getTime (:received-at last-packet))]
                           (when (> last-time first-time)
                             (/ (- last-time first-time) 60000.0))))}))}))

(defn metric-store-analysis
  "Get comprehensive analysis of the metric-store including fill predictions.
   Shows current state, fill rate, and projections for when it will fill up."
  []
  (let [metrics @metric-store
        current-count (count metrics)
        fill-rate-per-second (calculate-fill-rate metrics)
        projections (calculate-store-projections current-count
                                                 metric-store-max-size
                                                 fill-rate-per-second)]
    {:store-configuration
     {:current-count current-count
      :max-size metric-store-max-size
      :fill-percentage (:fill-percentage projections)}
     :fill-rate
     {:metrics-per-second fill-rate-per-second
      :metrics-per-hour (when fill-rate-per-second (* fill-rate-per-second 3600.0))
      :metrics-per-day (when fill-rate-per-second (* fill-rate-per-second 86400.0))}
     :projections
     {:remaining-capacity (:remaining-capacity projections)
      :time-until-full-hours (:time-until-full-hours projections)
      :days-until-full (:days-until-full projections)
      :estimated-memory-mb (:estimated-memory-mb projections)}
     :time-range
     (when (>= current-count 1)
       (let [first-metric (first metrics)
             last-metric (last metrics)
             first-time (:device-time-us first-metric)
             last-time (:device-time-us last-metric)]
         {:first-metric-time-us first-time
          :last-metric-time-us last-time
          :span-minutes (when (and first-time last-time (> last-time first-time))
                         (/ (- last-time first-time) 60000000.0))}))}))

;; ---- Unwrapped Metric Inspection Functions ----

(defn peek-metrics
  "Get the most recent N unwrapped metrics (default 10).
   Returns metrics in time order (oldest to newest)."
  ([]
   (peek-metrics 10))
  ([n]
   (take-last n @metric-buffer)))

(defn latest-metric
  "Get the latest unwrapped metric from buffer, or nil if buffer is empty."
  []
  (last @metric-buffer))

(defn inspect-metric
  "Inspect an unwrapped metric with formatted output.
   If no metric provided, uses the latest from buffer."
  ([]
   (inspect-metric (latest-metric)))
  ([metric]
   (if metric
     (do
       (println "=== Unwrapped Metric ===")
       (println "Name:" (:name metric))
       (println "Type:" (:type metric))
       (println "Value:" (pr-str (:value metric)))
       (when (:error metric)
         (println "Error:" (:error metric)))
       (when (:fields metric)
         (println "Fields:" (:fields metric)))
       (println "Device time (us):" (:device-time-us metric))
       (when (:offset-ms metric)
         (println "Offset (ms):" (:offset-ms metric)))
       (when-let [pid (:packet-id metric)]
         (println "\nPacket provenance:")
         (println "  Packet ID:" pid)
         (when-let [pkt-info (get @packet-registry pid)]
           (println "  Sender:" (:sender pkt-info))
           (println "  Received at:" (:received-at pkt-info))
           (println "  Prelude:" (:prelude pkt-info)))
         (println "  Index in packet:" (:idx metric)))
       metric)
     (println "No unwrapped metrics available"))))

(defn find-unwrapped-metrics
  "Find all unwrapped metrics with a given name from the persistent metric-store.
   Returns metrics sorted by device-time-us (already sorted in store)."
  [metric-name]
  (->> @metric-store
       (filter #(= (:name %) metric-name))))

(defn list-unwrapped-metric-names
  "List all unique metric names from the persistent metric-store."
  []
  (->> @metric-store
       (map :name)
       distinct
       sort))

(defn get-packet-by-id
  "Get full packet data by packet-id from packet-registry."
  [packet-id]
  (get @packet-registry packet-id))

(defn get-metrics-by-packet-id
  "Get all metrics from a specific packet by packet-id.
   Returns metrics sorted by device-time-us (already sorted in store)."
  [packet-id]
  (->> @metric-store
       (filter #(= (:packet-id %) packet-id))))

(defn get-metrics-by-time-range
  "Get all metrics within a time range (device-time-us).
   Returns metrics sorted by device-time-us (already sorted in store)."
  [start-time-us end-time-us]
  (->> @metric-store
       (filter (fn [m]
                (when-let [device-time (:device-time-us m)]
                  (and (>= device-time start-time-us)
                       (<= device-time end-time-us)))))))

(defn metric-stats
  "Get statistics about unwrapped metrics from the persistent metric-store."
  []
  (let [metrics @metric-store
        by-name (group-by :name metrics)
        by-packet-id (group-by :packet-id metrics)]
    {:total-metrics (count metrics)
     :unique-names (count (keys by-name))
     :unique-packets (count (keys by-packet-id))
     :by-name (into {} (map (fn [[name ms]]
                              [name {:count (count ms)
                                     :min-time (when (seq ms)
                                                 (apply min (keep :device-time-us ms)))
                                     :max-time (when (seq ms)
                                                 (apply max (keep :device-time-us ms)))}])
                            by-name))}))

(defn sample-metric
  "Sample one metric from the metric stream (non-blocking).
   Returns the metric or ::timeout if none available within timeout-ms (default 1000)."
  ([]
   (sample-metric 1000))
  ([timeout-ms]
   (when-let [stream @metric-stream]
     (let [result @(s/try-take! stream ::drained timeout-ms ::timeout)]
       (when (not= result ::drained)
         result)))))

(defn clear-metric-buffer!
  "Clear the unwrapped metric buffer, persistent metric-store, and packet registry."
  []
  (reset! metric-buffer [])
  (reset! metric-store [])
  (reset! packet-registry {})
  (println "Metric buffer, metric-store, and packet registry cleared"))

(defn auto-start!
  "Auto-start backend services (telemetry + web) in the background.
   Safe to call during namespace load - won't block."
  []
  (future
    (Thread/sleep 1000) ; Brief delay to let REPL fully initialize
    (try
      (start!)
      (catch Exception e
        (println "Warning: Auto-start failed:" (.getMessage e))
        (println "You can manually start with: (user/start!)")))))

;; Auto-start backend services when REPL connects (runs async, won't block jack-in)
;; This starts the telemetry server (UDP port 8514) and web server (HTTP port 8080)
;; The web server provides the API endpoints and WebSocket that the frontend needs
(auto-start!)

;; Example usage in REPL:
(comment
  ;; Start shadow-cljs server (required for ClojureScript REPL)

  ;; Start everything (default ports)
  (start!)

  ;; Start with custom ports
  (start! {:telemetry-port 8514 :web-port 8080})

  ;; Start services individually
  (start-telemetry!)
  (start-web!)

  ;; Check status
  (status)

  ;; Stop services
  (stop!)
  ;; Restart everything
  (restart!)

  ;; Start XTDB (optional)
  (start-xtdb! {:in-memory? true})  ; In-memory for testing

  ;; Start everything with XTDB
  (start! {:xtdb-opts {:in-memory? true}})

  ;; Access service instances
  @telemetry-server
  @web-server
  @xtdb-node

  ;; Query XTDB examples (after starting XTDB)
  (require '[xtdb.api :as xt] '[aeonik.xtdb :as xtdb])

  ;; Query all metrics (XTDB v2 queries directly on connectable)
  (xtdb/query-metrics (:node @xtdb-node))

  ;; Query specific metric name
  (xtdb/query-metrics (:node @xtdb-node) {:name "temp"})

  ;; Query recent metrics (last hour)
  (let [one-hour-ago (- (System/currentTimeMillis) (* 60 60 1000))]
    (xtdb/query-metrics (:node @xtdb-node) {:since-ms one-hour-ago}))

  ;; Get all unique metric names
  (xtdb/query-metric-names (:node @xtdb-node))

  ;; Get total count
  (xtdb/query-metric-count (:node @xtdb-node))

  ;; Custom query using XTDB directly (XTDB v2 queries on connectable)
  (xt/q (:node @xtdb-node)
        '{:find [name value device-time-us]
          :where [[e :telemetry/name name]
                  [e :telemetry/value value]
                  [e :telemetry/device-time-us device-time-us]
                  [(= name "temp")]]
          :limit 10})

  ;; ---- Packet Inspection Examples ----

  ;; Get buffer statistics
  (buffer-stats)

  ;; Inspect latest parsed packet
  (inspect-parsed)

  ;; Inspect latest processed packet
  (inspect-processed)

  ;; Get last 5 parsed packets
  (peek-parsed 5)

  ;; Get last 5 processed packets
  (peek-processed 5)

  ;; Sample one packet from parsed stream (non-blocking, waits up to 1 second)
  (sample-parsed)

  ;; Sample one packet from processed stream (non-blocking, waits up to 2 seconds)
  (sample-processed 2000)

  ;; Get latest parsed packet
  (latest-parsed)

  ;; Get latest processed packet
  (latest-processed)

  ;; List all unique metric names from recent packets
  (list-metric-names)

  ;; List metric names from a specific sender
  (list-metric-names "/192.168.1.100:8514")

  ;; Find all metrics with a specific name
  (find-metrics "temp")

  ;; Find metrics by name from a specific sender
  (find-metrics "temp" "/192.168.1.100:8514")

  ;; Get packet statistics
  (packet-stats)

  ;; Clear packet buffers
  (clear-buffers!)

  ;; Inspect a specific packet
  (let [pkt (latest-processed)]
    (inspect-processed pkt))

  ;; Compare parsed vs processed for same packet
  (let [parsed-pkt (latest-parsed)
        processed-pkt (latest-processed)]
    (println "=== PARSED ===")
    (inspect-parsed parsed-pkt)
    (println "\n=== PROCESSED ===")
    (inspect-processed processed-pkt))

  ;; ---- Unwrapped Metric Inspection Examples ----

  ;; Get last 10 unwrapped metrics (time-ordered)
  (peek-metrics 10)

  ;; Get latest unwrapped metric
  (latest-metric)

  ;; Inspect latest unwrapped metric (shows packet provenance)
  (inspect-metric)

  ;; Find all metrics with a specific name (time-ordered)
  (find-unwrapped-metrics "temp")

  ;; List all unique metric names from unwrapped metrics
  (list-unwrapped-metric-names)

  ;; Get packet metadata by packet-id
  (let [metric (latest-metric)
        pid (:packet-id metric)]
    (get-packet-by-id pid))

  ;; Get statistics about unwrapped metrics (from persistent metric-store)
  (metric-stats)

  ;; Get all metrics from a specific packet
  (let [metric (latest-metric)
        pid (:packet-id metric)]
    (get-metrics-by-packet-id pid))

  ;; Get metrics within a time range (device-time-us)
  (let [start-time 690000000000
        end-time 700000000000]
    (get-metrics-by-time-range start-time end-time))

  ;; ---- Processed Stream Buffer Analysis ----

  ;; Get statistics about metrics in inspection buffer (last N metrics)
  (processed-stream-buffer-stats)

  ;; Get detailed analysis of metrics in inspection buffer
  (processed-stream-buffer-analysis)

  ;; Reset persistent stores
  (reset-processed-stream-stats!)

  ;; ---- Persistent Store Statistics (All Metrics) ----

  ;; Get statistics about ALL metrics in persistent metric-store
  (processed-packet-stats)

  ;; Get detailed metric statistics from persistent metric-store
  (processed-packet-metric-stats)

  ;; Get statistics for a specific metric name from persistent metric-store
  (processed-packet-metric-by-name "cmdcnt")

  ;; Get summary of ALL metrics in persistent metric-store
  (processed-packet-summary)

  ;; Get buffer and store statistics
  (buffer-stats)

  ;; ---- Persistent Store Analysis ----

  ;; Analyze packet-store with fill predictions
  (packet-store-analysis)

  ;; Analyze metric-store with fill predictions
  (metric-store-analysis)

  ;; Sample one metric from metric stream (non-blocking)
  (sample-metric)

  ;; Clear metric buffer
  (clear-metric-buffer!)

  ;; Inspect a specific unwrapped metric
  (let [m (latest-metric)]
    (inspect-metric m))

  ;; Compare packet vs unwrapped metric
  (let [pkt (latest-processed)
        metric (latest-metric)]
    (println "=== PACKET ===")
    (inspect-processed pkt)
    (println "\n=== UNWRAPPED METRIC ===")
    (inspect-metric metric)))
