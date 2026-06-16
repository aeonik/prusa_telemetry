(ns aeonik.config
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def default-config-file "config/prusa-telemetry.edn")

(def default-config
  {:telemetry {:port 8514}
   :http {:host "0.0.0.0"
          :port 8080}
   :archive {:prints-dir "telemetry-data/prints"
             :print-end-timeout-ms (* 10 60 1000)}
   :prusalink {:base-url nil
               :username nil
               :password nil}})

(defn config-file-path
  "Return the operator config path.
   PRUSA_CONFIG or the prusa.config system property can override the default."
  []
  (or (some-> (System/getenv "PRUSA_CONFIG") str/trim not-empty)
      (some-> (System/getProperty "prusa.config") str/trim not-empty)
      default-config-file))

(defn- parse-long?
  [value]
  (try
    (when (some? value)
      (Long/parseLong (str/trim (str value))))
    (catch Exception _
      nil)))

(defn- blank->nil
  [value]
  (some-> value str str/trim not-empty))

(defn deep-merge
  "Recursively merge maps from left to right."
  [& maps]
  (letfn [(merge-entry [left right]
            (if (and (map? left) (map? right))
              (merge-with merge-entry left right)
              right))]
    (reduce merge-entry {} maps)))

(defn read-config-file
  "Read an EDN config file, returning nil when the file is absent."
  ([]
   (read-config-file (config-file-path)))
  ([path]
   (let [file (io/file path)]
     (when (.exists file)
       (edn/read-string (slurp file))))))

(defn env-overrides
  "Return config overrides from environment variables."
  []
  (cond-> {}
    (parse-long? (System/getenv "TELEMETRY_PORT"))
    (assoc-in [:telemetry :port] (parse-long? (System/getenv "TELEMETRY_PORT")))

    (parse-long? (System/getenv "WEB_PORT"))
    (assoc-in [:http :port] (parse-long? (System/getenv "WEB_PORT")))

    (blank->nil (System/getenv "WEB_HOST"))
    (assoc-in [:http :host] (blank->nil (System/getenv "WEB_HOST")))

    (blank->nil (System/getenv "TELEMETRY_DATA_DIR"))
    (assoc-in [:archive :prints-dir] (blank->nil (System/getenv "TELEMETRY_DATA_DIR")))

    (parse-long? (System/getenv "PRINT_END_TIMEOUT_MS"))
    (assoc-in [:archive :print-end-timeout-ms]
              (parse-long? (System/getenv "PRINT_END_TIMEOUT_MS")))

    (blank->nil (System/getenv "PRUSALINK_URL"))
    (assoc-in [:prusalink :base-url] (blank->nil (System/getenv "PRUSALINK_URL")))

    (blank->nil (System/getenv "PRUSALINK_USERNAME"))
    (assoc-in [:prusalink :username] (blank->nil (System/getenv "PRUSALINK_USERNAME")))

    (blank->nil (System/getenv "PRUSALINK_PASSWORD"))
    (assoc-in [:prusalink :password] (blank->nil (System/getenv "PRUSALINK_PASSWORD")))))

(defn load-config
  "Load runtime config from defaults, optional EDN file, and env overrides."
  ([]
   (load-config (config-file-path)))
  ([path]
   (deep-merge default-config
               (or (read-config-file path) {})
               (env-overrides))))

(defn telemetry-port
  "Return the configured telemetry UDP port."
  [config]
  (or (parse-long? (get-in config [:telemetry :port])) 8514))

(defn http-port
  "Return the configured HTTP port."
  [config]
  (or (parse-long? (get-in config [:http :port])) 8080))

(defn http-host
  "Return the configured HTTP bind host."
  [config]
  (or (blank->nil (get-in config [:http :host])) "0.0.0.0"))

(defn prints-dir
  "Return the configured telemetry archive directory as a File."
  [config]
  (io/file (or (blank->nil (get-in config [:archive :prints-dir]))
               "telemetry-data/prints")))

(defn print-end-timeout-ms
  "Return the configured sticky print timeout in milliseconds."
  [config]
  (or (parse-long? (get-in config [:archive :print-end-timeout-ms]))
      (* 10 60 1000)))

(defn configured-prusalink-auth
  "Return a PrusaLink auth map when the main config contains one."
  [config]
  (let [auth (:prusalink config)]
    (when (some blank->nil (vals (select-keys auth [:base-url :url :username :password])))
      auth)))

(defn validation-warnings
  "Return non-fatal operator warnings for config that deserves attention."
  [config]
  (cond-> []
    (#{"0.0.0.0" "::"} (http-host config))
    (conj "HTTP is bound to all interfaces; expose it only on a trusted network or behind a reverse proxy.")

    (not (configured-prusalink-auth config))
    (conj "PrusaLink auth is not configured; dashboard print progress and run IDs may be unavailable.")))

(defn print-startup-summary!
  "Print operator-facing startup config, omitting secrets."
  [config]
  (println (format "Telemetry UDP port: %d" (telemetry-port config)))
  (println (format "HTTP bind: %s:%d" (http-host config) (http-port config)))
  (println "Telemetry archive:" (.getPath (prints-dir config)))
  (when-let [base-url (some-> (configured-prusalink-auth config)
                              (select-keys [:base-url :url])
                              vals
                              first
                              blank->nil)]
    (println "PrusaLink URL:" base-url))
  (doseq [warning (validation-warnings config)]
    (println "WARNING:" warning)))
