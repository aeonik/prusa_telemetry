(ns aeonik.app
  (:require [aeonik.ws :as ws]
            [aeonik.events :refer [dispatch! init-timeline!]]
            [aeonik.render :refer [render!]]
            [aeonik.state :refer [app-state]]))

(defn set-view-mode [mode]
  "Set the view mode (called from timeline.html)"
  (let [mode-keyword (if (string? mode) (keyword mode) mode)]
    (dispatch! {:type :view/set :mode mode-keyword})))

(defn init []
  (println "Initializing Prusa Telemetry Dashboard...")
  (init-timeline!) ; Initialize timeline with dispatch! callback
  (ws/connect-websocket!)
  
  ;; Check if we're on the timeline page and set view mode after a short delay
  (let [path (.-pathname js/location)]
    (when (= path "/timeline")
      (js/setTimeout (fn [] 
                      (dispatch! {:type :view/set :mode :timeline})) 
                    200)))
  
  ;; Set up controls
  (when-let [pause-btn (.getElementById js/document "pause-btn")]
    (set! (.-onclick pause-btn)
          (fn [_]
            (dispatch! {:type :pause/toggle})
            ;; Update button text after state change
            (js/setTimeout
             (fn []
               (set! (.-textContent pause-btn) 
                     (if (:paused @app-state) "Resume" "Pause")))
             0))))
  
  (when-let [clear-btn (.getElementById js/document "clear-btn")]
    (set! (.-onclick clear-btn)
          (fn [_]
            (dispatch! {:type :data/clear}))))
  
  (when-let [view-toggle (.getElementById js/document "view-toggle")]
    (set! (.-onclick view-toggle)
          (fn [_]
            (dispatch! {:type :view/set-cycle})
            (set! (.-textContent view-toggle) 
                  (case (:view-mode @app-state)
                    :latest "Show Packets"
                    :packets "Show Timeline"
                    :timeline "Show Latest"
                    "Show Packets")))))
  
  ;; Initial render
  (render!))

(set! (.-onload js/window) init)
