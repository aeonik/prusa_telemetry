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
(def ^:private replay-parse-line-budget 25)
(def ^:private replay-max-buffer-chars (* 1024 1024))
(def ^:private replay-resume-buffer-chars (/ replay-max-buffer-chars 2))
(defonce ^:private replay-load-controller (atom nil))

(defn- active-load-token?
  [token]
  (= token (:token @replay-load-controller)))

(defn- clear-active-load! [token]
  (when (active-load-token? token)
    (reset! replay-load-controller nil)))

(defn- replace-active-load! [token controller]
  (let [previous @replay-load-controller]
    (reset! replay-load-controller {:token token
                                    :controller controller})
    (when-let [previous-controller (:controller previous)]
      (.abort previous-controller))))

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

(defn- stream-replay-response! [response archive token filename expected-bytes]
  (let [bytes-total (or (response-content-length response)
                        expected-bytes)
        body (.-body response)]
    (dispatch! {:type :replay/stream-start
                :archive archive
                :token token
                :bytes-total bytes-total})
    (if-not body
      (do
        (clear-active-load! token)
        (dispatch! {:type :replay/load-error
                    :token token
                    :message "This browser does not support streaming replay loads"}))
      (let [stream-reader (.getReader body)
            decoder (js/TextDecoder. "utf-8")
            line-buffer (atom "")
            batch (atom [])
            bytes-loaded (atom 0)
            read-complete? (atom false)
            drain-scheduled? (atom false)
            pump-paused? (atom false)
            finished? (atom false)]
        (letfn [(fail! [error]
                  (when-not @finished?
                    (reset! finished? true)
                    (js/console.error "Error streaming telemetry replay file:" error)
                    (.cancel stream-reader)
                    (clear-active-load! token)
                    (dispatch! {:type :replay/load-error
                                :token token
                                :message (error-message error
                                                        "Error streaming telemetry replay file")})))
                (dispatch-progress! []
                  (dispatch! {:type :replay/stream-progress
                              :token token
                              :bytes-loaded @bytes-loaded
                              :bytes-total bytes-total}))
                (emit-batch! []
                  (let [packets @batch]
                    (when (seq packets)
                      (reset! batch [])
                      (dispatch-replay-batch! token packets @bytes-loaded bytes-total))))
                (next-line! []
                  (let [text @line-buffer
                        idx (.indexOf text "\n")]
                    (when (>= idx 0)
                      (let [line (subs text 0 idx)]
                        (reset! line-buffer (subs text (inc idx)))
                        line))))
                (has-line? []
                  (>= (.indexOf @line-buffer "\n") 0))
                (process-line! [line]
                  (let [line (str/trim line)]
                    (when (seq line)
                      (swap! batch conj (reader/read-string line))
                      (when (>= (count @batch) replay-stream-batch-size)
                        (emit-batch!)))))
                (finish-if-ready! []
                  (when (and @read-complete?
                             (not @finished?)
                             (not (has-line?)))
                    (try
                      (let [tail (str/trim @line-buffer)]
                        (reset! line-buffer "")
                        (when (seq tail)
                          (process-line! tail)))
                      (emit-batch!)
                      (reset! finished? true)
                      (clear-active-load! token)
                      (println "Finished streaming replay" filename)
                      (dispatch! {:type :replay/stream-complete :token token})
                      (catch :default error
                        (fail! error)))))
                (drain! []
                  (when-not @finished?
                    (reset! drain-scheduled? false)
                    (try
                      (loop [processed 0]
                        (when (< processed replay-parse-line-budget)
                          (when-let [line (next-line!)]
                            (process-line! line)
                            (recur (inc processed)))))
                      (if (has-line?)
                        (schedule-drain!)
                        (finish-if-ready!))
                      (resume-pump-if-ready!)
                      (catch :default error
                        (fail! error)))))
                (resume-pump-if-ready! []
                  (when (and @pump-paused?
                             (not @read-complete?)
                             (< (count @line-buffer) replay-resume-buffer-chars))
                    (reset! pump-paused? false)
                    (js/setTimeout pump! 0)))
                (schedule-drain! []
                  (when (and (not @finished?)
                             (not @drain-scheduled?))
                    (reset! drain-scheduled? true)
                    (js/setTimeout drain! 0)))
                (append-text! [text]
                  (when (seq text)
                    (swap! line-buffer str text))
                  (schedule-drain!))
                (finish-read! []
                  (try
                    (let [tail (.decode decoder)]
                      (when (seq tail)
                        (swap! line-buffer str tail)))
                    (reset! read-complete? true)
                    (schedule-drain!)
                    (catch :default error
                      (fail! error))))
                (pump! []
                  (when-not @finished?
                    (-> (.read stream-reader)
                        (.then (fn [result]
                                 (if (.-done result)
                                   (finish-read!)
                                   (try
                                     (let [value (.-value result)
                                           text (.decode decoder value #js {:stream true})]
                                       (swap! bytes-loaded + (.-byteLength value))
                                       (dispatch-progress!)
                                       (append-text! text)
                                       (if (> (count @line-buffer) replay-max-buffer-chars)
                                         (reset! pump-paused? true)
                                         (js/setTimeout pump! 0)))
                                     (catch :default error
                                       (fail! error))))))
                        (.catch fail!))))]
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
  ([date filename]
   (load-telemetry-file-replace date filename nil))
  ([date filename expected-bytes]
   (let [url (str (get-api-base-url) "/api/telemetry-file-raw/" date "/" filename)
         archive (str date ":" filename)
         token (str archive ":" (.now js/Date))
         controller (js/AbortController.)]
     (replace-active-load! token controller)
     (dispatch! {:type :replay/load-start
                 :archive archive
                 :token token
                 :bytes-total expected-bytes})
     (-> (js/fetch url #js {:signal (.-signal controller)})
         (.then (fn [response]
                  (if (.-ok response)
                    (stream-replay-response! response archive token filename expected-bytes)
                    (-> (.json response)
                        (.then (fn [data]
                                 (let [details (js->clj data :keywordize-keys true)]
                                   (println "Replay file error details:" details)
                                   (clear-active-load! token)
                                   (dispatch! {:type :replay/load-error
                                              :token token
                                              :message (or (:error details)
                                                           "Error loading telemetry replay file")}))
                                 (js/Promise.resolve nil))
                               (fn [error]
                                 (println "Error parsing replay error response:" error)
                                 (js/console.error error)
                                 (clear-active-load! token)
                                 (dispatch! {:type :replay/load-error
                                            :token token
                                            :message (or (.-message error)
                                                         "Error loading telemetry replay file")})
                                 (js/Promise.resolve nil)))))))
         (.catch (fn [error]
                   (println "Error loading telemetry replay file:" error)
                   (js/console.error error)
                   (clear-active-load! token)
                   (dispatch! {:type :replay/load-error
                              :token token
                              :message (or (.-message error)
                                           "Error loading telemetry replay file")})))))))
