(ns user
  "REPL utilities for telemetry services.
   
   Quick start:
     (start!)              ; start all services (no console spam)
     (peek!)               ; inspect packets
     (add-sink! :console println-sink)  ; add output when needed
     (remove-sink! :console)            ; remove it
     (stop!)               ; stop everything"
  (:require
   [aeonik.prusa-telemetry :as telemetry]
   [aeonik.web-server :as web]
   [aeonik.xtdb :as xtdb]
   [shadow.cljs.devtools.server :as server]
   [shadow.cljs.devtools.api :as shadow]
   [manifold.stream :as s]
   [clojure.pprint :as pp]))

;; ============================================================
;; State
;; ============================================================

(defonce telemetry-server (atom nil))
(defonce web-server (atom nil))
(defonce xtdb-node (atom nil))
(defonce sinks (atom {}))  ;; {name -> stream}

;; ============================================================
;; Sink management
;; ============================================================

(defn add-sink!
  "Add a named sink. Returns the stream (closeable)."
  [name callback]
  (if-let [srv @telemetry-server]
    (let [stream ((:tap srv))]
      (s/consume callback stream)
      (swap! sinks assoc name stream)
      (println "✓ Sink added:" name)
      stream)
    (println "✗ Telemetry not running")))

(defn remove-sink!
  "Remove a sink by name."
  [name]
  (if-let [stream (get @sinks name)]
    (do (s/close! stream)
        (swap! sinks dissoc name)
        (println "✓ Sink removed:" name))
    (println "✗ Sink not found:" name)))

(defn clear-sinks!
  "Remove all sinks."
  []
  (doseq [name (keys @sinks)]
    (remove-sink! name)))

;; Predefined sink callbacks
(def println-sink #(run! println (:display-lines %)))
(def pprint-sink #(pp/pprint (dissoc % :raw)))

;; ============================================================
;; Services
;; ============================================================

(defn start-telemetry!
  ([] (start-telemetry! {}))
  ([{:keys [port] :or {port 8514}}]
   (when-not @telemetry-server
     (reset! telemetry-server
             (telemetry/start-telemetry-server {:port port :sinks {}}))
     (println "✓ Telemetry on UDP" port))
   @telemetry-server))

(defn stop-telemetry! []
  (when-let [srv @telemetry-server]
    (clear-sinks!)
    ((:stop! srv))
    (reset! telemetry-server nil)
    (println "✓ Telemetry stopped")))

(defn start-web!
  ([] (start-web! {}))
  ([{:keys [port] :or {port 8080}}]
   (when-not @web-server
     (or @telemetry-server (start-telemetry!))
     (let [tap ((:tap @telemetry-server))]
       (reset! web-server
               (web/start-web-server {:port port :telemetry-stream tap}))
       (println "✓ Web on port" port "| ws://localhost:" port "/ws")))
   @web-server))

(defn stop-web! []
  (when-let [srv @web-server]
    ((:stop! srv))
    (reset! web-server nil)
    (println "✓ Web stopped")))

(defn start-xtdb!
  ([] (start-xtdb! {}))
  ([opts]
   (when-not @xtdb-node
     (try
       (reset! xtdb-node (xtdb/start-node! opts))
       (println "✓ XTDB started")
       (catch Exception e
         (println "✗ XTDB failed:" (.getMessage e)))))
   @xtdb-node))

(defn stop-xtdb! []
  (when-let [node @xtdb-node]
    (try ((:stop! node)) (catch Exception _))
    (reset! xtdb-node nil)
    (println "✓ XTDB stopped")))

;; ============================================================
;; Lifecycle
;; ============================================================

(defn status []
  {:telemetry (if @telemetry-server :running :stopped)
   :web       (if @web-server :running :stopped)
   :xtdb      (if @xtdb-node :running :stopped)
   :sinks     (vec (keys @sinks))})

(defn start!
  "Start services. No console output by default—use add-sink! for that."
  ([] (start! {}))
  ([{:keys [telemetry-port web-port xtdb?]
     :or {telemetry-port 8514 web-port 8080}}]
   (start-telemetry! {:port telemetry-port})
   (when xtdb? (start-xtdb!))
   (start-web! {:port web-port})
   (status)))

(defn stop! []
  (stop-web!)
  (stop-telemetry!)
  (stop-xtdb!))

(defn restart! []
  (stop!)
  (Thread/sleep 300)
  (start!))

;; ============================================================
;; Inspection
;; ============================================================

(defn tap!
  "Get a fresh tap stream."
  []
  (when-let [srv @telemetry-server]
    ((:tap srv))))

(defn peek!
  "Grab n packets (default 1)."
  ([] (peek! 1))
  ([n]
   (if-let [t (tap!)]
     (vec (repeatedly n #(deref (s/try-take! t ::timeout 2000 ::timeout))))
     (println "✗ Telemetry not running"))))

(defn topology
  "Print stream topology (one line per node)."
  []
  (when-let [srv @telemetry-server]
    (telemetry/print-topology (:socket srv))))

(defn topology-data
  "Full topology as nested data."
  []
  (when-let [srv @telemetry-server]
    (telemetry/topology-data (:socket srv))))

(defn topology-summary
  "Concise topology - just node types and connections."
  []
  (when-let [srv @telemetry-server]
    (telemetry/topology-summary (:socket srv))))

(defn stages
  "List the processing pipeline stages."
  []
  (when-let [srv @telemetry-server]
    (:stages srv)))

;; ============================================================
;; Convenience sinks
;; ============================================================

(defn stats-sink!
  "Add stats sink, returns atom with running stats."
  []
  (let [stats (atom {:packets 0 :metrics 0 :by-type {}})]
    (add-sink! :stats
               (fn [{:keys [metrics]}]
                 (swap! stats
                        #(-> %
                             (update :packets inc)
                             (update :metrics + (count metrics))
                             (update :by-type
                                     (fn [bt]
                                       (reduce (fn [m {:keys [type]}]
                                                 (update m type (fnil inc 0)))
                                               bt metrics)))))))
    stats))

;; ============================================================
;; ClojureScript REPL
;; ============================================================

(defn cljs-repl
  ([] (cljs-repl :app))
  ([build-id]
   (server/start!)
   (shadow/watch build-id)
   (shadow/nrepl-select build-id)))

;; ============================================================
;; Auto-start (quiet—no console sink)
;; ============================================================

(defonce ^:private _auto-start
  (future
    (Thread/sleep 1000)
    (try (start!)
         (catch Exception e
           (println "Auto-start failed:" (.getMessage e))))))

;; ============================================================
;; REPL examples
;; ============================================================

(comment
  (start!)
  (stop!)
  (status)

  ;; Add console output when you want it
  (add-sink! :console println-sink)
  (remove-sink! :console)

  ;; Inspect packets
  (peek!)
  (peek! 5)

  ;; Stats
  (def stats (stats-sink!))
  @stats

  ;; Topology - three views
  (topology)          ; printed, one line per node
  (topology-summary)  ; data, concise
  (topology-data)     ; data, full details

  ;; Custom sink
  (add-sink! :logger #(spit "telemetry.log"
                            (str (:wall-time-str %) "\n")
                            :append true)))
