(ns aeonik.render
  (:require [aeonik.state :refer [app-state]]
            [aeonik.views :as views]))

(defonce render-state
  (atom {:render-scheduled? false
         :last-render-ms    0
         :throttle-ms       50}))

(defn hiccup->dom
  "Convert Hiccup-style vector to DOM element"
  [hiccup]
  (cond
    (string? hiccup) (.createTextNode js/document hiccup)
    (number? hiccup) (.createTextNode js/document (str hiccup))
    (vector? hiccup)
    (let [[tag & args] hiccup
          _ (when-not (keyword? tag)
              (throw (js/Error. (str "Hiccup tag must be a keyword, got: " tag))))
          [attrs children] (if (and (seq args) (map? (first args)))
                            [(first args) (cljs.core/rest args)]
                            [{} (if (and (= (count args) 1) (seq? (first args)))
                                  (first args)
                                  args)])
          elem (.createElement js/document (name tag))]
      ;; Set attributes
      (doseq [[k v] attrs]
        (cond
          (= k :class) (set! (.-className elem) (if (string? v) v (apply str (interpose " " v))))
          (= k :id) (set! (.-id elem) v)
          (= k :onclick) (set! (.-onclick elem) v)
          (= k :onchange) (set! (.-onchange elem) v)
          (= k :oninput) (set! (.-oninput elem) v)
          (= k :onmousedown) (set! (.-onmousedown elem) v)
          (= k :onmouseup) (set! (.-onmouseup elem) v)
          (= k :ontouchstart) (set! (.-ontouchstart elem) v)
          (= k :ontouchend) (set! (.-ontouchend elem) v)
          (= k :value) (set! (.-value elem) v)
          (string? k) (.setAttribute elem k v)
          (keyword? k) (.setAttribute elem (name k) (str v))
          :else (.setAttribute elem (str k) (str v))))
      ;; Add children
      (doseq [child children]
        (when (some? child)
          (cond
            (vector? child) (let [dom-child (hiccup->dom child)]
                             (when dom-child
                               (.appendChild elem dom-child)))
            (seq? child) 
            (doseq [c child]
              (when (some? c)
                (let [dom-c (hiccup->dom c)]
                  (when dom-c
                    (.appendChild elem dom-c)))))
            (string? child) (.appendChild elem (.createTextNode js/document child))
            (number? child) (.appendChild elem (.createTextNode js/document (str child)))
            :else (let [dom-child (hiccup->dom child)]
                    (when dom-child
                      (.appendChild elem dom-child))))))
      elem)
    (seq? hiccup) (let [frag (.createDocumentFragment js/document)]
                    (doseq [item hiccup]
                      (.appendChild frag (hiccup->dom item)))
                    frag)
    :else nil))

(defn replace-children! [elem hiccup]
  (set! (.-innerHTML elem) "")
  (when-let [node (hiccup->dom hiccup)]
    (.appendChild elem node)))

(defn render! []
  (try
    (let [state @app-state
          status-el (.getElementById js/document "status")
          content-el (.getElementById js/document "content")]
      ;; Initialize timeline state if needed
      (when (= (:view-mode state) :timeline)
        (let [filenames (keys (:timeline-data state))
              current-filename (or (:selected-filename state) (first filenames))
              all-metrics (get (:timeline-data state) current-filename [])
              time-range (if (seq all-metrics)
                          (let [min-time (apply min (map :device-time-us all-metrics))
                                max-time (apply max (map :device-time-us all-metrics))]
                            {:min min-time :max max-time})
                          nil)]
          (when (and time-range (nil? (:selected-time state)))
            (swap! app-state assoc :selected-time (:max time-range)))
          (when (and current-filename (nil? (:selected-filename state)))
            (swap! app-state assoc :selected-filename current-filename))))
      (when status-el
        (replace-children! status-el (views/status-view state)))
      (when content-el
        (replace-children! content-el (views/main-view state))))
    (swap! render-state assoc
           :render-scheduled? false
           :last-render-ms (js/Date.now))
    (catch :default e
      (println "Error in render:" e)
      (js/console.error e))))

(defn schedule-render! []
  (let [{:keys [render-scheduled? last-render-ms throttle-ms]} @render-state
        now (js/Date.now)]
    (when (and (not render-scheduled?)
               (>= (- now last-render-ms) throttle-ms))
      (swap! render-state assoc :render-scheduled? true)
      (js/requestAnimationFrame render!))))

;; Watch app-state for changes and schedule renders
(add-watch app-state :render-watcher
           (fn [_ _ old new]
             (when (not= old new)
               (schedule-render!))))
