(ns aeonik.prusalink-state
  (:require [aeonik.prusalink :as prusalink]))

(def initial-state
  {:available? false
   :active? nil
   :run-id nil
   :status nil
   :job nil
   :updated-at nil})

(def request-error "PrusaLink request failed")
(def poll-interval-ms 1000)

(defn new-run-id
  "Create a local print-run identifier from the observed PrusaLink job."
  [job-id now-ms]
  (let [stamp (.format (java.text.SimpleDateFormat. "yyyyMMdd-HHmmss-SSS")
                       (java.util.Date. now-ms))]
    (if (some? job-id)
      (str "job-" job-id "_run-" stamp)
      (str "run-" stamp))))

(defn run-started?
  "Return true when status indicates a new local print run.

   PrusaLink job ids can be reused when manually reprinting, so also detect
   elapsed-time or progress rollbacks."
  [previous-state job]
  (let [previous-time (:time-printing previous-state)
        current-time (:time_printing job)
        previous-progress (:progress previous-state)
        current-progress (:progress job)]
    (or (not (:active? previous-state))
        (not= (:job-id previous-state) (:id job))
        (and (number? previous-time)
             (number? current-time)
             (< current-time (- previous-time 5)))
        (and (number? previous-progress)
             (number? current-progress)
             (< current-progress (- previous-progress 1.0))))))

(defn status->print-state
  "Convert a PrusaLink status JSON map into local print-state."
  ([status-json previous-state]
   (status->print-state status-json previous-state (System/currentTimeMillis)))
  ([{:keys [printer job]} previous-state now-ms]
   (let [active? (boolean job)
         run-id (when active?
                  (if (run-started? previous-state job)
                    (new-run-id (:id job) now-ms)
                    (:run-id previous-state)))]
     {:available? true
      :active? active?
      :state (:state printer)
      :job-id (:id job)
      :run-id run-id
      :progress (:progress job)
      :time-printing (:time_printing job)
      :updated-at now-ms})))

(defn- refresh-options
  [opts-or-status-fn]
  (if (fn? opts-or-status-fn)
    {:status-fn opts-or-status-fn
     :job-fn prusalink/job}
    (merge {:status-fn prusalink/status
            :job-fn prusalink/job}
           opts-or-status-fn)))

(defn- fetch-active-job
  [job-fn active?]
  (when active?
    (try
      (let [{:keys [status json]} (job-fn)]
        (cond
          (and (<= 200 status 299) json)
          {:job json}

          (and (<= 200 status 299) (nil? json))
          {:job nil}

          (= 404 status)
          {:job nil}

          :else
          {:job nil
           :job-error (str "HTTP " status)}))
      (catch Exception e
        (println "PrusaLink job poll failed:" (.getMessage e))
        {:job nil
         :job-error request-error}))))

(defn refresh!
  "Poll PrusaLink for the current dashboard print state."
  ([state-atom]
   (refresh! state-atom {}))
  ([state-atom opts-or-status-fn]
   (let [{:keys [status-fn job-fn]} (refresh-options opts-or-status-fn)]
     (try
       (let [{:keys [status json]} (status-fn)]
         (if (<= 200 status 299)
           (let [print-state (status->print-state json @state-atom)
                 {:keys [job job-error]} (fetch-active-job job-fn (:active? print-state))]
             (reset! state-atom
                     (cond-> (assoc print-state
                                    :status json
                                    :job job
                                    :error nil)
                       job-error
                       (assoc :job-error job-error))))
           (swap! state-atom assoc
                  :available? false
                  :active? nil
                  :status nil
                  :job nil
                  :error (str "HTTP " status)
                  :updated-at (System/currentTimeMillis))))
       (catch Exception e
         (println "PrusaLink print-state poll failed:" (.getMessage e))
         (swap! state-atom assoc
                :available? false
                :active? nil
                :status nil
                :job nil
                :error request-error
                :updated-at (System/currentTimeMillis)))))))

(defn start-poller!
  "Start polling PrusaLink print state.

   Returns a stop function."
  ([state-atom]
   (start-poller! state-atom {}))
  ([state-atom {:keys [poll-ms refresh-fn]
                :or {poll-ms poll-interval-ms
                     refresh-fn refresh!}}]
   (let [running? (atom true)
         poller (future
                  (while @running?
                    (refresh-fn state-atom)
                    (Thread/sleep poll-ms)))]
     (fn []
       (reset! running? false)
       (future-cancel poller)))))
