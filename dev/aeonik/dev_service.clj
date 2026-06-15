(ns aeonik.dev-service
  "Development service entry point with a managed nREPL.

   This starts the same services exposed by dev/user.clj, but keeps them in a
   long-running process that is easy to supervise from tmux."
  (:require
   [nrepl.server :as nrepl])
  (:gen-class))

(def ^:private default-bind "127.0.0.1")
(def ^:private default-nrepl-port 7888)
(def ^:private default-telemetry-port 8514)
(def ^:private default-web-port 8080)

(defn- parse-port
  "Parse a port value, returning default-port when value is blank or invalid."
  [value default-port]
  (try
    (if (seq (str value))
      (Long/parseLong (str value))
      default-port)
    (catch Exception _
      default-port)))

(defn- configured-port
  "Resolve a port from args, environment, and default value."
  [args index env-name default-port]
  (parse-port (or (nth args index nil)
                  (System/getenv env-name))
              default-port))

(defn- nrepl-server-port
  "Return the actual nREPL port chosen by nREPL."
  [server configured-port]
  (or (:port server)
      (some-> (:server-socket server) .getLocalPort)
      configured-port))

(defn- write-nrepl-port!
  "Write the active backend nREPL port for editor attach workflows."
  [port]
  (spit ".nrepl-port" (str port "\n")))

(defn- stop-system!
  "Stop services and nREPL server tracked in state."
  [state]
  (when-let [user-ns (find-ns 'user)]
    (when-let [stop! (ns-resolve user-ns 'stop!)]
      (try
        (stop!)
        (catch Exception e
          (println "Error stopping telemetry services:" (.getMessage e))))))
  (when-let [server (:nrepl-server @state)]
    (try
      (nrepl/stop-server server)
      (catch Exception e
        (println "Error stopping nREPL:" (.getMessage e))))))

(defn -main
  "Start the development service.

   Optional positional args are:
   1. backend nREPL port
   2. telemetry UDP port
   3. web HTTP port

   Environment overrides are NREPL_BIND, NREPL_PORT, TELEMETRY_PORT, and WEB_PORT."
  [& args]
  (let [bind (or (System/getenv "NREPL_BIND") default-bind)
        nrepl-port (configured-port args 0 "NREPL_PORT" default-nrepl-port)
        telemetry-port (configured-port args 1 "TELEMETRY_PORT" default-telemetry-port)
        web-port (configured-port args 2 "WEB_PORT" default-web-port)
        state (atom {})
        shutdown (fn [] (stop-system! state))]
    (System/setProperty "prusa.telemetry.auto-start" "false")
    (println "Starting Prusa telemetry dev service...")
    (let [server (nrepl/start-server :bind bind :port nrepl-port)
          actual-nrepl-port (nrepl-server-port server nrepl-port)]
      (swap! state assoc :nrepl-server server)
      (write-nrepl-port! actual-nrepl-port)
      (println (format "Backend nREPL: %s:%d" bind actual-nrepl-port))
      ((requiring-resolve 'user/start!) {:telemetry-port telemetry-port
                                         :web-port web-port})
      (println (format "Telemetry UDP: %d" telemetry-port))
      (println (format "Backend HTTP: http://localhost:%d" web-port))
      (println "Use (user/status), (user/restart!), and (user/stop!) from the backend REPL.")
      (.addShutdownHook (Runtime/getRuntime) (Thread. shutdown))
      @(promise))))
