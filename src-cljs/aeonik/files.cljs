(ns aeonik.files
  (:require [aeonik.events :refer [dispatch!]]
            [cljs.reader :as reader]
            [clojure.string :as str]))

(defn- get-api-base-url
  "Get the base URL for API calls.
   Uses relative URLs - shadow-cljs proxies API requests to backend automatically."
  []
  (str js/location.protocol "//" js/location.host))

(def ^:private replay-stream-batch-size 100)

(defn- response-content-length [response]
  (when-let [header (.get (.-headers response) "content-length")]
    (let [value (js/parseInt header 10)]
      (when-not (js/isNaN value)
        value))))

(defn- error-message [error fallback]
  (or (some-> error .-message)
      fallback))

(defn- dispatch-replay-batch! [token packets bytes-loaded bytes-total]
  (when (seq packets)
    (dispatch! {:type :replay/stream-batch
                :token token
                :packets packets
                :bytes-loaded bytes-loaded
                :bytes-total bytes-total})))

(defn- stream-replay-response! [response archive token filename]
  (let [bytes-total (response-content-length response)
        body (.-body response)]
    (dispatch! {:type :replay/stream-start
                :archive archive
                :token token
                :bytes-total bytes-total})
    (if-not body
      (dispatch! {:type :replay/load-error
                  :message "This browser does not support streaming replay loads"})
      (let [stream-reader (.getReader body)
            decoder (js/TextDecoder. "utf-8")
            buffer (atom "")
            batch (atom [])
            bytes-loaded (atom 0)]
        (letfn [(fail! [error]
                  (js/console.error "Error streaming telemetry replay file:" error)
                  (.cancel stream-reader)
                  (dispatch! {:type :replay/load-error
                              :message (error-message error
                                                      "Error streaming telemetry replay file")}))
                (emit-batch! []
                  (let [packets @batch]
                    (when (seq packets)
                      (reset! batch [])
                      (dispatch-replay-batch! token packets @bytes-loaded bytes-total))))
                (process-line! [line]
                  (let [line (str/trim line)]
                    (when (seq line)
                      (swap! batch conj (reader/read-string line))
                      (when (>= (count @batch) replay-stream-batch-size)
                        (emit-batch!)))))
                (process-text! [text]
                  (let [text (str @buffer text)
                        lines (str/split text #"\r?\n")]
                    (reset! buffer (last lines))
                    (doseq [line (butlast lines)]
                      (process-line! line))))
                (finish! []
                  (try
                    (let [tail (.decode decoder)]
                      (when (seq tail)
                        (process-text! tail)))
                    (when (seq @buffer)
                      (process-line! @buffer))
                    (emit-batch!)
                    (println "Finished streaming replay" filename)
                    (dispatch! {:type :replay/stream-complete :token token})
                    (catch :default error
                      (fail! error))))
                (pump! []
                  (-> (.read stream-reader)
                      (.then (fn [result]
                               (if (.-done result)
                                 (finish!)
                                 (try
                                   (let [value (.-value result)
                                         text (.decode decoder value #js {:stream true})]
                                     (swap! bytes-loaded + (.-byteLength value))
                                     (process-text! text)
                                     (js/setTimeout pump! 0))
                                   (catch :default error
                                     (fail! error))))))
                      (.catch fail!)))]
          (pump!))))))

(defn fetch-available-files!
  "Fetch list of available telemetry files from the server and update app-state"
  []
  (let [url (str (get-api-base-url) "/api/telemetry-files")]
    (println "Fetching telemetry files from:" url)
    (-> (js/fetch url)
        (.then (fn [response]
                 (println "Response status:" (.-status response))
                 (if (.-ok response)
                   (-> (.json response)
                       (.then (fn [data]
                               (let [all-files (js->clj data :keywordize-keys true)
                                     ;; Filter out hidden files (starting with .)
                                     files (filter (fn [file-info]
                                                   (not (str/starts-with? (:filename file-info) ".")))
                                                 all-files)]
                                 (println "Parsed files:" (count all-files) ", after filtering:" (count files))
                                 (println "Files to set:" files)
                                 (dispatch! {:type :files/set-available
                                            :files files})
                                 (println "Dispatched files/set-available"))))
                       (.catch (fn [error]
                                (println "Error parsing file list:" error)
                                (js/console.error error))))
                   (-> (.text response)
                       (.then (fn [text]
                               (println "Error response body:" text)))
                       (.catch (fn [error]
                                (println "Error reading error response:" error)))))))
        (.catch (fn [error]
                 (println "Error fetching telemetry files:" error)
                 (js/console.error error))))))

(defn load-telemetry-file
  "Load a telemetry file from the server and dispatch events"
  [date filename]
  (let [url (str (get-api-base-url) "/api/telemetry-file/" date "/" filename)]
    (-> (js/fetch url)
        (.then (fn [response]
                 (if (.-ok response)
                   (-> (.json response)
                       (.then (fn [data]
                               (let [packets (js->clj data :keywordize-keys true)]
                                 (println (str "Loading " (count packets) " packets from " filename))
                                 (dispatch! {:type :data/load-file
                                            :packets packets})
                                 (println (str "Dispatched load-file event for " filename)))))
                       (.catch (fn [error]
                                (println "Error parsing telemetry file:" error)
                                (js/console.error error))))
                   (-> (.json response)
                       (.then (fn [data]
                               (println "Error details:" (js->clj data :keywordize-keys true))
                               (js/Promise.resolve nil))
                              (fn [error]
                                (println "Error parsing error response:" error)
                                (js/console.error error)
                                (js/Promise.resolve nil)))))))
        (.catch (fn [error]
                 (println "Error loading telemetry file:" error)
                 (js/console.error error))))))

(defn load-telemetry-file-replace
  "Load a telemetry file as the active replay dataset."
  [date filename]
  (let [url (str (get-api-base-url) "/api/telemetry-file-raw/" date "/" filename)
        archive (str date ":" filename)
        token (str archive ":" (.now js/Date))]
    (dispatch! {:type :replay/load-start :archive archive})
    (-> (js/fetch url)
        (.then (fn [response]
                 (if (.-ok response)
                   (stream-replay-response! response archive token filename)
                   (-> (.json response)
                       (.then (fn [data]
                                (let [details (js->clj data :keywordize-keys true)]
                                  (println "Replay file error details:" details)
                                  (dispatch! {:type :replay/load-error
                                             :message (or (:error details)
                                                          "Error loading telemetry replay file")}))
                                (js/Promise.resolve nil))
                              (fn [error]
                                (println "Error parsing replay error response:" error)
                                (js/console.error error)
                                (dispatch! {:type :replay/load-error
                                           :message (or (.-message error)
                                                        "Error loading telemetry replay file")})
                                (js/Promise.resolve nil)))))))
        (.catch (fn [error]
                  (println "Error loading telemetry replay file:" error)
                  (js/console.error error)
                  (dispatch! {:type :replay/load-error
                             :message (or (.-message error)
                                          "Error loading telemetry replay file")}))))))
