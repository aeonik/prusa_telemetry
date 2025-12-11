(ns aeonik.web-server
  (:require
   [aleph.http :as http]
   [aleph.netty :as netty]
   [manifold.stream :as s]
   [manifold.deferred :as d]
   [aeonik.prusa-telemetry :as telemetry]
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(defn telemetry-to-json
  "Convert telemetry packet to JSON-serializable format"
  [{:keys [sender received-at prelude metrics display-lines wall-time-str]}]
  {:sender (str sender)
   :received-at (.getTime received-at)
   :prelude prelude
   :metrics (map (fn [m]
                   (cond-> {:name (:name m)
                            :type (name (:type m))
                            :tick (:tick m)
                            :device-time-us (:device-time-us m)
                            :device-time-str (:device-time-str m)}
                     (:value m) (assoc :value (:value m))
                     (:error m) (assoc :error (:error m))
                     (:fields m) (assoc :fields (:fields m))))
                 metrics)
   :display-lines display-lines
   :wall-time-str wall-time-str})

(defn websocket-handler
  "WebSocket handler that streams telemetry data"
  [processed-stream]
  (fn [req]
    (let [ws-deferred (http/websocket-connection req)]
      ;; Set up the connection when it's ready (async, don't block)
      (d/chain ws-deferred
               (fn [ws]
                 (println "WebSocket client connected")
                 
                 ;; Forward processed-stream -> WebSocket
                 (s/consume
                  (fn [packet]
                    (try
                      (let [json-data (json/write-str (telemetry-to-json packet))]
                        (s/put! ws json-data))
                      (catch Exception e
                        (println "Error sending WebSocket message:" (.getMessage e))
                        (.printStackTrace e))))
                  processed-stream)
                 
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

(defn start-web-server
  "Start HTTP server with WebSocket support for telemetry streaming.
   
   In development, shadow-cljs serves HTML/JS files on port 9630 and proxies
   /ws requests to this server. Access the app via http://localhost:9630 for REPL support.
   
   Returns {:server .. :stop! (fn [])}
   Options:
   - :port (default 8080) - WebSocket server port (shadow-cljs proxies to this)
   - :telemetry-stream (required) - the processed stream from telemetry server"
  [{:keys [port telemetry-stream]
    :or {port 8080}}]
  (when (nil? telemetry-stream)
    (throw (ex-info "telemetry-stream is required" {})))
  
  (let [timeline-handler (fn [_req]
                          (if-let [resource (io/resource "timeline.html")]
                            {:status 200
                             :headers {"Content-Type" "text/html"}
                             :body (slurp resource)}
                            {:status 404
                             :headers {"Content-Type" "text/plain"}
                             :body "timeline.html not found"}))
        routes {"/" index-handler
                "/timeline" timeline-handler
                "/ws" (websocket-handler telemetry-stream)
                "/app.js" (fn [_req]
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
                                   :body "app.js not found"}))))}
        
        handler (fn [req]
                  (try
                    (println "Request received:" (:uri req) "Method:" (:request-method req))
                    (cond
                      ;; Check exact routes first
                      (contains? routes (:uri req))
                      ((get routes (:uri req)) req)
                      
                      ;; Then check for ClojureScript assets under /app.js/
                      (str/starts-with? (:uri req) "/app.js/")
                      (cljs-asset-handler req)
                      
                      :else
                      {:status 404
                       :headers {"Content-Type" "text/plain"}
                       :body "Not found"})
                    (catch Exception e
                      (println "Handler error:" (.getMessage e))
                      (.printStackTrace e)
                      {:status 500
                       :headers {"Content-Type" "text/plain"}
                       :body (str "Server error: " (.getMessage e))})))
        
        server (try
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
                   (throw e)))]
    
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

