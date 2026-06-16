(ns aeonik.web-server-test
  (:require [aeonik.archive :as archive]
            [aeonik.web-server :as web]
            [clojure.data.json :as json]
            [clojure.test :refer :all]))

(deftest telemetry-file-handler-rejects-traversal
  (with-redefs [archive/read-telemetry-file (fn [_ date filename]
                                              (is (= "2026-06-15" date))
                                              (is (= "../../config/prusalink.edn" filename))
                                              {:status :invalid-path})]
    (let [response (web/load-telemetry-file-handler
                    {:uri "/api/telemetry-file/2026-06-15/../../config/prusalink.edn"})
          body (json/read-str (:body response) :key-fn keyword)]
      (is (= 400 (:status response)))
      (is (= {:error "Invalid archive path"} body)))))
