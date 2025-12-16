(ns aeonik.files
  (:require [aeonik.events :refer [dispatch!]]
            [clojure.string :as str]))

(defn- get-api-base-url
  "Get the base URL for API calls.
   Uses relative URLs - shadow-cljs proxies API requests to backend automatically."
  []
  (str js/location.protocol "//" js/location.host))

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
