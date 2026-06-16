(ns aeonik.prusalink-proxy
  (:require
   [aeonik.prusalink :as prusalink]
   [aeonik.prusalink-auth :as prusalink-auth]
   [aeonik.prusalink-state :as prusalink-state]
   [clojure.data.json :as json]
   [clojure.string :as str]))

(defn auth-status-handler
  "Return a password-safe status for the local PrusaLink auth config."
  [_req]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/write-str (prusalink-auth/auth-status))})

(defn print-state-handler
  "Return the backend's local PrusaLink-derived print/run state."
  [state-atom]
  (fn [_req]
    {:status 200
     :headers {"Content-Type" "application/json"
               "Cache-Control" "no-cache"}
     :body (json/write-str @state-atom)}))

(defn proxy-handler
  "Proxy a PrusaLink API request through the backend Digest-auth client."
  [target-path]
  (fn [_req]
    (try
      (let [{:keys [status body]} (prusalink/request target-path)]
        {:status (if (str/blank? (or body ""))
                   200
                   status)
         :headers {"Content-Type" "application/json"
                   "Cache-Control" "no-cache"}
         :body (if (str/blank? (or body ""))
                 (json/write-str {})
                 body)})
      (catch Exception e
        (println "PrusaLink proxy request failed:" (.getMessage e))
        {:status 502
         :headers {"Content-Type" "application/json"
                   "Cache-Control" "no-cache"}
         :body (json/write-str {:error prusalink-state/request-error})}))))

(defn first-header
  "Return the first header value from a PrusaLink response header map."
  [headers header-name default-value]
  (or (first (get headers header-name))
      (first (get headers (str/lower-case header-name)))
      default-value))

(defn media-proxy-handler
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
          (println "PrusaLink media proxy request failed:" (.getMessage e))
          {:status 502
           :headers {"Content-Type" "application/json"
                     "Cache-Control" "no-cache"}
           :body (json/write-str {:error prusalink-state/request-error})})))))
