(ns aeonik.archive
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def default-prints-dir (io/file "telemetry-data" "prints"))
(def default-print-end-timeout-ms (* 10 60 1000))
(def missing-print-filename ::missing-print-filename)
(def missing-telemetry-print-state ::missing-telemetry-print-state)

(defn make-state
  "Create archive service state.

   The state map keeps mutable run tracking explicit at the boundary while
   leaving filename parsing, path validation, and most decisions pure."
  ([] (make-state {}))
  ([{:keys [prints-dir active-prints telemetry-print-states
            prusalink-state print-end-timeout-ms
            now-ms log-fn]}]
   {:prints-dir (or prints-dir default-prints-dir)
    :active-prints (or active-prints (atom {}))
    :telemetry-print-states (or telemetry-print-states (atom {}))
    :prusalink-state (or prusalink-state (atom {:available? false
                                                :active? nil}))
    :print-end-timeout-ms (or print-end-timeout-ms default-print-end-timeout-ms)
    :now-ms (or now-ms #(System/currentTimeMillis))
    :log-fn (or log-fn println)}))

(defn ensure-prints-dir!
  "Ensure the archive root directory exists."
  [{:keys [prints-dir]}]
  (when-not (.exists prints-dir)
    (.mkdirs prints-dir)
    (println "Created prints directory:" (.getAbsolutePath prints-dir))))

(defn child-path?
  "Return true when child resolves inside base after canonicalization."
  [base child]
  (let [base-path (.getCanonicalPath base)
        child-path (.getCanonicalPath child)
        base-prefix (str base-path java.io.File/separator)]
    (or (= child-path base-path)
        (str/starts-with? child-path base-prefix))))

(defn valid-archive-date?
  "Return true when date is a telemetry archive date directory."
  [date]
  (boolean (re-matches #"\d{4}-\d{2}-\d{2}" (or date ""))))

(defn valid-archive-filename?
  "Return true when filename is a single EDN archive filename."
  [filename]
  (and (string? filename)
       (str/ends-with? filename ".edn")
       (not (str/blank? filename))
       (not (str/includes? filename "/"))
       (not (str/includes? filename "\\"))))

(defn archive-file
  "Return a canonicalized archive file when date and filename are safe."
  ([date filename]
   (archive-file default-prints-dir date filename))
  ([prints-dir date filename]
   (when (and (valid-archive-date? date)
              (valid-archive-filename? filename))
     (let [file (io/file prints-dir date filename)]
       (when (child-path? prints-dir file)
         file)))))

(defn normalize-print-filename
  "Normalize the firmware print filename metric.

   Blank names mean idle/no active print. Some long string metrics can arrive
   with an opening quote but no closing quote, so trim boundary quotes here."
  [filename]
  (when (some? filename)
    (let [normalized (-> (str filename)
                         (str/trim)
                         (str/replace #"^\"+" "")
                         (str/replace #"\"+$" "")
                         (str/trim))]
      (not-empty normalized))))

(defn sanitize-filename
  "Sanitize filename for use in file system."
  [filename]
  (when-let [filename (normalize-print-filename filename)]
    (not-empty
     (-> filename
         (str/replace #"[^\w\s\-_\.]" "_")
         (str/replace #"\s+" "_")
         (str/replace #"^\.+" "")
         (str/replace #"\.+$" "")
         (str/trim)))))

(defn archive-file-for-save!
  "Return the archive file for a print filename and create its date directory."
  [state print-filename]
  (when-let [sanitized-name (sanitize-filename print-filename)]
    (let [now (java.util.Date.)
          date-fmt (java.text.SimpleDateFormat. "yyyy-MM-dd")
          date-str (.format date-fmt now)
          filename (str sanitized-name ".edn")
          file (archive-file (:prints-dir state) date-str filename)]
      (when file
        (.mkdirs (.getParentFile file))
        file))))

(defn metric-value
  "Extract a metric value from either :value or structured fields."
  [metric]
  (or (:value metric)
      (when-let [fields (:fields metric)]
        (when (map? fields)
          (or (get fields "value")
              (first (vals fields)))))))

(defn get-print-filename
  "Extract raw print_filename from metrics.

   Returns `missing-print-filename` when the metric is absent so an explicit
   blank filename can be distinguished from no filename update."
  [metrics]
  (reduce (fn [_ metric]
            (if (= (:name metric) "print_filename")
              (reduced (metric-value metric))
              missing-print-filename))
          missing-print-filename
          metrics))

(defn get-telemetry-print-state
  "Extract the firmware print-active signal from metrics.

   Returns `missing-telemetry-print-state` when the metric is absent so sticky
   idle state can be distinguished from unknown firmware support."
  [metrics]
  (reduce (fn [_ metric]
            (if (= (:name metric) "is_printing")
              (reduced (metric-value metric))
              missing-telemetry-print-state))
          missing-telemetry-print-state
          metrics))

(defn print-active-value?
  "Return true when a firmware print-active value is truthy."
  [value]
  (cond
    (number? value) (pos? value)
    (string? value) (#{"1" "true" "yes" "on"} (str/lower-case (str/trim value)))
    :else (boolean value)))

(defn active-prusalink-run-suffix
  "Return the active local print-run suffix when PrusaLink reports a live job."
  [state]
  (let [{:keys [available? active? run-id job-id]} @(:prusalink-state state)]
    (when (and available? active?)
      (or run-id
          (when (some? job-id) (str "job-" job-id))))))

(defn prusalink-active?
  "Return true when PrusaLink currently reports an active job."
  [state]
  (true? (:active? @(:prusalink-state state))))

(defn print-save-filename
  "Build the archive filename for a print run.

   A local run suffix distinguishes repeated attempts of the same G-code file."
  [state filename]
  (when-let [filename (normalize-print-filename filename)]
    (if-let [run-suffix (active-prusalink-run-suffix state)]
      (str filename "_" run-suffix)
      filename)))

(defn get-active-print
  "Get the active print for a sender, checking for timeouts."
  [state sender current-time]
  (let [{:keys [active-prints print-end-timeout-ms]} state
        active-print (get @active-prints sender)]
    (if active-print
      (let [time-since-last-packet (- current-time (:last-packet-time active-print))]
        (if (> time-since-last-packet print-end-timeout-ms)
          (do
            (swap! active-prints dissoc sender)
            nil)
          (do
            (swap! active-prints assoc sender (assoc active-print :last-packet-time current-time))
            (get @active-prints sender))))
      nil)))

(defn set-active-print-filename!
  "Set the active print filename for a sender."
  [state sender filename current-time]
  (let [save-filename (print-save-filename state filename)]
    (swap! (:active-prints state) assoc sender
           {:filename filename
            :save-filename save-filename
            :run-suffix (active-prusalink-run-suffix state)
            :last-packet-time current-time})
    (get @(:active-prints state) sender)))

(defn refresh-active-print-run!
  "Update the active save filename when PrusaLink reports a new local run."
  [state sender active-print current-time]
  (if (and active-print (active-prusalink-run-suffix state))
    (let [expected-save-filename (print-save-filename state (:filename active-print))]
      (if (= expected-save-filename (:save-filename active-print))
        active-print
        (set-active-print-filename! state sender (:filename active-print) current-time)))
    active-print))

(defn clear-active-print-filename!
  "Clear the active print filename for a sender."
  [state sender]
  (swap! (:active-prints state) dissoc sender))

(defn refresh-telemetry-print-state!
  "Update and return the last known telemetry print-active state for sender.

   nil means this firmware has not reported `is_printing` for the sender yet."
  [state sender raw-value]
  (if (= missing-telemetry-print-state raw-value)
    (get @(:telemetry-print-states state) sender)
    (let [active? (print-active-value? raw-value)]
      (swap! (:telemetry-print-states state) assoc sender active?)
      active?)))

(defn telemetry-print-idle?
  "Return true when telemetry explicitly says this sender is idle."
  [telemetry-print-active?]
  (false? telemetry-print-active?))

(defn can-start-print-save?
  "Return true when a filename metric should start a new archive."
  [state telemetry-print-active?]
  (or (true? telemetry-print-active?)
      (prusalink-active? state)))

(defn prusalink-print-active?
  "Return true when PrusaLink currently reports an active job.

   If PrusaLink is unavailable, preserve telemetry-only save behavior."
  [state]
  (let [{:keys [available? active?]} @(:prusalink-state state)]
    (if available?
      (boolean active?)
      true)))

(defn clear-completed-print!
  "Clear sticky print tracking when the print is no longer active."
  [state sender active-print]
  (when active-print
    ((:log-fn state)
     "No active print; stopping telemetry save for"
     (or (:save-filename active-print) (:filename active-print))))
  (clear-active-print-filename! state sender))

(defn telemetry-to-json
  "Convert telemetry packet to JSON-serializable format, sorting metrics by device time."
  [{:keys [sender received-at prelude metrics display-lines wall-time-str]}]
  (let [sorted-metrics (sort-by (fn [m] (or (:device-time-us m) 0)) metrics)]
    {:sender (str sender)
     :received-at (.getTime received-at)
     :prelude prelude
     :metrics (map (fn [m]
                     (cond-> {:name (:name m)
                              :type (name (:type m))
                              :offset-us (:offset-us m)
                              :offset-ms (:offset-ms m)
                              :device-time-us (:device-time-us m)
                              :device-time-str (:device-time-str m)}
                       (:value m) (assoc :value (:value m))
                       (:error m) (assoc :error (:error m))
                       (:tags m) (assoc :tags (:tags m))
                       (:fields m) (assoc :fields (:fields m))))
                   sorted-metrics)
     :display-lines display-lines
     :wall-time-str wall-time-str}))

(defn save-packet-to-file!
  "Save a packet to a print archive in append-only EDN format."
  [state packet print-filename]
  (try
    (ensure-prints-dir! state)
    (if-let [print-file (archive-file-for-save! state print-filename)]
      (do
        (with-open [writer (io/writer print-file :append true)]
          (binding [*print-length* nil
                    *print-level* nil
                    *out* writer]
            (prn packet)))
        true)
      false)
    (catch Exception e
      (println "Error saving packet to file:" (.getMessage e))
      (.printStackTrace e)
      false)))

(defn handle-packet-saving!
  "Handle print filename tracking and file saving for one telemetry packet."
  [state packet]
  (let [{:keys [sender metrics]} packet
        sender-str (str sender)
        current-time ((:now-ms state))
        raw-print-filename (get-print-filename metrics)
        raw-telemetry-print-state (get-telemetry-print-state metrics)
        telemetry-print-active? (refresh-telemetry-print-state!
                                 state sender-str raw-telemetry-print-state)
        saw-print-filename? (not= missing-print-filename raw-print-filename)
        new-print-filename (when saw-print-filename?
                             (normalize-print-filename raw-print-filename))
        active-print (refresh-active-print-run!
                      state
                      sender-str
                      (get-active-print state sender-str current-time)
                      current-time)
        active-save-filename (:save-filename active-print)]
    (cond
      (not (prusalink-print-active? state))
      (clear-completed-print! state sender-str active-print)

      (telemetry-print-idle? telemetry-print-active?)
      (clear-completed-print! state sender-str active-print)

      (and saw-print-filename? (nil? new-print-filename))
      (clear-active-print-filename! state sender-str)

      new-print-filename
      (when (or active-save-filename
                (can-start-print-save? state telemetry-print-active?))
        (let [new-save-filename (print-save-filename state new-print-filename)]
          (when-not (= new-save-filename active-save-filename)
            (set-active-print-filename! state sender-str new-print-filename current-time))
          (save-packet-to-file! state (telemetry-to-json packet) new-save-filename)))

      active-save-filename
      (save-packet-to-file! state (telemetry-to-json packet) active-save-filename))))

(defn list-telemetry-files!
  "List available EDN telemetry archive files."
  [state]
  (ensure-prints-dir! state)
  (let [date-dirs (filter #(and (.isDirectory %)
                                (valid-archive-date? (.getName %)))
                          (or (.listFiles (:prints-dir state)) []))
        files-by-date (reduce (fn [acc date-dir]
                                (let [date (.getName date-dir)
                                      edn-files (filter #(and (.isFile %)
                                                              (valid-archive-filename? (.getName %)))
                                                        (or (.listFiles date-dir) []))
                                      file-info (map (fn [f]
                                                       {:date date
                                                        :filename (.getName f)
                                                        :size (.length f)
                                                        :modified (.lastModified f)})
                                                     edn-files)]
                                  (concat acc file-info)))
                              []
                              date-dirs)]
    (sort-by (fn [f] [(:date f) (:filename f)]) files-by-date)))

(defn read-telemetry-file
  "Read one archive file and return a status map.

   Status is one of :ok, :invalid-path, or :not-found."
  [state date filename]
  (if-let [file-path (archive-file (:prints-dir state) date filename)]
    (if (and (.exists file-path) (.isFile file-path))
      {:status :ok
       :packets (with-open [reader (io/reader file-path)]
                  (doall (keep (fn [line]
                                 (try
                                   (edn/read-string line)
                                   (catch Exception e
                                     (println "Error reading line:" (.getMessage e))
                                     nil)))
                               (line-seq reader))))}
      {:status :not-found})
    {:status :invalid-path}))
