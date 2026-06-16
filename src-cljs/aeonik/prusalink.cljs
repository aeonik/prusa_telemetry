(ns aeonik.prusalink
  (:require
   [aeonik.events :refer [dispatch!]]))

(def ^:private status-poll-ms 1000)
(def ^:private job-poll-ms 1000)
(def ^:private print-state-poll-ms 1000)

(defonce status-poller (atom nil))
(defonce job-poller (atom nil))
(defonce print-state-poller (atom nil))

(declare stop-polling!)

(defn- fetch-json!
  "Fetch JSON from a local backend endpoint and dispatch a success event."
  [url success-type & [{:keys [not-found-type]}]]
  (.catch
   (.then
    (.then (js/fetch url #js {:cache "no-store"})
           (fn [response]
             (cond
               (.-ok response)
               (.json response)

               (and not-found-type (= 404 (.-status response)))
               (do
                 (dispatch! {:type not-found-type
                             :received-at (.now js/Date)})
                 (js/Promise.resolve nil))

               :else
               (throw (js/Error. (str "HTTP " (.-status response) " from " url))))))
    (fn [payload]
      (when payload
        (dispatch! {:type success-type
                    :data (js->clj payload :keywordize-keys true)
                    :received-at (.now js/Date)}))))
   (fn [error]
     (dispatch! {:type :prusalink/error
                 :message (.-message error)
                 :received-at (.now js/Date)}))))

(defn fetch-status!
  "Fetch the PrusaLink status snapshot through the backend proxy."
  []
  (fetch-json! "/api/prusalink/status" :prusalink/status-success))

(defn fetch-job!
  "Fetch active PrusaLink job details through the backend proxy."
  []
  (fetch-json! "/api/prusalink/job"
               :prusalink/job-success
               {:not-found-type :prusalink/job-clear}))

(defn fetch-print-state!
  "Fetch the backend-local PrusaLink print/run state."
  []
  (fetch-json! "/api/prusalink/print-state" :prusalink/print-state-success))

(defn start-polling!
  "Deprecated compatibility hook.

   PrusaLink dashboard metadata now arrives through the main WebSocket stream.
   Calling this only clears any legacy timers left alive by a hot reload."
  []
  (stop-polling!))

(defn stop-polling!
  "Stop PrusaLink API polling."
  []
  (when-let [poller @status-poller]
    (js/clearInterval poller)
    (reset! status-poller nil))
  (when-let [poller @job-poller]
    (js/clearInterval poller)
    (reset! job-poller nil))
  (when-let [poller @print-state-poller]
    (js/clearInterval poller)
    (reset! print-state-poller nil)))
