(ns aeonik.prusalink-auth
  (:require
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
   Returns nil when the file is absent. Throws ex-info when present but invalid."
  ([]
   (read-auth (auth-file-path)))
  ([path]
   (let [file (io/file path)]
     (when (.exists file)
       (normalize-auth (edn/read-string (slurp file)))))))

(defn auth-status
  "Return a password-safe status map for the configured PrusaLink auth file."
  []
  (let [path (auth-file-path)
        file (io/file path)]
    (if-not (.exists file)
      {:path path
       :exists? false
       :configured? false}
      (try
        (let [auth (read-auth path)]
          {:path path
           :exists? true
           :configured? true
           :base-url (:base-url auth)
           :username (:username auth)
           :password? (not (str/blank? (:password auth)))})
        (catch Exception e
          {:path path
           :exists? true
           :configured? false
           :error (.getMessage e)})))))
