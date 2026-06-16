(ns aeonik.web-server
  (:require
   [aleph.http :as http]
   [aleph.netty :as netty]
   [manifold.stream :as s]
   [manifold.deferred :as d]
   [aeonik.archive :as archive]
   [aeonik.prusa-telemetry :as telemetry]
   [aeonik.prusalink-proxy :as prusalink-proxy]
   [aeonik.prusalink-state :as prusalink-state]
   [aeonik.ws-bridge :as ws-bridge]
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(defonce ^:private prusalink-print-state
  (atom prusalink-state/initial-state))

(defn- current-archive-state-shape
  "Add newly introduced archive state slots to an existing defonce state."
  [state]
  (cond-> state
    (nil? (:telemetry-print-states state))
    (assoc :telemetry-print-states (atom {}))))

(defonce ^:private archive-state
  (archive/make-state {:prusalink-state prusalink-print-state}))

(alter-var-root #'archive-state current-archive-state-shape)

(defonce ^:private live-handler
  (atom nil))

(defonce ^:private live-config
  (atom nil))

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
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (json/write-str (archive/list-telemetry-files! archive-state))}
    (catch Exception e
      (println "Error listing telemetry files:" (.getMessage e))
      (.printStackTrace e)
      {:status 500
       :headers {"Content-Type" "application/json"}
       :body (json/write-str {:error "Unable to list telemetry files"})})))

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
              result (archive/read-telemetry-file archive-state date filename)]
          (case (:status result)
            :invalid-path
            {:status 400
             :headers {"Content-Type" "application/json"}
             :body (json/write-str {:error "Invalid archive path"})}

            :ok
            {:status 200
             :headers {"Content-Type" "application/json"}
             :body (json/write-str (:packets result))}

            :not-found
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
       :body (json/write-str {:error "Unable to load telemetry file"})})))

(defn raw-telemetry-file-handler
  "Stream a raw line-delimited EDN telemetry archive file."
  [req]
  (try
    (let [uri (:uri req)
          match (re-matches #"/api/telemetry-file-raw/([^/]+)/(.+)" uri)]
      (if match
        (let [[_ date filename] match
              file-path (archive/archive-file (:prints-dir archive-state) date filename)]
          (cond
            (nil? file-path)
            {:status 400
             :headers {"Content-Type" "application/json"}
             :body (json/write-str {:error "Invalid archive path"})}

            (not (and (.exists file-path) (.isFile file-path)))
            {:status 404
             :headers {"Content-Type" "application/json"}
             :body (json/write-str {:error "File not found"})}

            :else
            {:status 200
             :headers {"Content-Type" "application/edn; charset=utf-8"
                       "Content-Length" (str (.length file-path))
                       "Access-Control-Allow-Origin" "*"
                       "Access-Control-Expose-Headers" "Content-Length"
                       "Cache-Control" "no-store"}
             :body file-path}))
        {:status 400
         :headers {"Content-Type" "application/json"}
         :body (json/write-str {:error "Invalid request format"})}))
    (catch Exception e
      (println "Error streaming telemetry file:" (.getMessage e))
      (.printStackTrace e)
      {:status 500
       :headers {"Content-Type" "application/json"}
       :body (json/write-str {:error "Unable to stream telemetry file"})})))

(defn- setup-packet-saving-consumer
  "Set up a single consumer for saving packets (runs once per packet, not per WebSocket client).
   Creates subscription stream and connects to telemetry stream to ensure we see every packet.
   telemetry-stream: manifold stream from telemetry server (fan-out stream).

   Returns a stop function that closes the saving branch."
  [telemetry-stream]
  (let [saving-stream (s/stream 100)
        _ (println "Setting up packet saving consumer, connecting to telemetry-stream...")
        _ (s/connect telemetry-stream
                     saving-stream
                     {:description "fan-out → saving-stream"
                      :upstream? false
                      :downstream? true})]
    (let [saving-consumer (s/consume 
                           (fn [packet]
                             (try
                               (archive/handle-packet-saving! archive-state packet)
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
                       (.printStackTrace error)))
      (fn stop-saving-consumer! []
        (when-not (s/closed? saving-stream)
          (s/close! saving-stream))))))

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
  "Create the route map for the HTTP server.

   Route entries intentionally call through Vars or small wrappers so namespace
   reloads update request behavior without rebuilding the route map."
  [telemetry-stream]
  {"/" #'index-handler
   "/timeline" #'timeline-handler
   "/dashboard" #'dashboard-handler
   "/replay" #'dashboard-handler
   "/ws" (fn [req]
           ((ws-bridge/websocket-handler telemetry-stream
                                             {:prusalink-state prusalink-print-state})
            req))
   "/api/telemetry-files" #'list-telemetry-files-handler
   "/api/prusalink/auth" #'prusalink-proxy/auth-status-handler
   "/api/prusalink/print-state" (fn [req]
                                  ((prusalink-proxy/print-state-handler prusalink-print-state) req))
   "/api/prusalink/status" (fn [req]
                             ((prusalink-proxy/proxy-handler "/api/v1/status") req))
   "/api/prusalink/job" (fn [req]
                          ((prusalink-proxy/proxy-handler "/api/v1/job") req))
   "/api/prusalink/connection" (fn [req]
                                 ((prusalink-proxy/proxy-handler "/api/connection") req))
   "/app.js" #'app-js-handler})

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
          
          (str/starts-with? uri "/api/telemetry-file-raw/")
          (do
            (println "Matched telemetry-file-raw pattern")
            (raw-telemetry-file-handler req))

          ;; Check for telemetry file loading endpoint
          (str/starts-with? uri "/api/telemetry-file/")
          (do
            (println "Matched telemetry-file pattern")
            (load-telemetry-file-handler req))

          (str/starts-with? uri "/api/prusalink/proxy/")
          (do
            (println "Matched prusalink proxy pattern")
            (prusalink-proxy/media-proxy-handler req))

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
         :body "Server error"}))))

(defn- build-request-handler
  "Build a fresh request handler from the current code and runtime inputs."
  [telemetry-stream]
  (-> telemetry-stream
      create-routes
      create-request-handler))

(defn reload-request-handler!
  "Rebuild the live request handler without closing the HTTP listener.

   This is the backend half of the hot-reload workflow. Reload namespaces first,
   then call this function so the stable Aleph handler delegates to fresh route
   and WebSocket implementations."
  []
  (if-let [{:keys [telemetry-stream]} @live-config]
    (do
      (reset! live-handler (build-request-handler telemetry-stream))
      {:status :reloaded})
    {:status :not-running}))

(defn- unavailable-handler
  "Return a 503 before the live handler is installed."
  [_req]
  {:status 503
   :headers {"Content-Type" "text/plain"}
   :body "Web server handler is not ready"})

(defn- live-request-handler
  "Stable handler passed to Aleph.

   Aleph keeps this function object for the life of the listener, so it only
   derefs live-handler and never closes over route tables directly."
  [req]
  ((or @live-handler unavailable-handler) req))

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

  ;; Create a stable delegating handler. The handler implementation itself can
  ;; be refreshed by calling reload-request-handler! after namespace reloads.
  (let [stop-saving-consumer! (setup-packet-saving-consumer telemetry-stream)
        stop-prusalink-poller! (prusalink-state/start-poller! prusalink-print-state)
        _ (reset! live-config {:port port
                               :telemetry-stream telemetry-stream})
        _ (reload-request-handler!)
        server (start-http-server live-request-handler port)]
    
    (println (format "Web server started on http://localhost:%d" port))
    (println (format "WebSocket endpoint: ws://localhost:%d/ws" port))
    (println "Waiting for telemetry packets... (make sure your printer is sending UDP packets to port 8514)")
    
    {:server server
     :config {:port port}
     :reload! reload-request-handler!
     :stop! (fn []
              (stop-prusalink-poller!)
              (stop-saving-consumer!)
              (reset! live-config nil)
              (reset! live-handler nil)
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
