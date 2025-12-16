(ns aeonik.app
  (:require [aeonik.events :refer [dispatch! init-timeline!]]
            [aeonik.files :as files]
            [aeonik.state :refer [app-state load-state-from-storage!] :as state]
            [aeonik.views :as views]
            [aeonik.ws :as ws]
            [reagent.dom.client :as rdom]))

(defn set-view-mode
  "Parameters: mode keyword or string representing a view.
   Returns: nil after dispatching the view selection."
  [mode]
  (let [mode-keyword (if (string? mode) (keyword mode) mode)]
    (dispatch! {:type :view/set :mode mode-keyword})))

(defn- root-component
  "Parameters: none.
   Returns: hiccup vector for the mounted application root."
  []
  (let [state-val @app-state]
    [views/app-shell state-val (.-pathname js/location)]))

(defonce root (when-let [el (.getElementById js/document "app")]
                 (rdom/create-root el)))

(defn- mount-root!
  "Parameters: none.
   Returns: nil after mounting the Reagent root."
  []
  (when root
    (rdom/render root [root-component])))

(defn- ensure-timeline-selection!
  "Parameters: none.
   Returns: nil after ensuring timeline defaults are populated.
   Uses packet msg numbers for timeline operations."
  []
  (let [events (:telemetry-events @app-state)
        timeline-data (state/get-timeline-data events)
        filenames (keys timeline-data)]
    (when (seq filenames)
      (let [current-filename (or (:selected-filename @app-state) (first filenames))
            packets (get timeline-data current-filename [])]
        (when (seq packets)
          (let [packet-msg-numbers (map :packet-msg packets)
                max-msg (apply max packet-msg-numbers)]
            (when (nil? (:selected-packet-msg @app-state))
              (dispatch! {:type :timeline/set-packet-msg :packet-msg max-msg}))
            (when (nil? (:selected-filename @app-state))
              (dispatch! {:type :timeline/set-filename :filename current-filename}))))))))

(defn init
  "Parameters: none.
   Returns: nil after bootstrapping the dashboard."
  []
  (println "Initializing Prusa Telemetry Dashboard...")
  (load-state-from-storage!)
  (ensure-timeline-selection!)
  (init-timeline!)
  (ws/connect-websocket!)
  ;; Fetch available files on initialization
  (files/fetch-available-files!)
  (when (= (.-pathname js/location) "/timeline")
    (dispatch! {:type :view/set :mode :timeline}))
  (mount-root!))

(set! (.-onload js/window) init)
