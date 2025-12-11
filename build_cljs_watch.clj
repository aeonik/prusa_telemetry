(ns build-cljs-watch
  (:require [cljs.build.api :as cljs]))

(defn -main [& args]
  (println "Starting ClojureScript watch mode...")
  (println "Watching src-cljs for changes...")
  (println "Press Ctrl+C to stop")
  (cljs/watch "src-cljs"
    {:main 'aeonik.app
     :output-to "resources/app.js"
     :output-dir "target/cljs-out"
     :asset-path "/app.js"
     :optimizations :none
     :source-map true
     :verbose true}))
