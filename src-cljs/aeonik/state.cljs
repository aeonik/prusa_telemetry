(ns aeonik.state)

(defonce app-state
  (atom
   {:ws                 nil
    :connected          false
    :telemetry-data     []
    :latest-values      {}      ; Map of metric-name -> latest value
    :paused             false
    :view-mode          :latest ; :latest | :packets | :timeline
    :timeline-data      {}      ; Map of print_filename -> sorted list of all metrics with timestamps
    :selected-time      nil     ; Selected time for scrubbing (in microseconds)
    :selected-filename  nil     ; Selected print filename
    :timeline-playing   false
    :timeline-interval  nil     ; Interval ID for auto-play
    :slider-dragging    false
    :user-interacting   false}))
