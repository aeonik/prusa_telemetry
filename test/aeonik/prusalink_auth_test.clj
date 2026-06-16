(ns aeonik.prusalink-auth-test
  (:require
   [aeonik.config :as config]
   [aeonik.prusalink-auth :as auth]
   [clojure.test :refer :all]))

(deftest missing-auth-file-returns-nil
  (is (nil? (auth/read-auth "/tmp/prusa-telemetry-missing-auth.edn"))))

(deftest normalizes-auth-config
  (is (= {:base-url "http://printer.local"
          :username "maker"
          :password "secret"}
         (auth/normalize-auth {:base-url "http://printer.local/"
                               :username " maker "
                               :password " secret "}))))

(deftest rejects-incomplete-auth-config
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"Invalid PrusaLink auth config"
       (auth/normalize-auth {:base-url "http://printer.local"
                             :username "maker"}))))

(deftest reads-auth-from-main-config
  (with-redefs [config/load-config
                (fn []
                  {:prusalink {:base-url "http://printer.local/"
                               :username " maker "
                               :password " secret "}})]
    (is (= {:base-url "http://printer.local"
            :username "maker"
            :password "secret"}
           (auth/read-auth)))))
