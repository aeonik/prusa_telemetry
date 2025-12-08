(ns aeonik.prusa-telemetry
  (:require
   [aleph.udp :as udp]
   [aleph.tcp :as tcp]
   [clj-commons.byte-streams :as bs]
   [clojure.string :as str]
   [manifold.stream :as s]
   [manifold.deferred :as d])
  (:import [java.text SimpleDateFormat]))

;; ---- Tolerant value parsing ----
(defn parse-long?
  "Safely parse a long, returning nil on failure"
  [s]
  (try (Long/parseLong s)
       (catch Exception _ nil)))

(defn parse-double?
  "Safely parse a double, returning nil on failure"
  [s]
  (try (Double/parseDouble s)
       (catch Exception _ nil)))

(defn unquote-str
  "Remove surrounding quotes from a string if present"
  [s]
  (if (and (string? s)
           (>= (count s) 2)
           (= \" (first s))
           (= \" (last s)))
    (subs s 1 (dec (count s)))
    s))

(defn parse-value
  "Parse a value: long for '123i', double for '123.45', else string (quotes removed)"
  [s]
  (when s
    (let [s (str/trim s)]
      (cond
        ;; Integer with 'i' suffix
        (and (>= (count s) 2) (str/ends-with? s "i"))
        (or (parse-long? (subs s 0 (dec (count s))))
            (unquote-str s))  ; fallback to string if bad int
        ;; Try double, then fallback to string
        :else
        (or (parse-double? s)
            (unquote-str s))))))

(defn parse-kv-pairs
  "Parse comma-separated key=value pairs, handling numeric and string values"
  [s]
  (into {}
        (keep (fn [segment]
                (let [[k v] (str/split segment #"=" 2)]
                  (when (and (seq k) (some? v))
                    [k (parse-value v)]))))
        (str/split (or s "") #",")))

;; ---- Packet parsing ----
(def prelude-re #"(?:^|\s)msg=\d+,\s*tm=\d+,\s*v=\d+")

(defn parse-prelude
  "Extract msg, tm, and v from the prelude string"
  [s]
  (->> (str/split (or s "") #",")
       (map #(str/split % #"=" 2))
       (reduce (fn [m [k v]]
                 (case k
                   "msg" (assoc m :msg (parse-long? v))
                   "tm"  (assoc m :tm  (parse-long? v))
                   "v"   (assoc m :v   (parse-long? v))
                   m))
               {})))

(defn parse-metric-line
  "Parse a single metric line with proper type handling"
  [line base-tm-us]
  (let [line   (str/trim line)
        tokens (str/split line #"\s+")
        name   (first tokens)]
    (when (seq name)
      (cond
        ;; Simple numeric: name v=<value> <tick>
        (and (>= (count tokens) 3)
             (str/starts-with? (second tokens) "v="))
        (let [val-str (subs (second tokens) 2)
              val     (parse-value val-str)
              tick    (parse-long? (nth tokens 2))
              ts      (when (and tick base-tm-us)
                        (+ base-tm-us (* tick 1000)))]
          {:type :numeric
           :name name
           :value val
           :tick tick
           :device-time-us ts})
        ;; Error message: name error="..." <tick>
        (and (>= (count tokens) 3)
             (str/starts-with? (second tokens) "error="))
        (let [[_ msg] (re-find #"error=\"([^\"]*)\"" line)
              tick    (parse-long? (last tokens))
              ts      (when (and tick base-tm-us)
                        (+ base-tm-us (* tick 1000)))]
          {:type :error
           :name name
           :error (or msg "")
           :tick tick
           :device-time-us ts})
        ;; Structured fields: name k=v[,k=v,...] <tick>
        (>= (count tokens) 3)
        (let [tick   (parse-long? (last tokens))
              fields (parse-kv-pairs (second tokens))
              ts     (when (and tick base-tm-us)
                       (+ base-tm-us (* tick 1000)))]
          {:type :structured
           :name name
           :fields fields
           :tick tick
           :device-time-us ts})
        ;; Fallback for unknown format
        :else
        {:type :unknown
         :name name
         :raw line}))))

(defn parse-packet
  "Parse an Aleph UDP message into structured telemetry data"
  [{:keys [message sender]}]
  (try
    (let [txt        (bs/to-string message)
          lines      (str/split txt #"\r?\n")
          first-line (first lines)
          ;; Capture receive time
          received-at (java.util.Date.)
          ;; Extract prelude
          prelude-match (re-find prelude-re first-line)
          prelude-str   (when prelude-match (str/trim prelude-match))
          prelude       (parse-prelude prelude-str)
          base-tm-us    (:tm prelude)
          ;; Parse metrics (skip first line and empty lines)
          metrics (->> (rest lines)
                       (remove str/blank?)
                       (keep #(parse-metric-line % base-tm-us))
                       (into []))]
      {:sender      sender
       :received-at received-at
       :prelude     prelude
       :metrics     metrics
       :raw         txt})
    (catch Exception e
      {:error (.getMessage e)
       :raw   (try (bs/to-string message)
                   (catch Exception _ "Failed to decode message"))})))

;; ---- Transducers for processing ----

(def sort-metrics-xf
  "Transducer that sorts metrics by device time"
  (map (fn [packet]
         (update packet :metrics
                 #(sort-by :device-time-us %)))))

(def add-formatted-time-xf
  "Transducer that adds formatted timestamps"
  (map (fn [packet]
         (let [date-fmt (SimpleDateFormat. "HH:mm:ss.SSS")]
           (assoc packet
                  :wall-time-str (.format date-fmt (:received-at packet))
                  :formatted-metrics
                  (map (fn [m]
                         (assoc m :device-time-str
                                (when-let [us (:device-time-us m)]
                                  (let [seconds (/ us 1000000.0)
                                        minutes (int (/ seconds 60))
                                        secs    (mod seconds 60)]
                                    (format "%02d:%06.3f" minutes secs)))))
                       (:metrics packet)))))))

(def format-for-display-xf
  "Transducer that formats metrics for display"
  (map (fn [{:keys [wall-time-str formatted-metrics] :as packet}]
         (assoc packet :display-lines
                (map (fn [m]
                       (let [value-str (case (:type m)
                                         :numeric (if (number? (:value m))
                                                    (if (integer? (:value m))
                                                      (format "%d" (:value m))
                                                      (format "%.3f" (double (:value m))))
                                                    (str (:value m)))
                                         :error (str "ERROR: " (:error m))
                                         :structured (str/join ", "
                                                               (map (fn [[k v]]
                                                                      (str k "=" v))
                                                                    (:fields m)))
                                         "unknown")]
                         (format "[%s | %s] %-20s = %s"
                                 wall-time-str
                                 (or (:device-time-str m) "--------")
                                 (:name m)
                                 value-str)))
                     formatted-metrics)))))

;; ---- Server management ----

(defn start-telemetry-server
  "Start a UDP telemetry server with transducer pipeline.
   Returns {:socket .. :stream .. :processed .. :stop! (fn [])}
   Options:
   - :port (default 8514)"
  [{:keys [port]
    :or {port 8514}}]
  (let [socket @(udp/socket {:port port})
        ;; Raw parsed stream
        parsed-stream (s/stream 100)
        ;; Processed stream with transducers
        processed-stream (s/stream 100 (comp sort-metrics-xf
                                             add-formatted-time-xf
                                             format-for-display-xf))]

    ;; Connect socket -> parsed
    (s/connect-via
     socket
     (fn [msg]
       (let [parsed (parse-packet msg)]
         (s/put! parsed-stream parsed)))
     parsed-stream)

    ;; Connect parsed -> processed
    (s/connect parsed-stream processed-stream)

    ;; Ensure socket closes when stream is drained
    (s/on-drained parsed-stream #(s/close! socket))

    {:socket socket
     :stream parsed-stream
     :processed processed-stream
     :stop!  (fn []
               (s/close! processed-stream)
               (s/close! parsed-stream)
               (s/close! socket)
               ::stopped)}))

;; ---- Output handlers ----

(defn start-display-server
  "Start a TCP server for display output (works better than Unix socket for terminal).
   Connect with: nc localhost 9515 or telnet localhost 9515
   Returns {:server .. :stop! (fn [])}"
  [{:keys [port] :or {port 9515}}]
  (let [clients (atom #{})
        server (tcp/start-server
                (fn [stream info]
                  (swap! clients conj stream)
                  (s/on-closed stream #(swap! clients disj stream))
                  ;; Send initial clear screen
                  (s/put! stream "\033[2J\033[H")
                  (s/put! stream "Connected to Prusa Telemetry Display\n")
                  (s/put! stream "=====================================\n\n"))
                {:port port})]
    {:server server
     :clients clients
     :stop! (fn []
              (doseq [client @clients]
                (s/close! client))
              (.close server))}))

(defn connect-display
  "Connect processed stream to display server"
  [processed-stream display-server]
  (s/consume
   (fn [{:keys [display-lines]}]
     (let [clients @(:clients display-server)]
       (when (seq clients)
         (let [output (str "\r" (str/join "\n\r" display-lines)
                           "\n\r" (apply str (repeat 80 "=")))]
           (doseq [client clients]
             (s/put! client output))))))
   processed-stream))

;; ---- Simple console printer (for REPL) ----

(defn print-metrics
  "Simple printer that works in REPL (newlines instead of carriage returns)"
  [{:keys [display-lines]}]
  (doseq [line display-lines]
    (println line)))

;; ---- Main entry point ----

(defn -main
  "Run the telemetry server with console output"
  [& args]
  (let [port (or (some-> args first parse-long?) 8514)]
    (println (format "Starting Prusa telemetry server on UDP port %d..." port))
    (println "Display server on TCP port 9515 - connect with: nc localhost 9515")
    (println "Press Ctrl+C to exit\n")

    (let [srv (start-telemetry-server {:port port})
          display-srv (start-display-server {:port 9515})]

      ;; Connect to display server
      (connect-display (:processed srv) display-srv)

      ;; Also print to console for debugging
      (s/consume print-metrics (:processed srv))

      ;; Add shutdown hook
      (.addShutdownHook
       (Runtime/getRuntime)
       (Thread. (fn []
                  (println "\nShutting down...")
                  ((:stop! display-srv))
                  ((:stop! srv))
                  (Thread/sleep 100))))

      ;; Keep main thread alive
      @(promise))))

;; ---- REPL usage ----

(comment
  ;; Start server
  (def srv (start-telemetry-server {:port 8514}))

  (def processed-printer
    (future
      (s/consume
       print-metrics
       (:processed srv))))

;; Option 1: Simple printing (works in REPL with newlines)
  (def printer
    (future
      (s/consume print-metrics (:processed srv))))

  ;; Option 2: Start display server for proper carriage returns
  (def display-srv (start-display-server {:port 9515}))
  (connect-display (:processed srv) display-srv)
  ;; Then connect with: nc localhost 9515

  ;; Option 3: Raw stream access
  (def raw-printer
    (future
      (s/consume
       (fn [{:keys [metrics]}]
         (println "Got" (count metrics) "metrics"))
       (:stream srv))))

  ;; Option 4: Access processed stream with all transformations
  (def processed-tap (s/stream))
  (s/connect (:processed srv) processed-tap)

  ;; Take one processed packet
  @(s/try-take! processed-tap ::drained 5000 ::timeout)

  ;; Option 5: Custom transducer pipeline (filtering position metrics)
  (def custom-stream
    (s/stream 10))

  ;; Connect with filtering transformation
  (s/connect-via
   (:processed srv)
   (fn [packet]
     (let [filtered (update packet :metrics
                            #(filter (fn [m]
                                       (str/starts-with? (:name m) "pos_"))
                                     %))]
       (when (seq (:metrics filtered))
         (s/put! custom-stream filtered))))
   custom-stream)

  ;; Print the filtered results
  (def custom-printer
    (future
      (s/consume
       (fn [{:keys [metrics sender]}]
         (println (format "Position metrics from %s:" sender))
         (doseq [m metrics]
           (println (format "  %s = %s at tick %s"
                            (:name m)
                            (:value m)
                            (:tick m)))))
       custom-stream)))

  ;; Option 6: Aggregate stats with transducers
  (def stats-stream
    (s/stream 10))

  ;; Connect and transform to stats
  (s/connect-via
   (:processed srv)
   (fn [packet]
     (s/put! stats-stream
             {:metric-count (count (:metrics packet))
              :timestamp (System/currentTimeMillis)
              :sender (:sender packet)}))
   stats-stream)

  ;; Print the stats
  (def stats-printer
    (future
      (s/consume
       (fn [{:keys [metric-count timestamp sender]}]
         (println (format "[%s] Received %d metrics from %s"
                          (java.util.Date. timestamp)
                          metric-count
                          sender)))
       stats-stream)))

  ;; Alternative for Option 6: Running statistics
  (def running-stats (atom {:total-packets 0
                            :total-metrics 0
                            :by-sender {}}))

  (def stats-accumulator
    (future
      (s/consume
       (fn [{:keys [metrics sender]}]
         (swap! running-stats
                (fn [stats]
                  (-> stats
                      (update :total-packets inc)
                      (update :total-metrics + (count metrics))
                      (update-in [:by-sender sender :packets] (fnil inc 0))
                      (update-in [:by-sender sender :metrics] (fnil + 0) (count metrics))))))
       (:processed srv))))

  ;; View accumulated stats
  @running-stats

  ;; Clean shutdown
  ((:stop! srv))
  (when display-srv ((:stop! display-srv)))

  ;; Cancel futures
  (when (future? printer) (future-cancel printer))
  (when (future? raw-printer) (future-cancel raw-printer))
  (when (future? custom-printer) (future-cancel custom-printer))
  (when (future? stats-printer) (future-cancel stats-printer))
  (when (future? stats-accumulator) (future-cancel stats-accumulator))

  ;; Clean up streams
  (when custom-stream (s/close! custom-stream))
  (when stats-stream (s/close! stats-stream))

  ;; Restart
  (def srv (start-telemetry-server {:port 8514})))
