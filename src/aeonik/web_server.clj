(ns aeonik.web-server
  (:require
   [aleph.http :as http]
   [aleph.netty :as netty]
   [manifold.stream :as s]
   [manifold.deferred :as d]
   [aeonik.prusa-telemetry :as telemetry]
   [clojure.data.json :as json]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def ^:private prints-dir (io/file "telemetry-data" "prints"))

;; Track active prints per sender: {sender {:filename "..." :last-packet-time <timestamp>}}
(def ^:private active-prints (atom {}))

;; Print is considered ended if no packets received for this many milliseconds
(def ^:private print-end-timeout-ms (* 10 60 1000)) ; 10 minutes

(defn- ensure-prints-dir! 
  "Ensure the prints directory exists"
  []
  (when-not (.exists prints-dir)
    (.mkdirs prints-dir)
    (println "Created prints directory:" (.getAbsolutePath prints-dir))))

(defn- sanitize-filename 
  "Sanitize filename for use in file system"
  [filename]
  (-> filename
      (str/replace #"[^\w\s\-_\.]" "_")
      (str/replace #"\s+" "_")
      (str/trim)))

(defn- get-print-filename 
  "Extract print_filename from metrics"
  [metrics]
  (some (fn [m]
          (when (= (:name m) "print_filename")
            (or (:value m)
                (when-let [fields (:fields m)]
                  (if (map? fields)
                    (or (get fields "value")
                        (first (vals fields)))
                    nil)))))
        metrics))

(defn- get-active-print-filename 
  "Get the active print filename for a sender, checking for timeouts"
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
            (:filename active-print))))
      nil)))

(defn- set-active-print-filename! 
  "Set the active print filename for a sender"
  [sender filename current-time]
  (swap! active-prints assoc sender {:filename filename :last-packet-time current-time}))

(defn- save-packet-to-file!
  "Save a packet to a file for the given print filename in EDN format (append-only, one packet per line)"
  [packet print-filename]
  (try
    (ensure-prints-dir!)
    (let [sanitized-name (sanitize-filename print-filename)
          now (java.util.Date.)
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
                              :offset-ms (:offset-ms m)
                              :tick-ms (:offset-ms m)  ; Compatibility alias for frontend
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
        new-print-filename (get-print-filename metrics)
        ;; Get active print filename (handles sticky behavior and timeout)
        active-print-filename (get-active-print-filename sender-str current-time)]
    ;; If we see a new print_filename, update the active print
    (when new-print-filename
      (if (= new-print-filename active-print-filename)
        ;; Same print - just update timestamp (already done in get-active-print-filename)
        nil
        ;; New print started - update active print
        (set-active-print-filename! sender-str new-print-filename current-time)))
    ;; Save packet if we have an active print (either from this packet or sticky from previous)
    (let [print-filename-to-use (or new-print-filename active-print-filename)]
      (when print-filename-to-use
        (save-packet-to-file! (telemetry-to-json packet) print-filename-to-use)))))

(defn websocket-handler
  "WebSocket handler that streams telemetry data.
   Each WebSocket connection gets its own subscription to ensure all clients see every packet."
  [processed-bus]
  (fn [req]
    (let [ws-deferred (http/websocket-connection req)]
      ;; Set up the connection when it's ready (async, don't block)
      (d/chain ws-deferred
               (fn [ws]
                 (println "WebSocket client connected")
                 
                 ;; Create subscription stream for this client (each client gets their own copy)
                 (let [client-stream (s/stream 100)]
                   ;; Connect bus to client stream (broadcasts to this client)
                   (s/connect processed-bus client-stream)
                   (s/consume
                    (fn [packet]
                      (try
                        (let [json-data (json/write-str (telemetry-to-json packet))]
                          (s/put! ws json-data))
                        (catch Exception e
                          (println "Error sending WebSocket message:" (.getMessage e))
                          (.printStackTrace e))))
                    client-stream))
                 
                 ;; Handle client messages (if any)
                 (s/consume
                  (fn [msg]
                    (println "Received from client:" msg))
                  ws)
                 
                 ;; Clean up on disconnect
                 (s/on-closed ws
                              (fn []
                                (println "WebSocket client disconnected")))
                 ws))
      ;; Use catch for error handling - ignore stream objects
      (d/catch ws-deferred
               (fn [error]
                 ;; Ignore stream objects - they're not errors
                 ;; Check if it's a stream by checking the class name string
                 (let [error-str (str error)
                       is-stream? (or (.contains error-str "SplicedStream")
                                    (.contains error-str "Stream@"))]
                   (when-not is-stream?
                     (println "WebSocket connection error:" 
                              (cond
                                (instance? Exception error) (.getMessage error)
                                (instance? Throwable error) (.getMessage error)
                                :else (str error)))
                     (when (instance? Throwable error)
                       (.printStackTrace error))))))
      ;; Return the deferred - Aleph will handle it
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
   Creates subscription stream and connects to bus to ensure we see every packet."
  [telemetry-stream]
  (let [saving-stream (s/stream 100)]
    ;; Connect bus to saving stream (broadcasts to this consumer)
    (s/connect telemetry-stream saving-stream)
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
                     (fn [_] (println "Packet saving consumer closed"))
                     (fn [error]
                       (println "ERROR: Packet saving consumer failed:" (.getMessage error))
                       (.printStackTrace error)))
      (println "Packet saving consumer initialized"))))

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
   "/ws" (websocket-handler telemetry-stream)
   "/api/telemetry-files" list-telemetry-files-handler
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
   
   In development, shadow-cljs serves HTML/JS files on port 9630 and proxies
   all non-static requests (including /ws and /api/*) to this server.
   Access the app via http://localhost:9630 for REPL support.
   
   Returns {:server .. :stop! (fn [])}
   Options:
   - :port (default 8080) - Backend server port (shadow-cljs proxies to this)
   - :telemetry-stream (required) - the processed stream from telemetry server"
  [{:keys [port telemetry-stream]
    :or {port 8080}}]
  (when (nil? telemetry-stream)
    (throw (ex-info "telemetry-stream is required" {})))
  
  ;; Set up packet saving consumer
  (setup-packet-saving-consumer telemetry-stream)
  
  ;; Create routes and handler
  (let [routes (create-routes telemetry-stream)
        handler (create-request-handler routes)
        server (start-http-server handler port)]
    
    (println (format "Web server started on http://localhost:%d" port))
    (println (format "WebSocket endpoint: ws://localhost:%d/ws" port))
    
    {:server server
     :stop! (fn []
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
                                     :telemetry-stream (:processed telemetry-srv)})]
      
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

