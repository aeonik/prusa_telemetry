(ns aeonik.web-server
  (:require
   [aleph.http :as http]
   [aleph.netty :as netty]
   [manifold.stream :as s]
   [manifold.deferred :as d]
   [aeonik.prusa-telemetry :as telemetry]
   [aeonik.prusalink :as prusalink]
   [aeonik.prusalink-auth :as prusalink-auth]
   [clojure.data.json :as json]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def ^:private prints-dir (io/file "telemetry-data" "prints"))

;; Track active prints per sender:
;; {sender {:filename "..." :save-filename "..." :job-id 123 :last-packet-time <timestamp>}}
(def ^:private active-prints (atom {}))
(def ^:private prusalink-print-state
  (atom {:available? false
         :active? nil
         :run-id nil
         :updated-at nil}))

;; Print is considered ended if no packets received for this many milliseconds
(def ^:private print-end-timeout-ms (* 10 60 1000)) ; 10 minutes
(def ^:private missing-print-filename ::missing-print-filename)
(def ^:private prusalink-poll-interval-ms 1000)

(defn- ensure-prints-dir! 
  "Ensure the prints directory exists"
  []
  (when-not (.exists prints-dir)
    (.mkdirs prints-dir)
    (println "Created prints directory:" (.getAbsolutePath prints-dir))))

(defn- normalize-print-filename
  "Normalize the firmware print filename metric.
   Blank names mean idle/no active print. Some long string metrics can arrive
   with an opening quote but no closing quote, so trim boundary quotes here."
  [filename]
  (when (some? filename)
    (let [normalized (-> (str filename)
                         (str/trim)
                         (str/replace #"^\"+" "")
                         (str/replace #"\"+$" "")
                         (str/trim))]
      (not-empty normalized))))

(defn- sanitize-filename
  "Sanitize filename for use in file system."
  [filename]
  (when-let [filename (normalize-print-filename filename)]
    (not-empty
     (-> filename
         (str/replace #"[^\w\s\-_\.]" "_")
         (str/replace #"\s+" "_")
         (str/replace #"^\.+" "")
         (str/replace #"\.+$" "")
         (str/trim)))))

(defn- metric-value
  "Extract a metric value from either :value or structured fields."
  [metric]
  (or (:value metric)
      (when-let [fields (:fields metric)]
        (when (map? fields)
          (or (get fields "value")
              (first (vals fields)))))))

(defn- get-print-filename 
  "Extract raw print_filename from metrics.
   Returns `missing-print-filename` when the metric is absent so an explicit
   blank filename can be distinguished from no filename update."
  [metrics]
  (reduce (fn [_ metric]
            (if (= (:name metric) "print_filename")
              (reduced (metric-value metric))
              missing-print-filename))
          missing-print-filename
          metrics))

(defn- active-prusalink-run-suffix
  "Return the active local print-run suffix when PrusaLink reports a live job."
  []
  (let [{:keys [available? active? run-id job-id]} @prusalink-print-state]
    (when (and available? active?)
      (or run-id
          (when (some? job-id) (str "job-" job-id))))))

(defn- print-save-filename
  "Build the archive filename for a print run.
   A local run suffix distinguishes repeated attempts of the same G-code file."
  [filename]
  (when-let [filename (normalize-print-filename filename)]
    (if-let [run-suffix (active-prusalink-run-suffix)]
      (str filename "_" run-suffix)
      filename)))

(defn- get-active-print
  "Get the active print for a sender, checking for timeouts."
  [sender current-time]
  (let [active-print (get @active-prints sender)]
    (if active-print
      (let [time-since-last-packet (- current-time (:last-packet-time active-print))]
        (if (> time-since-last-packet print-end-timeout-ms)
          ;; Print has timed out - consider it ended
          (do
            (swap! active-prints dissoc sender)
            nil)
          ;; Still active - update last packet time
          (do
            (swap! active-prints assoc sender (assoc active-print :last-packet-time current-time))
            (get @active-prints sender))))
      nil)))

(defn- set-active-print-filename! 
  "Set the active print filename for a sender."
  [sender filename current-time]
  (let [save-filename (print-save-filename filename)]
    (swap! active-prints assoc sender
           {:filename filename
            :save-filename save-filename
            :run-suffix (active-prusalink-run-suffix)
            :last-packet-time current-time})
    (get @active-prints sender)))

(defn- refresh-active-print-run!
  "Update the active save filename when PrusaLink reports a new local run."
  [sender active-print current-time]
  (if (and active-print (active-prusalink-run-suffix))
    (let [expected-save-filename (print-save-filename (:filename active-print))]
      (if (= expected-save-filename (:save-filename active-print))
        active-print
        (set-active-print-filename! sender (:filename active-print) current-time)))
    active-print))

(defn- clear-active-print-filename!
  "Clear the active print filename for a sender."
  [sender]
  (swap! active-prints dissoc sender))

(defn- prusalink-print-active?
  "Return true when PrusaLink currently reports an active job.
   If PrusaLink is unavailable, preserve telemetry-only save behavior."
  []
  (let [{:keys [available? active?]} @prusalink-print-state]
    (if available?
      (boolean active?)
      true)))

(defn- clear-completed-print!
  "Clear sticky print tracking when PrusaLink says the print is no longer active."
  [sender active-print]
  (when active-print
    (println "PrusaLink reports no active job; stopping telemetry save for"
             (or (:save-filename active-print) (:filename active-print))))
  (clear-active-print-filename! sender))

(defn- new-prusalink-run-id
  "Create a local print-run identifier from the observed PrusaLink job."
  [job-id now-ms]
  (let [stamp (.format (java.text.SimpleDateFormat. "yyyyMMdd-HHmmss-SSS")
                       (java.util.Date. now-ms))]
    (if (some? job-id)
      (str "job-" job-id "_run-" stamp)
      (str "run-" stamp))))

(defn- prusalink-run-started?
  "Return true when status indicates a new local print run.
   PrusaLink job ids can be reused when manually reprinting, so also detect
   elapsed-time or progress rollbacks."
  [previous-state job]
  (let [previous-time (:time-printing previous-state)
        current-time (:time_printing job)
        previous-progress (:progress previous-state)
        current-progress (:progress job)]
    (or (not (:active? previous-state))
        (not= (:job-id previous-state) (:id job))
        (and (number? previous-time)
             (number? current-time)
             (< current-time (- previous-time 5)))
        (and (number? previous-progress)
             (number? current-progress)
             (< current-progress (- previous-progress 1.0))))))

(defn- prusalink-status->print-state
  "Convert a PrusaLink status JSON map into local print-state."
  ([status-json]
   (prusalink-status->print-state status-json @prusalink-print-state (System/currentTimeMillis)))
  ([{:keys [printer job]} previous-state now-ms]
   (let [active? (boolean job)
         run-id (when active?
                  (if (prusalink-run-started? previous-state job)
                    (new-prusalink-run-id (:id job) now-ms)
                    (:run-id previous-state)))]
     {:available? true
      :active? active?
      :state (:state printer)
      :job-id (:id job)
      :run-id run-id
      :progress (:progress job)
      :time-printing (:time_printing job)
      :updated-at now-ms})))

(defn- refresh-prusalink-print-state!
  "Poll PrusaLink for the current print state used to gate disk writes."
  []
  (try
    (let [{:keys [status json]} (prusalink/status)]
      (if (<= 200 status 299)
        (reset! prusalink-print-state (prusalink-status->print-state json))
        (swap! prusalink-print-state assoc
               :available? false
               :active? nil
               :error (str "HTTP " status)
               :updated-at (System/currentTimeMillis))))
    (catch Exception e
      (swap! prusalink-print-state assoc
             :available? false
             :active? nil
             :error (.getMessage e)
             :updated-at (System/currentTimeMillis)))))

(defn- start-prusalink-print-state-poller!
  "Start polling PrusaLink print state.
   Returns a stop function."
  []
  (let [running? (atom true)
        poller (future
                 (while @running?
                   (refresh-prusalink-print-state!)
                   (Thread/sleep prusalink-poll-interval-ms)))]
    (fn []
      (reset! running? false)
      (future-cancel poller))))

(defn- save-packet-to-file!
  "Save a packet to a file for the given print filename in EDN format (append-only, one packet per line)"
  [packet print-filename]
  (try
    (ensure-prints-dir!)
    (if-let [sanitized-name (sanitize-filename print-filename)]
      (let [now (java.util.Date.)
            date-fmt (java.text.SimpleDateFormat. "yyyy-MM-dd")
            date-str (.format date-fmt now)
            date-dir (io/file prints-dir date-str)
            _ (.mkdirs date-dir)
            print-file (io/file date-dir (str sanitized-name ".edn"))]
        (with-open [writer (io/writer print-file :append true)]
          (binding [*print-length* nil
                    *print-level* nil
                    *out* writer]
            (prn packet)))
        true)
      false)
    (catch Exception e
      (println "Error saving packet to file:" (.getMessage e))
      (.printStackTrace e)
      false)))

(defn telemetry-to-json
  "Convert telemetry packet to JSON-serializable format, ensuring metrics are sorted by device-time-us"
  [{:keys [sender received-at prelude metrics display-lines wall-time-str]}]
  (let [sorted-metrics (sort-by (fn [m] (or (:device-time-us m) 0)) metrics)]
    {:sender (str sender)
     :received-at (.getTime received-at)
     :prelude prelude
     :metrics (map (fn [m]
                     (cond-> {:name (:name m)
                              :type (name (:type m))
                              :offset-us (:offset-us m)
                              :offset-ms (:offset-ms m)
                              :device-time-us (:device-time-us m)
                              :device-time-str (:device-time-str m)}
                       (:value m) (assoc :value (:value m))
                       (:error m) (assoc :error (:error m))
                       (:fields m) (assoc :fields (:fields m))))
                   sorted-metrics)
     :display-lines display-lines
     :wall-time-str wall-time-str}))

(defn- handle-packet-saving! 
  "Handle print filename tracking and file saving (called once per packet, not per WebSocket client)"
  [packet]
  (let [{:keys [sender metrics]} packet
        sender-str (str sender)
        current-time (System/currentTimeMillis)
        ;; Check for new print_filename in this packet
        raw-print-filename (get-print-filename metrics)
        saw-print-filename? (not= missing-print-filename raw-print-filename)
        new-print-filename (when saw-print-filename?
                             (normalize-print-filename raw-print-filename))
        ;; Get active print (handles sticky behavior, timeout, and PrusaLink run ids)
        active-print (refresh-active-print-run!
                      sender-str
                      (get-active-print sender-str current-time)
                      current-time)
        active-save-filename (:save-filename active-print)]
    (cond
      (not (prusalink-print-active?))
      (clear-completed-print! sender-str active-print)

      ;; Firmware emits a blank print_filename while idle. Treat that as an
      ;; explicit end marker so post-print telemetry does not append forever.
      (and saw-print-filename? (nil? new-print-filename))
      (clear-active-print-filename! sender-str)

      new-print-filename
      (let [new-save-filename (print-save-filename new-print-filename)]
        (when-not (= new-save-filename active-save-filename)
          (set-active-print-filename! sender-str new-print-filename current-time))
        (save-packet-to-file! (telemetry-to-json packet) new-save-filename))

      active-save-filename
      (save-packet-to-file! (telemetry-to-json packet) active-save-filename))))

(defn websocket-handler
  "WebSocket handler that streams telemetry data.
   Each WebSocket connection gets its own subscription to ensure all clients see every packet.
   telemetry-stream: manifold stream from telemetry server (fan-out stream)"
  [telemetry-stream]
  (fn [req]
    (let [ws-deferred (http/websocket-connection req)]
      (d/chain ws-deferred
               (fn [ws]

                 ;; Create subscription stream for this client
                 (let [client-stream (s/stream 100)]
                   ;; Connect telemetry stream to client stream
                   (s/connect telemetry-stream client-stream {:description "fan-out → client-stream"})

                   ;; Consume packets and send to WebSocket
                   (s/consume
                    (fn [packet]
                      (try
                        (let [json-data (json/write-str (telemetry-to-json packet))
                              put-result (s/put! ws json-data)]
                          (when put-result
                            (d/on-realized put-result
                                           (fn [success]
                                             (when (and (not success) (not (s/closed? ws)))
                                               (println "WARNING: s/put! returned false but WebSocket not closed")))
                                           (fn [error]
                                             (when-not (s/closed? ws)
                                               (println "ERROR putting to WebSocket:" (.getMessage error)))))))
                        (catch Exception e
                          (when-not (s/closed? ws)
                            (println "ERROR sending WebSocket message:" (.getMessage e))
                            (.printStackTrace e)))))
                    client-stream)

                   ;; Handle client messages (if any)
                   (s/consume
                    (fn [msg]
                      nil)
                    ws)

                   ;; Clean up on disconnect
                   (s/on-closed ws
                                (fn []
                                  nil))

                   ws)))
      (d/catch ws-deferred
               (fn [error]
                 (let [error-str (str error)
                       is-stream? (or (.contains error-str "SplicedStream")
                                    (.contains error-str "Stream@"))]
                   (when-not is-stream?
                     (println "WebSocket connection error:" 
                              (cond
                                (instance? Exception error) (.getMessage error)
                                (instance? Throwable error) (.getMessage error)
                                :else (str error)))))))
      ws-deferred)))

(defn index-handler
  "Serve the main HTML page"
  [_req]
  (if-let [resource (io/resource "index.html")]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (slurp resource)}
    {:status 404
     :headers {"Content-Type" "text/plain"}
     :body "index.html not found"}))

(defn cljs-asset-handler
  "Serve ClojureScript compiled assets from target/cljs-out/"
  [req]
  (let [uri (:uri req)
        ;; Remove /app.js prefix to get the relative path
        relative-path (str/replace uri #"^/app\.js/" "")
        ;; Build the file path
        file-path (io/file "target/cljs-out" relative-path)]
    (if (and (.exists file-path) (.isFile file-path))
      (let [content-type (cond
                           (str/ends-with? relative-path ".js") "application/javascript"
                           (str/ends-with? relative-path ".map") "application/json"
                           :else "application/octet-stream")]
        {:status 200
         :headers {"Content-Type" content-type}
         :body (slurp file-path)})
      {:status 404
       :headers {"Content-Type" "text/plain"}
       :body (str "File not found: " relative-path)})))

(defn list-telemetry-files-handler
  "List all available telemetry data files"
  [_req]
  (try
    (ensure-prints-dir!)
    (let [date-dirs (filter #(and (.isDirectory %) 
                                  (re-matches #"\d{4}-\d{2}-\d{2}" (.getName %)))
                           (.listFiles prints-dir))
          files-by-date (reduce (fn [acc date-dir]
                                 (let [date (.getName date-dir)
                                       edn-files (filter #(and (.isFile %)
                                                               (str/ends-with? (.getName %) ".edn"))
                                                        (.listFiles date-dir))
                                       file-info (map (fn [f]
                                                       {:date date
                                                        :filename (.getName f)
                                                        :size (.length f)
                                                        :modified (.lastModified f)})
                                                     edn-files)]
                                   (concat acc file-info)))
                               []
                               date-dirs)
          sorted-files (sort-by (fn [f] [(:date f) (:filename f)]) files-by-date)]
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/write-str sorted-files)})
    (catch Exception e
      (println "Error listing telemetry files:" (.getMessage e))
      (.printStackTrace e)
      {:status 500
       :headers {"Content-Type" "application/json"}
       :body (json/write-str {:error (.getMessage e)})})))

(defn load-telemetry-file-handler
  "Load a telemetry data file by date and filename.
   Files are already grouped and sorted when saved, so we just read and return them."
  [req]
  (try
    (let [uri (:uri req)
          ;; Extract date and filename from URI like /api/telemetry-file/2025-12-12/filename.edn
          match (re-matches #"/api/telemetry-file/([^/]+)/(.+)" uri)]
      (if match
        (let [[_ date filename] match
              file-path (io/file prints-dir date filename)]
          (if (and (.exists file-path) (.isFile file-path))
            ;; Read packets line by line - they're already in correct format
            (let [packets (with-open [reader (io/reader file-path)]
                           (doall (keep (fn [line]
                                         (try
                                           (edn/read-string line)
                                           (catch Exception e
                                             (println "Error reading line:" (.getMessage e))
                                             nil)))
                                       (line-seq reader))))]
              {:status 200
               :headers {"Content-Type" "application/json"}
               :body (json/write-str packets)})
            {:status 404
             :headers {"Content-Type" "application/json"}
             :body (json/write-str {:error "File not found"})}))
        {:status 400
         :headers {"Content-Type" "application/json"}
         :body (json/write-str {:error "Invalid request format"})}))
    (catch Exception e
      (println "Error loading telemetry file:" (.getMessage e))
      (.printStackTrace e)
      {:status 500
       :headers {"Content-Type" "application/json"}
       :body (json/write-str {:error (.getMessage e)})})))

(defn- setup-packet-saving-consumer
  "Set up a single consumer for saving packets (runs once per packet, not per WebSocket client).
   Creates subscription stream and connects to telemetry stream to ensure we see every packet.
   telemetry-stream: manifold stream from telemetry server (fan-out stream)"
  [telemetry-stream]
  (let [saving-stream (s/stream 100)
        _ (println "Setting up packet saving consumer, connecting to telemetry-stream...")
        _ (s/connect telemetry-stream saving-stream {:description "fan-out → saving-stream"})]
    (let [saving-consumer (s/consume 
                           (fn [packet]
                             (try
                               (handle-packet-saving! packet)
                               (catch Exception e
                                 (println "ERROR processing packet for saving:" (.getMessage e))
                                 (.printStackTrace e)))
                             ;; Always return true to keep consuming
                             true)
                           saving-stream)]
      ;; Handle errors in the consumer (log but don't crash)
      (d/on-realized saving-consumer
                     (fn [_] nil)
                     (fn [error]
                       (println "ERROR: Packet saving consumer failed:" (.getMessage error))
                       (.printStackTrace error))))))

(defn- timeline-handler
  "Serve the timeline HTML page"
  [_req]
  (if-let [resource (io/resource "timeline.html")]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (slurp resource)}
    {:status 404
     :headers {"Content-Type" "text/plain"}
     :body "timeline.html not found"}))

(defn- dashboard-handler
  "Serve the live metrics dashboard HTML page."
  [_req]
  (if-let [resource (io/resource "dashboard.html")]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (slurp resource)}
    {:status 404
     :headers {"Content-Type" "text/plain"}
     :body "dashboard.html not found"}))

(defn- prusalink-auth-status-handler
  "Return a password-safe status for the local PrusaLink auth config."
  [_req]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/write-str (prusalink-auth/auth-status))})

(defn- prusalink-print-state-handler
  "Return the backend's local PrusaLink-derived print/run state."
  [_req]
  {:status 200
   :headers {"Content-Type" "application/json"
             "Cache-Control" "no-cache"}
   :body (json/write-str @prusalink-print-state)})

(defn- prusalink-proxy-handler
  "Proxy a PrusaLink API request through the backend Digest-auth client."
  [target-path]
  (fn [_req]
    (try
      (let [{:keys [status body]} (prusalink/request target-path)]
        {:status status
         :headers {"Content-Type" "application/json"
                   "Cache-Control" "no-cache"}
         :body body})
      (catch Exception e
        {:status 502
         :headers {"Content-Type" "application/json"
                   "Cache-Control" "no-cache"}
         :body (json/write-str
                {:error (.getMessage e)
                 :auth (prusalink-auth/auth-status)})}))))

(defn- first-header
  "Return the first header value from a PrusaLink response header map."
  [headers header-name default-value]
  (or (first (get headers header-name))
      (first (get headers (str/lower-case header-name)))
      default-value))

(defn- prusalink-media-proxy-handler
  "Proxy safe PrusaLink media paths, currently thumbnails."
  [req]
  (let [prefix "/api/prusalink/proxy"
        uri (:uri req)
        target-path (subs uri (count prefix))]
    (if-not (str/starts-with? target-path "/thumb/")
      {:status 400
       :headers {"Content-Type" "application/json"
                 "Cache-Control" "no-cache"}
       :body (json/write-str {:error "Unsupported PrusaLink proxy path"})}
      (try
        (let [{:keys [status headers body]} (prusalink/request-bytes target-path)]
          {:status status
           :headers {"Content-Type" (first-header headers "content-type" "application/octet-stream")
                     "Cache-Control" "no-cache"}
           :body body})
        (catch Exception e
          {:status 502
           :headers {"Content-Type" "application/json"
                     "Cache-Control" "no-cache"}
           :body (json/write-str {:error (.getMessage e)})})))))

(defn- js-asset-handler
  "Serve compiled ClojureScript assets from resources/js for direct backend access."
  [req]
  (let [uri (:uri req)
        relative-path (subs uri (count "/js/"))
        base-dir (io/file "resources" "js")
        asset-file (io/file base-dir relative-path)
        base-path (.getCanonicalPath base-dir)
        asset-path (.getCanonicalPath asset-file)]
    (if (and (str/starts-with? asset-path base-path)
             (.exists asset-file)
             (.isFile asset-file))
      {:status 200
       :headers {"Content-Type" (cond
                                  (str/ends-with? relative-path ".js") "application/javascript"
                                  (str/ends-with? relative-path ".map") "application/json"
                                  (str/ends-with? relative-path ".edn") "application/edn"
                                  :else "application/octet-stream")
                 "Cache-Control" "no-cache"}
       :body (slurp asset-file)}
      {:status 404
       :headers {"Content-Type" "text/plain"}
       :body "JS asset not found"})))

(defn- app-js-handler
  "Serve the compiled app.js file"
  [_req]
  (let [app-js-file (io/file "resources/app.js")]
    (if (and (.exists app-js-file) (.isFile app-js-file))
      {:status 200
       :headers {"Content-Type" "application/javascript"
                "Last-Modified" (str (java.util.Date. (.lastModified app-js-file)))
                "Cache-Control" "no-cache"}
       :body (slurp app-js-file)}
      (if-let [resource (io/resource "app.js")]
        {:status 200
         :headers {"Content-Type" "application/javascript"
                  "Cache-Control" "no-cache"}
         :body (slurp resource)}
        {:status 404
         :headers {"Content-Type" "text/plain"}
         :body "app.js not found"}))))

(defn- create-routes
  "Create the route map for the HTTP server"
  [telemetry-stream]
  {"/" index-handler
   "/timeline" timeline-handler
   "/dashboard" dashboard-handler
   "/replay" dashboard-handler
   "/ws" (websocket-handler telemetry-stream)
   "/api/telemetry-files" list-telemetry-files-handler
   "/api/prusalink/auth" prusalink-auth-status-handler
   "/api/prusalink/print-state" prusalink-print-state-handler
   "/api/prusalink/status" (prusalink-proxy-handler "/api/v1/status")
   "/api/prusalink/job" (prusalink-proxy-handler "/api/v1/job")
   "/api/prusalink/connection" (prusalink-proxy-handler "/api/connection")
   "/app.js" app-js-handler})

(defn- create-request-handler
  "Create the main request handler that routes requests to appropriate handlers"
  [routes]
  (fn [req]
    (try
      (let [uri (:uri req)]
        (println "Request received:" uri "Method:" (:request-method req))
        (println "Available routes:" (keys routes))
        (println "Route match check:" (contains? routes uri))
        (cond
          ;; Check exact routes first
          (contains? routes uri)
          (do
            (println "Matched route:" uri)
            ((get routes uri) req))
          
          ;; Check for telemetry file loading endpoint
          (str/starts-with? uri "/api/telemetry-file/")
          (do
            (println "Matched telemetry-file pattern")
            (load-telemetry-file-handler req))

          (str/starts-with? uri "/api/prusalink/proxy/")
          (do
            (println "Matched prusalink proxy pattern")
            (prusalink-media-proxy-handler req))

          (str/starts-with? uri "/js/")
          (do
            (println "Matched js asset pattern")
            (js-asset-handler req))
          
          ;; Then check for ClojureScript assets under /app.js/
          (str/starts-with? uri "/app.js/")
          (do
            (println "Matched app.js pattern")
            (cljs-asset-handler req))
          
          :else
          (do
            (println "No route matched for:" uri)
            {:status 404
             :headers {"Content-Type" "text/plain"}
             :body (str "Not found. URI: " uri)})))
      (catch Exception e
        (println "Handler error:" (.getMessage e))
        (.printStackTrace e)
        {:status 500
         :headers {"Content-Type" "text/plain"}
         :body (str "Server error: " (.getMessage e))}))))

(defn- start-http-server
  "Start the HTTP server with the given handler and port"
  [handler port]
  (try
    (println "Attempting to start server on port" port "...")
    (let [srv (http/start-server handler {:port port})]
      (println "Server object created successfully")
      (Thread/sleep 1000) ; Give it time to bind
      (println "Server should be ready, checking status...")
      (println "Server closed?" (try (.isClosed srv) (catch Exception _ "unknown")))
      srv)
    (catch Exception e
      (println "ERROR starting server:" (.getMessage e))
      (.printStackTrace e)
      (throw e))))

(defn start-web-server
  "Start HTTP server with WebSocket support for telemetry streaming.
   
   In development, shadow-cljs serves HTML/JS files on port 9632 and proxies
   all non-static requests (including /ws and /api/*) to this server.
   Access the app via http://localhost:9632 for REPL support.
   
   Returns {:server .. :stop! (fn [])}
   Options:
   - :port (default 8080) - Backend server port (shadow-cljs proxies to this)
   - :telemetry-stream (required) - the processed stream from telemetry server"
  [{:keys [port telemetry-stream]
    :or {port 8080}}]
  (when (nil? telemetry-stream)
    (throw (ex-info "telemetry-stream is required" {})))

  (println "Starting web server with telemetry-stream:" (type telemetry-stream))
  (println "Telemetry stream closed?" (try (s/closed? telemetry-stream) (catch Exception _ "unknown")))

  ;; Set up packet saving consumer
  (setup-packet-saving-consumer telemetry-stream)
  
  ;; Create routes and handler
  (let [stop-prusalink-poller! (start-prusalink-print-state-poller!)
        routes (create-routes telemetry-stream)
        handler (create-request-handler routes)
        server (start-http-server handler port)]
    
    (println (format "Web server started on http://localhost:%d" port))
    (println (format "WebSocket endpoint: ws://localhost:%d/ws" port))
    (println "Waiting for telemetry packets... (make sure your printer is sending UDP packets to port 8514)")
    
    {:server server
     :stop! (fn []
              (stop-prusalink-poller!)
              (.close server)
              ::stopped)}))

(defn -main
  "Start both telemetry server and web server"
  [& args]
  (let [parse-long? (fn [s] (try (Long/parseLong s) (catch Exception _ nil)))
        telemetry-port (or (some-> args first parse-long?) 8514)
        web-port (or (some-> args second parse-long?) 8080)]
    
    (println "Starting Prusa telemetry system...")
    (println (format "Telemetry UDP port: %d" telemetry-port))
    (println (format "Web server port: %d" web-port))
    
    (let [telemetry-srv (telemetry/start-telemetry-server {:port telemetry-port})
          web-srv (start-web-server {:port web-port
                                     :telemetry-stream (:fan-out telemetry-srv)})]
      
      ;; Add shutdown hook
      (.addShutdownHook
       (Runtime/getRuntime)
       (Thread. (fn []
                  (println "\nShutting down...")
                  ((:stop! web-srv))
                  ((:stop! telemetry-srv))
                  (Thread/sleep 100))))
      
      ;; Keep main thread alive - wait for server to close
      (println "Servers running. Press Ctrl+C to stop.")
      (try
        (netty/wait-for-close (:server web-srv))
        (catch InterruptedException _
          (println "\nInterrupted, shutting down...")
          ((:stop! web-srv))
          ((:stop! telemetry-srv)))))))
