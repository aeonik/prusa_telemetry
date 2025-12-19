(ns aeonik.prusa-telemetry
  (:require
   [aleph.udp :as udp]
   [aleph.tcp :as tcp]
   [clj-commons.byte-streams :as bs]
   [clojure.string :as str]
   [manifold.stream :as s]
   [manifold.bus :as bus])
  (:import [java.text SimpleDateFormat]))

;; ============================================================
;; Parsing (pure functions)
;; ============================================================

(defn parse-long? [s]
  (try (Long/parseLong s) (catch Exception _ nil)))

(defn parse-double? [s]
  (try (Double/parseDouble s) (catch Exception _ nil)))

(defn unquote-str [s]
  (if (and (string? s) (>= (count s) 2) (= \" (first s)) (= \" (last s)))
    (subs s 1 (dec (count s)))
    s))

(defn parse-value [s]
  (when s
    (let [s (str/trim s)]
      (if (and (>= (count s) 2) (str/ends-with? s "i"))
        (or (parse-long? (subs s 0 (dec (count s)))) (unquote-str s))
        (or (parse-double? s) (unquote-str s))))))

(defn parse-kv-pairs [s]
  (into {}
        (keep (fn [seg]
                (let [[k v] (str/split seg #"=" 2)]
                  (when (and (seq k) (some? v)) [k (parse-value v)]))))
        (str/split (or s "") #",")))

(def prelude-re #"(?:^|\s)msg=\d+,\s*tm=\d+,\s*v=\d+")

(defn parse-prelude [s]
  (reduce (fn [m [k v]]
            (case k
              "msg" (assoc m :msg (parse-long? v))
              "tm"  (assoc m :base-time-us (parse-long? v))
              "v"   (assoc m :v (parse-long? v))
              m))
          {}
          (map #(str/split % #"=" 2) (str/split (or s "") #","))))

(defn parse-metric-line [line base-tm-us]
  (let [tokens (str/split (str/trim line) #"\s+")
        name   (first tokens)
        ntoken (count tokens)]
    (when (seq name)
      (let [second-tok (second tokens)
            offset-ms  (parse-long? (last tokens))
            ts         (when (and offset-ms base-tm-us)
                         (+ base-tm-us (* offset-ms 1000)))]
        (cond
          (and (>= ntoken 3) (str/starts-with? (or second-tok "") "v="))
          {:type :numeric, :name name, :value (parse-value (subs second-tok 2)),
           :offset-ms offset-ms, :device-time-us ts}

          (and (>= ntoken 3) (str/starts-with? (or second-tok "") "error="))
          {:type :error, :name name,
           :error (or (second (re-find #"error=\"([^\"]*)\"" line)) ""),
           :offset-ms offset-ms, :device-time-us ts}

          (>= ntoken 3)
          {:type :structured, :name name,
           :fields (parse-kv-pairs (str/join " " (drop 1 (drop-last 1 tokens)))),
           :offset-ms offset-ms, :device-time-us ts}

          :else {:type :unknown, :name name, :raw line})))))

(defn parse-packet [{:keys [message sender]}]
  (try
    (let [txt        (bs/to-string message)
          lines      (str/split txt #"\r?\n")
          prelude    (parse-prelude (some-> (re-find prelude-re (first lines)) str/trim))
          base-tm-us (:base-time-us prelude)]
      {:sender sender, :received-at (java.util.Date.), :prelude prelude,
       :metrics (into [] (keep #(parse-metric-line % base-tm-us)) (rest lines)),
       :raw txt})
    (catch Exception e
      {:error (.getMessage e), :raw (try (bs/to-string message) (catch Exception _ "<?>"))})))

;; ============================================================
;; Transform functions (pure)
;; ============================================================

(defn sort-metrics [packet]
  (update packet :metrics #(sort-by :device-time-us %)))

(defn add-timestamps [packet]
  (let [fmt (SimpleDateFormat. "HH:mm:ss.SSS")]
    (-> packet
        (assoc :wall-time-str (.format fmt (:received-at packet)))
        (update :metrics
                (fn [ms]
                  (mapv (fn [m]
                          (assoc m :device-time-str
                                 (when-let [us (:device-time-us m)]
                                   (format "%02d:%06.3f"
                                           (int (/ us 60000000))
                                           (mod (/ us 1000000.0) 60)))))
                        ms))))))

(defn format-value [m]
  (case (:type m)
    :numeric    (let [v (:value m)]
                  (cond (integer? v) (str v)
                        (number? v)  (format "%.3f" (double v))
                        :else        (str v)))
    :error      (str "ERROR: " (:error m))
    :structured (str/join ", " (map (fn [[k v]] (str k "=" v)) (:fields m)))
    "?"))

(defn add-display-lines [{:keys [wall-time-str metrics] :as packet}]
  (assoc packet :display-lines
         (mapv (fn [m]
                 (format "[%s | %s] %-20s = %s"
                         wall-time-str
                         (or (:device-time-str m) "--------")
                         (:name m)
                         (format-value m)))
               metrics)))

;; ============================================================
;; Declarative Stream Graph
;; ============================================================

(defn named-stream
  "Create a stream with a name for introspection."
  ([name] (named-stream name nil))
  ([name buffer-size]
   (let [s (if buffer-size (s/stream buffer-size) (s/stream))]
     ;; Store name in metadata-like way via description
     (vary-meta s assoc ::name name))))

(defn connect-named
  "Connect with a description for downstream introspection."
  [src sink description]
  (s/connect src sink {:description description}))

(defn transform-stage
  "Create a named transform stage. Returns the output stream."
  [input stage-name xf]
  (let [output (s/transform xf input)]
    ;; The transform creates the connection automatically
    ;; We can see it via (s/downstream input)
    output))

;; ============================================================
;; Topology DSL
;; ============================================================

(defn build-graph
  "Build a stream graph from a declarative topology spec.
   
   Topology format:
   {:source <stream or deferred<stream>>
    :stages [[:name xf-or-fn] ...]      ; linear pipeline
    :sinks  {:sink-name {:type :console/:tcp/:bus/...
                         :opts {...}}}}
   
   Returns {:streams {name stream} :stop! fn}"
  [{:keys [source stages sinks]}]
  (let [source-stream (if (instance? clojure.lang.IDeref source) @source source)

        ;; Build linear transform pipeline
        ;; Each stage creates a downstream connection visible via s/downstream
        pipeline-output
        (reduce (fn [input [stage-name stage-fn]]
                  (let [xf (if (fn? stage-fn)
                             (map stage-fn)
                             stage-fn)]
                    (s/transform xf input)))
                source-stream
                stages)

        ;; For fan-out, use s/map which creates multiple downstreams
        ;; Each sink gets its own derived stream
        sink-streams
        (into {}
              (map (fn [[sink-name sink-spec]]
                     (let [sink-stream (s/map identity pipeline-output)]
                       [sink-name {:stream sink-stream
                                   :spec sink-spec}])))
              sinks)]

    {:source source-stream
     :pipeline-output pipeline-output
     :sinks sink-streams
     :stop! (fn []
              (s/close! source-stream)
              (doseq [[_ {:keys [stream]}] sink-streams]
                (s/close! stream))
              ::stopped)}))

;; ============================================================
;; Topology introspection (using Manifold's native downstream)
;; ============================================================

(defn topology-data
  "Full topology as nested data - all details from Manifold."
  ([stream] (topology-data stream #{}))
  ([stream seen]
   (let [id (System/identityHashCode stream)
         desc (s/description stream)]
     (if (seen id)
       {:CYCLE id}
       (let [downstream (try (s/downstream stream) (catch Exception _ nil))
             seen' (conj seen id)]
         (cond-> {:id id :description desc}
           (seq downstream)
           (assoc :downstream
                  (mapv (fn [[conn sink]]
                          {:connection conn
                           :to (topology-data sink seen')})
                        downstream))))))))

(defn topology-summary
  "Concise topology - key info with readable structure."
  ([stream] (topology-summary stream #{}))
  ([stream seen]
   (let [id (System/identityHashCode stream)
         desc (s/description stream)]
     (if (seen id)
       {:CYCLE id}
       (let [downstream (try (s/downstream stream) (catch Exception _ nil))
             seen' (conj seen id)
             node-type (or (:type desc) (get-in desc [:sink :type]))
             address (get-in desc [:sink :connection :local-address])
             buffer (or (:buffer-capacity desc) (:buffer-size desc))
             permanent? (:permanent? desc)
             closed? (or (:closed? desc) (get-in desc [:sink :closed?]))
             drained? (:drained? desc)
             node (cond-> (array-map :type node-type)
                    address (assoc :address address)
                    (and buffer (pos? buffer)) (assoc :buffer buffer)
                    permanent? (assoc :permanent true)
                    closed? (assoc :CLOSED true)
                    drained? (assoc :DRAINED true))]
         (if (seq downstream)
           (assoc node :downstream
                  (mapv (fn [[conn sink]]
                          (let [via (cond
                                      (string? conn) conn
                                      (map? conn) (or (:description conn) (:op conn))
                                      :else nil)
                                child (topology-summary sink seen')]
                            (if via
                              (array-map :via via :to child)
                              (array-map :to child))))
                        downstream))
           node))))))

(defn print-topology
  "Print topology as tree."
  ([stream] (print-topology stream "" #{} nil))
  ([stream indent seen via-label]
   (let [id (System/identityHashCode stream)
         desc (s/description stream)]
     (if (seen id)
       (println (str indent "↺ CYCLE"))
       (let [downstream (try (s/downstream stream) (catch Exception _ nil))
             seen' (conj seen id)
             node-type (or (:type desc) (get-in desc [:sink :type]) "?")
             address (get-in desc [:sink :connection :local-address])
             buffer (or (:buffer-capacity desc) (:buffer-size desc))
             flags (str (when (:permanent? desc) " PERM")
                        (when (or (:closed? desc) (get-in desc [:sink :closed?])) " CLOSED")
                        (when (:drained? desc) " DRAINED"))
             buf-str (when (and buffer (pos? buffer)) (str "[" buffer "]"))
             node-str (str node-type buf-str flags (when address (str " @ " address)))]
         (when via-label
           (println (str indent "↓ " via-label)))
         (println (str indent node-str))
         (doseq [[conn sink] downstream]
           (let [via (cond
                       (string? conn) conn
                       (map? conn) (or (:description conn) (:op conn))
                       :else nil)]
             (print-topology sink (str indent "  ") seen' via))))))))

;; ============================================================
;; Sink implementations
;; ============================================================

(defmulti create-sink :type)

(defmethod create-sink :console
  [{:keys [stream format-fn] :or {format-fn :display-lines}}]
  (s/consume #(run! println (format-fn %)) stream)
  {:type :console})

(defmethod create-sink :tcp-display
  [{:keys [stream port] :or {port 9515}}]
  (let [clients (atom #{})
        server (tcp/start-server
                (fn [client-stream _]
                  (swap! clients conj client-stream)
                  (s/on-closed client-stream #(swap! clients disj client-stream))
                  (s/put! client-stream "\033[2J\033[HConnected to Prusa Telemetry\n"))
                {:port port})]
    (s/consume
     (fn [{:keys [display-lines]}]
       (let [out (str "\r" (str/join "\n\r" display-lines)
                      "\n\r" (apply str (repeat 80 "=")))]
         (doseq [c @clients] (s/put! c out))))
     stream)
    {:type :tcp-display
     :server server
     :clients clients
     :stop! #(do (doseq [c @clients] (s/close! c))
                 (.close server))}))

(defmethod create-sink :bus
  [{:keys [stream bus topic] :or {topic :packets}}]
  (s/connect-via stream #(bus/publish! bus topic %) (s/stream))
  {:type :bus :bus bus :topic topic})

(defmethod create-sink :callback
  [{:keys [stream callback]}]
  (s/consume callback stream)
  {:type :callback})

;; ============================================================
;; High-level API
;; ============================================================

(def default-stages
  "Default processing pipeline.
   Each stage is [name stage] where stage is either:
   - A transducer (use directly)
   - A function (wrap in map)"
  [[:parse       (map parse-packet)]      ;; transducer
   [:filter-err  (remove :error)]          ;; transducer  
   [:sort        (map sort-metrics)]       ;; transducer
   [:timestamps  (map add-timestamps)]     ;; transducer
   [:display     (map add-display-lines)]]) ;; transducer

(defn start-telemetry-server
  [{:keys [port stages sinks]
    :or {port 8514
         stages default-stages
         sinks {}}}]
  (let [socket @(udp/socket {:port port})

        input-buffer (s/stream 1000)
        _ (s/connect socket input-buffer {:description "udp-socket → input-buffer"})

        ;; Compose all transducers into one, then apply once
        stage-names (str/join " → " (map (comp name first) stages))
        pipeline-xf (apply comp (map second stages))
        pipeline-out (s/transform pipeline-xf input-buffer)

        fan-out (s/stream* {:permanent? true :buffer-size 100})
        _ (s/connect pipeline-out fan-out {:description (str "pipeline [" stage-names "] → fan-out")})

        active-sinks (into {}
                           (map (fn [[sink-name spec]]
                                  (let [branch (s/stream 100)
                                        _ (s/connect fan-out branch {:description (str "fan-out → " (name sink-name))})
                                        sink (create-sink (assoc spec :stream branch))]
                                    [sink-name sink])))
                           sinks)]

    {:socket socket
     :input-buffer input-buffer
     :pipeline-out pipeline-out
     :fan-out fan-out
     :sinks active-sinks
     :tap (fn []
            (let [t (s/stream 100)]
              (s/connect fan-out t {:description "fan-out → tap"})
              t))

     ;; Topology as data
     :topology-data (fn [] (topology-data socket))
     :topology-summary (fn [] (topology-summary socket))

     ;; Pretty print
     :topology (fn [] (print-topology socket))

     :stages (mapv first stages)
     :stop! (fn []
              (doseq [[_ sink] active-sinks]
                (when-let [stop (:stop! sink)] (stop)))
              (s/close! fan-out)
              (s/close! input-buffer)
              (s/close! socket)
              ::stopped)}))

;; ============================================================
;; Main
;; ============================================================

(defn -main [& args]
  (let [port (or (some-> args first parse-long?) 8514)
        srv (start-telemetry-server
             {:port port
              :sinks {:display {:type :tcp-display :port 9515}
                      :console {:type :console}}})]

    (println (format "Started: UDP %d, TCP display 9515" port))
    (println "Stages:" (:stages srv))
    (println "\nTopology:")
    ((:topology srv))

    (.addShutdownHook
     (Runtime/getRuntime)
     (Thread. #((:stop! srv))))

    @(promise)))

;; ============================================================
;; REPL helpers
;; ============================================================

(comment
  ;; Start with specific sinks
  (def srv (start-telemetry-server
            {:port 8514
             :sinks {:console {:type :console}
                     :display {:type :tcp-display :port 9515}}}))

  ;; View topology
  ((:topology srv))

  ;; Get topology as data
  ((:topology-data srv))

  ;; Create a tap for inspection
  (def my-tap ((:tap srv)))
  @(s/try-take! my-tap ::drained 5000 ::timeout)

  ;; Add custom sink dynamically
  (def stats (atom {:count 0}))
  (s/consume #(swap! stats update :count inc) ((:tap srv)))
  @stats

  ;; Inspect downstream at any point
  (s/downstream (:socket srv))
  (s/downstream (:pipeline-out srv))

  ;; Stop
  ((:stop! srv)))
