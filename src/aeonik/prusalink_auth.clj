(ns aeonik.prusalink-auth
  (:require
   [aeonik.config :as config]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def default-auth-file "config/prusalink.edn")

(defn auth-file-path
  "Return the PrusaLink auth file path.
   PRUSALINK_AUTH_FILE can override the local default."
  []
  (or (some-> (System/getenv "PRUSALINK_AUTH_FILE")
              str/trim
              not-empty)
      default-auth-file))

(defn- trim-value
  "Trim a configured value while keeping nil values nil."
  [value]
  (when (some? value)
    (str/trim (str value))))

(defn- normalize-base-url
  "Normalize the configured printer API URL."
  [base-url]
  (some-> base-url
          trim-value
          (str/replace #"/+$" "")))

(defn normalize-auth
  "Normalize and validate a raw PrusaLink auth map."
  [auth]
  (let [normalized {:base-url (normalize-base-url (or (:base-url auth) (:url auth)))
                    :username (trim-value (:username auth))
                    :password (trim-value (:password auth))}
        missing (->> [:base-url :username :password]
                     (filter #(str/blank? (get normalized %)))
                     vec)]
    (when (seq missing)
      (throw (ex-info "Invalid PrusaLink auth config"
                      {:missing missing})))
    normalized))

(defn read-auth
  "Read PrusaLink auth from disk.
   Returns nil when auth is absent. Throws ex-info when present but invalid.

   Precedence:
   1. PRUSALINK_AUTH_FILE when explicitly set
   2. :prusalink in PRUSA_CONFIG/config/prusa-telemetry.edn
   3. legacy config/prusalink.edn"
  ([]
   (if (System/getenv "PRUSALINK_AUTH_FILE")
     (read-auth (auth-file-path))
     (or (some-> (config/load-config)
                 config/configured-prusalink-auth
                 normalize-auth)
         (read-auth default-auth-file))))
  ([path]
   (let [file (io/file path)]
     (when (.exists file)
       (normalize-auth (edn/read-string (slurp file)))))))

(defn auth-status
  "Return a password-safe status map for the configured PrusaLink auth file."
  []
  (let [explicit-path (System/getenv "PRUSALINK_AUTH_FILE")
        path (auth-file-path)
        file (io/file path)]
    (if (and explicit-path (not (.exists file)))
      {:source :auth-file
       :path path
       :exists? false
       :configured? false}
      (try
        (if-let [auth (read-auth)]
          {:source (cond
                     explicit-path :auth-file
                     (config/configured-prusalink-auth (config/load-config)) :main-config
                     :else :legacy-auth-file)
           :path (when explicit-path path)
           :exists? true
           :configured? true
           :base-url (:base-url auth)
           :username (:username auth)
           :password? (not (str/blank? (:password auth)))}
          {:source :none
           :path path
           :exists? (.exists file)
           :configured? false})
        (catch Exception e
          {:source :error
           :path path
           :exists? (.exists file)
           :configured? false
           :error (.getMessage e)})))))
