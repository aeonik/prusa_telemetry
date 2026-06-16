(ns aeonik.prusa-telemetry
  (:require
   [aleph.udp :as udp]
   [aleph.tcp :as tcp]
   [clj-commons.byte-streams :as bs]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [aeonik.metric-catalog :as metric-catalog]
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
  (cond
    (and (string? s) (>= (count s) 2) (= \" (first s)) (= \" (last s)))
    (try
      (edn/read-string s)
      (catch Exception _
        (subs s 1 (dec (count s)))))

    (and (string? s) (seq s) (= \" (first s)))
    (subs s 1)

    :else
    s))

(defn parse-value [s]
  (when s
    (let [s (str/trim s)]
      (cond
        (and (>= (count s) 2) (str/ends-with? s "i"))
        (or (parse-long? (subs s 0 (dec (count s)))) (unquote-str s))

        (re-matches #"[+-]?\d+" s)
        (or (parse-long? s) (unquote-str s))

        :else
        (or (parse-double? s) (unquote-str s))))))

(defn split-unquoted
  "Split s on delimiter outside double-quoted strings."
  [s delimiter]
  (let [s (or s "")
        length (count s)]
    (loop [idx 0
           in-quote? false
           escaped? false
           part (StringBuilder.)
           parts []]
      (if (>= idx length)
        (conj parts (str part))
        (let [ch (.charAt s idx)]
          (cond
            escaped?
            (do
              (.append part ch)
              (recur (inc idx) in-quote? false part parts))

            (= ch \\)
            (do
              (.append part ch)
              (recur (inc idx) in-quote? true part parts))

            (= ch \")
            (do
              (.append part ch)
              (recur (inc idx) (not in-quote?) false part parts))

            (and (= ch delimiter) (not in-quote?))
            (recur (inc idx) false false (StringBuilder.) (conj parts (str part)))

            :else
            (do
              (.append part ch)
              (recur (inc idx) in-quote? false part parts))))))))

(defn parse-kv-pairs [s]
  (into {}
        (keep (fn [seg]
                (let [[k v] (str/split (str/trim seg) #"=" 2)]
                  (when (and (seq k) (some? v))
                    [k (parse-value v)]))))
        (split-unquoted (or s "") \,)))

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

(defn- add-timing-fields
  "Attach metric timing fields from the firmware line offset.
   The firmware emits the offset in microseconds relative to the packet `tm`
   header."
  [metric offset-us base-tm-us]
  (cond-> (assoc metric
                 :offset-us offset-us
                 :offset-ms (when offset-us (/ offset-us 1000.0)))
    (and offset-us base-tm-us) (assoc :device-time-us (+ base-tm-us offset-us))))

(defn parse-metric-head
  "Parse a line-protocol metric head into a clean metric name and tag map."
  [head]
  (let [[name & tag-parts] (split-unquoted head \,)]
    {:name (not-empty (str/trim (or name "")))
     :tags (not-empty
            (into {}
                  (keep (fn [tag-part]
                          (let [[k v] (str/split (str/trim tag-part) #"=" 2)]
                            (when (and (seq k) (some? v))
                              [k (parse-value v)]))))
                  tag-parts))}))

(defn- parse-scalar-value
  [raw-value scalar-type]
  (case scalar-type
    :integer (or (parse-long? (str/replace (str/trim raw-value) #"i$" ""))
                 (parse-value raw-value))
    :float (or (parse-double? (str/trim raw-value))
               (parse-value raw-value))
    :string (unquote-str (str/trim raw-value))
    :event true
    (parse-value raw-value)))

(defn- scalar-line?
  [tags fields]
  (and (empty? tags)
       (= #{"v"} (set (keys fields)))))

(defn- inferred-scalar-type
  [value]
  (if (number? value) :numeric :string))

(defn- metric-line-body
  [line]
  (when-let [[_ body raw-offset] (re-matches #"(?s)^(.+)\s+(-?\d+)\s*$" line)]
    {:body body
     :offset-us (parse-long? raw-offset)}))

(defn parse-metric-line
  ([line base-tm-us]
   (parse-metric-line line base-tm-us nil))
  ([line base-tm-us catalog]
   (let [line (str/trim line)]
    (when (seq line)
      (if-let [{:keys [body offset-us]} (metric-line-body line)]
        (let [[head fields-str] (str/split body #"\s+" 2)
              {:keys [name tags]} (parse-metric-head head)
              fields (parse-kv-pairs fields-str)
              metric-def (if catalog
                           (metric-catalog/metric-definition catalog name)
                           (metric-catalog/metric-definition name))
              scalar-type (metric-catalog/scalar-metric-type metric-def)]
          (cond
            (contains? fields "error")
            (add-timing-fields
             {:type :error
              :name name
              :error (str (get fields "error"))}
             offset-us
             base-tm-us)

            (scalar-line? tags fields)
            (let [value (parse-scalar-value (str (get fields "v"))
                                            scalar-type)
                  inferred-type (or scalar-type (inferred-scalar-type value))]
              (add-timing-fields
               {:type inferred-type
                :name name
                :value value}
               offset-us
               base-tm-us))

            (seq fields)
            (add-timing-fields
             (cond-> {:type :structured
                      :name name
                      :fields fields}
               (seq tags) (assoc :tags tags))
             offset-us
             base-tm-us)

            :else
            {:type :unknown
             :name name
             :raw line}))
        {:type :unknown
         :name (first (str/split line #"\s+"))
         :raw line})))))

(defn- parse-prelude-line
  [line]
  (let [matcher (re-matcher prelude-re (or line ""))]
    (when (.find matcher)
      {:prelude (parse-prelude (str/trim (.group matcher)))
       :metric-tail (str/trim (subs line (.end matcher)))})))

(defn parse-packet [{:keys [message sender]}]
  (try
    (let [txt        (bs/to-string message)
          lines      (str/split txt #"\r?\n")
          parsed-head (parse-prelude-line (first lines))
          prelude    (or (:prelude parsed-head) {})
          base-tm-us (:base-time-us prelude)
          metric-lines (if parsed-head
                         (into (if (seq (:metric-tail parsed-head))
                                 [(:metric-tail parsed-head)]
                                 [])
                               (rest lines))
                         lines)]
      {:sender sender, :received-at (java.util.Date.), :prelude prelude,
       :metrics (into [] (keep #(parse-metric-line % base-tm-us)) metric-lines),
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
                                   (let [total-seconds (/ us 1000000.0)
                                         hours (int (/ total-seconds 3600))
                                         minutes (int (/ (mod total-seconds 3600) 60))
                                         seconds (mod total-seconds 60)]
                                     (format "%02d:%02d:%05.2f" hours minutes (double seconds))))))
                        ms))))))

(defn format-value [m]
  (cond
    (:error m)
    (str "ERROR: " (:error m))

    (:fields m)
    (str/join ", " (map (fn [[k v]] (str k "=" v)) (:fields m)))

    :else
    (let [v (:value m)]
      (cond
        (integer? v) (str v)
        (number? v)  (format "%.3f" (double v))
        (some? v)    (str v)
        :else        "?"))))

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
   - A function (wrap in map)

   Stage functions are stored as Vars so CIDER namespace reloads update the
   behavior of an already-running pipeline. Changing the stage graph itself
   still requires a telemetry restart."
  [[:parse       (map #'parse-packet)]      ;; transducer
   [:filter-err  (remove :error)]          ;; transducer  
   [:sort        (map #'sort-metrics)]       ;; transducer
   [:timestamps  (map #'add-timestamps)]     ;; transducer
   [:display     (map #'add-display-lines)]]) ;; transducer

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
                                        _ (s/connect fan-out
                                                     branch
                                                     {:description (str "fan-out → " (name sink-name))
                                                      :upstream? false
                                                      :downstream? true})
                                        sink (create-sink (assoc spec :stream branch))]
                                    [sink-name sink])))
                           sinks)]

    {:socket socket
     :config {:port port
              :stages (mapv first stages)}
     :input-buffer input-buffer
     :pipeline-out pipeline-out
     :fan-out fan-out
     :sinks active-sinks
     :tap (fn []
            (let [t (s/stream 100)]
              (s/connect fan-out
                         t
                         {:description "fan-out → tap"
                          :upstream? false
                          :downstream? true})
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
