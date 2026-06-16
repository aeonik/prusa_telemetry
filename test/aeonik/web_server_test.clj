(ns aeonik.web-server-test
  (:require [aeonik.archive :as archive]
            [aeonik.web-server :as web]
            [clojure.data.json :as json]
            [clojure.test :refer :all]
            [manifold.stream :as s]))

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

(deftest raw-telemetry-file-handler-rejects-traversal
  (with-redefs [archive/archive-file (fn [_prints-dir date filename]
                                       (is (= "2026-06-15" date))
                                       (is (= "../../config/prusalink.edn" filename))
                                       nil)]
    (let [response (web/raw-telemetry-file-handler
                    {:uri "/api/telemetry-file-raw/2026-06-15/../../config/prusalink.edn"})
          body (json/read-str (:body response) :key-fn keyword)]
      (is (= 400 (:status response)))
      (is (= {:error "Invalid archive path"} body)))))

(deftest live-request-handler-can-be-reloaded
  (let [stream (s/stream)
        live-config (var-get #'web/live-config)
        live-handler (var-get #'web/live-handler)
        original-config @live-config
        original-handler @live-handler]
    (try
      (reset! live-config {:telemetry-stream stream})
      (with-redefs [web/index-handler (fn [_req]
                                        {:status 200
                                         :body "before"})]
        (is (= {:status :reloaded} (web/reload-request-handler!)))
        (is (= "before" (:body (#'web/live-request-handler {:uri "/"})))))
      (with-redefs [web/index-handler (fn [_req]
                                        {:status 200
                                         :body "after"})]
        (is (= {:status :reloaded} (web/reload-request-handler!)))
        (is (= "after" (:body (#'web/live-request-handler {:uri "/"})))))
      (finally
        (reset! live-config original-config)
        (reset! live-handler original-handler)
        (s/close! stream)))))
