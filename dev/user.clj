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
   [shadow.cljs.devtools.server :as server]
   [shadow.cljs.devtools.api :as shadow]))

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

(defn start-telemetry!
  "Start the telemetry server (UDP listener).
   Idempotent - safe to call multiple times."
  ([]
   (start-telemetry! {:port 8514}))
  ([opts]
   (when (nil? @telemetry-server)
     (println "Starting telemetry server...")
     (reset! telemetry-server (telemetry/start-telemetry-server opts))
     (println "✓ Telemetry server started on port" (:port opts 8514)))
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
     (reset! web-server (web/start-web-server
                         (assoc opts
                                :telemetry-stream (:processed @telemetry-server))))
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
  "Start all services (telemetry + web).
   Idempotent - safe to call multiple times."
  ([]
   (start! {:telemetry-port 8514 :web-port 8080}))
  ([{:keys [telemetry-port web-port]
     :or {telemetry-port 8514 web-port 8080}}]
   (start-telemetry! {:port telemetry-port})
   (start-web! {:port web-port})
   {:telemetry @telemetry-server
    :web @web-server}))

(defn stop!
  "Stop all services.
   Idempotent - safe to call multiple times."
  []
  (stop-web!)
  (stop-telemetry!))

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
   :web (if @web-server :running :stopped)})

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

  ;; Access service instances
  @telemetry-server
  @web-server)
