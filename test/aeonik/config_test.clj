(ns aeonik.config-test
  (:require
   [aeonik.config :as config]
   [clojure.java.io :as io]
   [clojure.test :refer :all]))

(deftest loads-config-file-over-defaults
  (let [file (java.io.File/createTempFile "prusa-telemetry" ".edn")]
    (try
      (spit file "{:http {:host \"127.0.0.1\" :port 18080}
                   :archive {:prints-dir \"/tmp/prusa-telemetry-test\"}
                   :prusalink {:base-url \"http://printer.local\"
                               :username \"maker\"
                               :password \"secret\"}}")
      (with-redefs [config/env-overrides (constantly {})]
        (let [cfg (config/load-config (.getPath file))]
          (is (= 8514 (config/telemetry-port cfg)))
          (is (= "127.0.0.1" (config/http-host cfg)))
          (is (= 18080 (config/http-port cfg)))
          (is (= (io/file "/tmp/prusa-telemetry-test") (config/prints-dir cfg)))
          (is (= {:base-url "http://printer.local"
                  :username "maker"
                  :password "secret"}
                 (config/configured-prusalink-auth cfg)))))
      (finally
        (.delete file)))))

(deftest warns-on-public-http-bind
  (let [warnings (config/validation-warnings config/default-config)]
    (is (some #(re-find #"bound to all interfaces" %) warnings))))
