(ns build-cljs
  (:require [cljs.build.api :as cljs]))

(defn -main [& args]
  (cljs/build "src-cljs"
    {:main 'aeonik.app
     :output-to "resources/app.js"
     :output-dir "target/cljs-out"
     :asset-path "/app.js"
     :optimizations :none
     :source-map true
     :verbose true})
  (println "ClojureScript build complete!"))
