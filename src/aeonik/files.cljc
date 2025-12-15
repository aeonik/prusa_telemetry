(ns aeonik.files
  "Utilities for handling telemetry file metadata and loading archives."
  (:require [clojure.string :as str]
            #?(:cljs [aeonik.events :refer [dispatch!]])))

(defn- parse-long-safe
  "Parameters: v any value convertible to a long.
   Returns: long value when possible, otherwise nil without throwing."
  [v]
  (cond
    (integer? v) (long v)
    (number? v) (long v)
    (string? v) (let [s (str/trim v)]
                 (when (seq s)
                   #?(:clj (try
                            (Long/parseLong s)
                            (catch Exception _
                              nil))
                      :cljs (let [n (js/parseInt s 10)]
                              (when-not (js/isNaN n) n)))))
    :else nil))

(defn normalize-available-files
  "Parameters: files collection of file info maps with :date and :filename keys.
   Returns: vector of sanitized file maps with string dates, filenames, sizes, and optional :modified timestamps.

   Ignores non-maps or entries missing required keys and coerces numeric fields safely."
  [files]
  (->> (if (sequential? files) files [])
       (filter map?)
       (keep (fn [f]
               (let [date (:date f)
                     filename (:filename f)]
                 (when (and date filename)
                   {:date     (str date)
                    :filename (str filename)
                    :size     (or (parse-long-safe (:size f)) 0)
                    :modified (parse-long-safe (:modified f))}))))
       vec))

(defn normalize-packets
  "Parameters: packets sequential collection of telemetry packet maps.
   Returns: vector of packets with required keys and vectorized metrics for downstream consumption.

   Drops malformed packet or metric entries rather than throwing, and coerces numeric timestamps when present."
  [packets]
  (let [as-string (fn [v]
                    (cond
                      (keyword? v) (name v)
                      (string? v) (let [s (str/trim v)]
                                     (when (seq s) s))
                      (nil? v) nil
                      :else (str v)))]
    (->> (if (sequential? packets) packets [])
         (filter map?)
         (map (fn [packet]
                (let [metrics (vec (or (:metrics packet) []))]
                  {:sender        (as-string (:sender packet))
                   :wall-time-str (as-string (:wall-time-str packet))
                   :wall-time-ms  (parse-long-safe (:wall-time-ms packet))
                   :metrics       (->> metrics
                                       (filter map?)
                                       (mapv (fn [m]
                                               {:name            (as-string (:name m))
                                                :value           (:value m)
                                                :fields          (:fields m)
                                                :error           (:error m)
                                                :type            (:type m)
                                                :tick            (parse-long-safe (:tick m))
                                                :device-time-us  (parse-long-safe (:device-time-us m))
                                                :device-time-str (as-string (:device-time-str m))
                                                :wall-time-ms    (parse-long-safe (:wall-time-ms m))})))})))
         vec)))

#?(:cljs
   (defn fetch-available-files!
     "Parameters: none.
      Returns: js/Promise resolving after dispatching :files/set-available with normalized file data."
     []
     (-> (js/fetch "/api/telemetry-files")
         (.then (fn [resp]
                  (if (.-ok resp)
                    (.json resp)
                    (js/Promise.reject (js/Error. (str "Failed to fetch telemetry files: " (.-status resp)))))))
         (.then (fn [data]
                  (let [files (normalize-available-files (js->clj data :keywordize-keys true))]
                    (dispatch! {:type :files/set-available :files files}))))
         (.catch (fn [err]
                   (js/console.error "Unable to fetch available telemetry files" err))))))

#?(:cljs
   (defn load-telemetry-file!
     "Parameters: date string (YYYY-MM-DD), filename string of the telemetry archive.
      Returns: js/Promise resolving after dispatching :data/load-file and updating selection."
     [date filename]
     (when (and (seq (str date)) (seq (str filename)))
       (-> (js/fetch (str "/api/telemetry-file/" (str/trim date) "/" (str/trim filename)))
           (.then (fn [resp]
                    (if (.-ok resp)
                      (.json resp)
                      (js/Promise.reject (js/Error. (str "Failed to load telemetry file: " (.-status resp)))))))
           (.then (fn [data]
                    (let [packets (normalize-packets (js->clj data :keywordize-keys true))]
                      (dispatch! {:type :data/load-file :packets packets})
                      (dispatch! {:type :timeline/set-filename :filename filename}))))
           (.catch (fn [err]
                     (js/console.error "Unable to load telemetry file" err)))))))
