(ns aeonik.archive-repair
  (:require [aeonik.prusa-telemetry :as telemetry]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.nio.file Files StandardCopyOption]))

(def default-root (io/file "telemetry-data" "prints"))

(defn telemetry-archive-file?
  "Return true when file is an EDN telemetry archive file."
  [file]
  (and (.isFile file)
       (str/ends-with? (.getName file) ".edn")))

(defn archive-files
  "Return telemetry archive files under root."
  [root]
  (->> (file-seq (io/file root))
       (filter telemetry-archive-file?)
       (sort-by #(.getPath %))))

(defn repair-metric
  "Move embedded line-protocol tags from :name into :tags.

   Example:
   {:name \"temp_noz,n=0,a=1\", :fields {\"value\" 215}}
   becomes:
   {:name \"temp_noz\", :tags {\"n\" 0, \"a\" 1}, :fields {\"value\" 215}}"
  [metric]
  (if (and (map? metric)
           (string? (:name metric))
           (str/includes? (:name metric) ","))
    (let [{clean-name :name parsed-tags :tags} (telemetry/parse-metric-head (:name metric))]
      (if (and clean-name
               (seq parsed-tags)
               (not= clean-name (:name metric)))
        (-> metric
            (assoc :name clean-name)
            (assoc :tags (merge parsed-tags (or (:tags metric) {}))))
        metric))
    metric))

(defn repair-packet
  "Repair one archive packet map."
  [packet]
  (if (map? packet)
    (update packet :metrics
            (fn [metrics]
              (if (sequential? metrics)
                (mapv repair-metric metrics)
                metrics)))
    packet))

(defn changed-metrics
  "Return the number of metric entries changed by repair-packet."
  [before after]
  (let [before-metrics (seq (:metrics before))
        after-metrics (seq (:metrics after))]
    (if (and before-metrics after-metrics)
      (count (filter false? (map identical? before-metrics after-metrics)))
      0)))

(defn repair-line
  "Repair one line-delimited EDN archive packet.

   Returns {:line string :changed-metrics n :parse-errors n}."
  [line]
  (try
    (let [packet (edn/read-string line)
          repaired (repair-packet packet)
          changed (changed-metrics packet repaired)]
      {:line (if (pos? changed) (pr-str repaired) line)
       :changed-metrics changed
       :parse-errors 0})
    (catch Exception _
      {:line line
       :changed-metrics 0
       :parse-errors 1})))

(defn- temp-file-for
  [file]
  (io/file (.getParentFile file)
           (str "." (.getName file) ".repair-tmp")))

(defn scan-file!
  "Scan one archive file.

   When write? is true, rewrites the file atomically if changes are needed."
  [file {:keys [write?] :or {write? false}}]
  (let [file (io/file file)
        tmp (temp-file-for file)
        totals (atom {:file (.getPath file)
                      :lines 0
                      :changed-lines 0
                      :changed-metrics 0
                      :parse-errors 0})
        process-line! (fn [line writer]
                        (let [{:keys [line changed-metrics parse-errors]} (repair-line line)]
                          (swap! totals
                                 (fn [acc]
                                   (-> acc
                                       (update :lines inc)
                                       (update :changed-lines #(if (pos? changed-metrics) (inc %) %))
                                       (update :changed-metrics + changed-metrics)
                                       (update :parse-errors + parse-errors))))
                          (when writer
                            (.write writer line)
                            (.newLine writer))))]
    (try
      (when write?
        (io/delete-file tmp true))
      (if write?
        (with-open [reader (io/reader file)
                    writer (io/writer tmp)]
          (doseq [line (line-seq reader)]
            (process-line! line writer)))
        (with-open [reader (io/reader file)]
          (doseq [line (line-seq reader)]
            (process-line! line nil))))
      (when (and write? (pos? (:changed-lines @totals)))
        (Files/move (.toPath tmp)
                    (.toPath file)
                    (into-array StandardCopyOption
                                [StandardCopyOption/REPLACE_EXISTING
                                 StandardCopyOption/ATOMIC_MOVE])))
      (when (and write? (zero? (:changed-lines @totals)))
        (io/delete-file tmp true))
      (assoc @totals :written? (and write? (pos? (:changed-lines @totals))))
      (catch Exception e
        (when write?
          (io/delete-file tmp true))
        (assoc @totals
               :error (.getMessage e)
               :written? false)))))

(defn scan-root!
  "Scan all telemetry archives under root."
  [root opts]
  (let [files (archive-files root)
        results (mapv #(scan-file! % opts) files)]
    {:root (.getPath (io/file root))
     :write? (boolean (:write? opts))
     :files (count files)
     :changed-files (count (filter #(pos? (:changed-lines %)) results))
     :changed-lines (reduce + 0 (map :changed-lines results))
     :changed-metrics (reduce + 0 (map :changed-metrics results))
     :parse-errors (reduce + 0 (map :parse-errors results))
     :results results}))

(defn- parse-args
  [args]
  (loop [args args
         opts {:root default-root
               :write? false}]
    (if-let [arg (first args)]
      (case arg
        "--write" (recur (rest args) (assoc opts :write? true))
        "--dry-run" (recur (rest args) (assoc opts :write? false))
        "--root" (recur (nnext args) (assoc opts :root (io/file (second args))))
        "-h" (assoc opts :help? true)
        "--help" (assoc opts :help? true)
        (recur (rest args) (assoc opts :root (io/file arg))))
      opts)))

(defn- print-summary
  [{:keys [root write? files changed-files changed-lines changed-metrics parse-errors results]}]
  (println (if write? "Archive repair write:" "Archive repair dry run:") root)
  (println "Files scanned:" files)
  (println "Files needing repair:" changed-files)
  (println "Lines needing repair:" changed-lines)
  (println "Metrics needing repair:" changed-metrics)
  (println "Parse errors:" parse-errors)
  (doseq [{:keys [file changed-lines changed-metrics parse-errors written? error]} results
          :when (or (pos? changed-lines) (pos? parse-errors) error)]
    (println (str "- " file
                  " changed-lines=" changed-lines
                  " changed-metrics=" changed-metrics
                  " parse-errors=" parse-errors
                  (when written? " written=true")
                  (when error (str " error=" error))))))

(defn -main
  "Scan or repair archived telemetry metric names.

   Defaults to a dry run:
     clojure -M -m aeonik.archive-repair

   Rewrite changed files:
     clojure -M -m aeonik.archive-repair --write"
  [& args]
  (let [{:keys [help? root] :as opts} (parse-args args)]
    (if help?
      (println "Usage: clojure -M -m aeonik.archive-repair [--dry-run|--write] [--root telemetry-data/prints]")
      (print-summary (scan-root! root opts)))))
