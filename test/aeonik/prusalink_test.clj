(ns aeonik.prusalink-test
  (:require
   [aeonik.prusalink :as prusalink]
   [clojure.test :refer :all]))

(deftest parses-digest-challenge
  (is (= {:realm "Printer API"
          :nonce "0738cba1009742ac"
          :stale "false"}
         (#'prusalink/parse-www-authenticate
          "Digest realm=\"Printer API\", nonce=\"0738cba1009742ac\", stale=false"))))

(deftest computes-rfc-2617-digest-response
  (is (= "6629fae49393a05397450978507c4ef1"
         (#'prusalink/digest-response
          {:username "Mufasa"
           :password "Circle Of Life"
           :method "GET"
           :uri "/dir/index.html"
           :realm "testrealm@host.com"
           :nonce "dcd98b7102dd2f0e8b11d0f600bfb0c093"
           :qop "auth"
           :nc "00000001"
           :cnonce "0a4f113b"}))))
