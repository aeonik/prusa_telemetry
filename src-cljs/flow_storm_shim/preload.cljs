(ns flow-storm-shim.preload
  "FlowStorm preload with compatibility wrappers for the current debugger runtime."
  (:require [cljs.storm.tracer :as storm-tracer]
            [flow-storm.api :as fs-api]
            [flow-storm.tracer :as fs-tracer]))

(defn- install-trace-hooks!
  "Install ClojureScriptStorm hooks, adapting newer CLJS hook arities."
  []
  (set! storm-tracer/trace-expr-fn
        (fn [flow-id val coord form-id _frame-id]
          (fs-tracer/trace-expr-exec flow-id val coord form-id)))
  (set! storm-tracer/trace-fn-call-fn
        (fn [flow-id fn-ns fn-name fn-args form-id _frame-id]
          (fs-tracer/trace-fn-call flow-id fn-ns fn-name fn-args form-id)))
  (set! storm-tracer/trace-fn-return-fn
        (fn [flow-id ret-val coord form-id _frame-id]
          (fs-tracer/trace-fn-return flow-id ret-val coord form-id)))
  (set! storm-tracer/trace-bind-fn
        (fn [flow-id coord sym-name val _frame-id]
          (fs-tracer/trace-bind flow-id coord sym-name val)))
  (set! storm-tracer/trace-form-init-fn fs-tracer/trace-form-init)
  (js/console.log "ClojureScriptStorm functions plugged in."))

(try
  (install-trace-hooks!)
  (catch :default _
    (js/console.log "ClojureScriptStorm not detected.")))

(fs-api/setup-runtime)
(fs-api/remote-connect {})
