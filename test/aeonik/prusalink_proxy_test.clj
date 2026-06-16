(ns aeonik.prusalink-proxy-test
  (:require [aeonik.prusalink :as prusalink]
            [aeonik.prusalink-proxy :as proxy]
            [clojure.data.json :as json]
            [clojure.test :refer :all]))

(deftest prusalink-proxy-errors-are-public-safe
  (with-redefs [prusalink/request (fn [_]
                                    (throw (ex-info "http://printer.local private detail" {})))]
    (let [response ((proxy/proxy-handler "/api/v1/status") {})
          body (json/read-str (:body response) :key-fn keyword)]
      (is (= 502 (:status response)))
      (is (= {:error "PrusaLink request failed"} body)))))
