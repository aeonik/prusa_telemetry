(ns build
  (:refer-clojure :exclude [run! test])
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [org.corfield.build :as bb])
  (:import
   [java.security MessageDigest]))

(def lib 'net.clojars.aeonik/prusa_telemetry)
(def version "0.1.0-SNAPSHOT")
(def main 'aeonik.web-server)
(def latest-jar "target/prusa-telemetry.jar")

(defn- run!
  [& command]
  (let [{:keys [exit out err]} (apply shell/sh command)]
    (when (seq out) (print out))
    (when (seq err) (binding [*out* *err*] (print err)))
    (when-not (zero? exit)
      (throw (ex-info "Command failed" {:command command
                                        :exit exit})))))

(defn- jar-file
  []
  (format "target/%s-%s.jar" (name lib) version))

(defn- hex
  [bytes]
  (apply str (map #(format "%02x" (bit-and % 0xff)) bytes)))

(defn- sha256-file!
  [path]
  (let [digest (MessageDigest/getInstance "SHA-256")
        file (io/file path)
        buffer (byte-array 8192)]
    (with-open [in (io/input-stream file)]
      (loop []
        (let [read (.read in buffer)]
          (when (pos? read)
            (.update digest buffer 0 read)
            (recur)))))
    (let [checksum (hex (.digest digest))
          out-path (str path ".sha256")]
      (spit out-path (format "%s  %s%n" checksum (.getName file)))
      (println "Wrote" out-path)
      out-path)))

(defn- copy-file!
  [from to]
  (io/make-parents to)
  (io/copy (io/file from) (io/file to))
  (println "Wrote" to)
  to)

(defn test "Run the tests." [opts]
  (bb/run-tests opts))

(defn clean
  "Clean build output."
  [opts]
  (bb/clean opts))

(defn cljs
  "Compile all ClojureScript release targets."
  [_opts]
  (run! "npm" "ci" "--ignore-scripts")
  (run! "clojure" "-M:shadow-cljs" "compile" "app")
  (run! "clojure" "-M:shadow-cljs" "compile" "replay-worker"))

(defn uber
  "Build the standalone application jar.
   Run `cljs` first when building manually."
  [opts]
  (-> opts
      (assoc :lib lib :version version :main main)
      (bb/uber)))

(defn release
  "Run tests, compile CLJS, build the jar, and emit a sha256 checksum."
  [opts]
  (bb/run-tests opts)
  (bb/clean opts)
  (cljs opts)
  (uber opts)
  (copy-file! (jar-file) latest-jar)
  (sha256-file! (jar-file))
  (sha256-file! latest-jar))

(defn ci "Run the CI pipeline of tests (and build the uberjar)." [opts]
  (release opts))
