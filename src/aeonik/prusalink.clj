(ns aeonik.prusalink
  (:require
   [aeonik.prusalink-auth :as auth]
   [clojure.data.json :as json]
   [clojure.string :as str])
  (:import
   [java.net URI]
   [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
   [java.security MessageDigest]
   [java.time Duration]
   [java.util UUID]))

(def ^:private request-timeout (Duration/ofSeconds 5))

(def ^:private client
  (delay
    (HttpClient/newHttpClient)))

(defn- md5-hex
  "Return the lower-case hex MD5 digest for a Digest-auth string."
  [s]
  (let [bytes (.digest (MessageDigest/getInstance "MD5")
                       (.getBytes (str s) "ISO-8859-1"))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) bytes))))

(defn- parse-www-authenticate
  "Parse a WWW-Authenticate Digest challenge header into a map."
  [header]
  (when (and header (str/starts-with? header "Digest "))
    (->> (re-seq #"([A-Za-z0-9_-]+)=(?:\"([^\"]*)\"|([^,\s]+))" header)
         (map (fn [[_ k quoted bare]]
                [(keyword (str/lower-case k)) (or quoted bare)]))
         (into {}))))

(defn- choose-qop
  "Choose a Digest qop value supported by this client."
  [qop]
  (when qop
    (some #{"auth"}
          (map str/trim (str/split qop #",")))))

(defn- digest-response
  "Build the Digest response hash."
  [{:keys [username password method uri realm nonce qop nc cnonce algorithm]}]
  (let [ha1-base (md5-hex (str username ":" realm ":" password))
        ha1 (if (= "md5-sess" (some-> algorithm str/lower-case))
              (md5-hex (str ha1-base ":" nonce ":" cnonce))
              ha1-base)
        ha2 (md5-hex (str method ":" uri))]
    (if qop
      (md5-hex (str ha1 ":" nonce ":" nc ":" cnonce ":" qop ":" ha2))
      (md5-hex (str ha1 ":" nonce ":" ha2)))))

(defn- quote-header-value
  "Quote a Digest auth header value."
  [value]
  (str "\"" (str/replace (str value) #"([\"\\])" "\\\\$1") "\""))

(defn- digest-authorization
  "Create a Digest Authorization header for one request."
  [{:keys [username password]} challenge method uri]
  (let [qop (choose-qop (:qop challenge))
        nc "00000001"
        cnonce (str/replace (str (UUID/randomUUID)) "-" "")
        params {:username username
                :password password
                :method method
                :uri uri
                :realm (:realm challenge)
                :nonce (:nonce challenge)
                :qop qop
                :nc nc
                :cnonce cnonce
                :algorithm (:algorithm challenge)}
        response (digest-response params)
        header-parts (cond-> [(str "username=" (quote-header-value username))
                              (str "realm=" (quote-header-value (:realm challenge)))
                              (str "nonce=" (quote-header-value (:nonce challenge)))
                              (str "uri=" (quote-header-value uri))
                              (str "response=" (quote-header-value response))]
                       (:opaque challenge)
                       (conj (str "opaque=" (quote-header-value (:opaque challenge))))

                       (:algorithm challenge)
                       (conj (str "algorithm=" (:algorithm challenge)))

                       qop
                       (conj (str "qop=" qop)
                             (str "nc=" nc)
                             (str "cnonce=" (quote-header-value cnonce))))]
    (str "Digest " (str/join ", " header-parts))))

(defn- request-uri
  "Build a full printer API URI from auth config and a path."
  [{:keys [base-url]} path]
  (URI/create (str base-url path)))

(defn- send-request
  "Send a single GET request, optionally with an Authorization header."
  [uri authorization body-handler]
  (let [builder (-> (HttpRequest/newBuilder uri)
                    (.timeout request-timeout)
                    (.GET))
        request (cond-> builder
                  authorization (.header "Authorization" authorization)
                  true (.build))]
    (.send @client request body-handler)))

(defn- request*
  "Fetch a PrusaLink API path using local Digest credentials and a body handler."
  [path body-handler]
  (if-let [credentials (auth/read-auth)]
    (let [method "GET"
          uri (request-uri credentials path)
          first-response (send-request uri nil body-handler)
          authenticate (some-> (.headers first-response)
                               (.firstValue "WWW-Authenticate")
                               (.orElse nil))
          challenge (parse-www-authenticate authenticate)
          final-response (if (and (= 401 (.statusCode first-response)) challenge)
                           (send-request uri (digest-authorization credentials
                                                                    challenge
                                                                    method
                                                                    path)
                                         body-handler)
                           first-response)]
      {:status (.statusCode final-response)
       :headers (into {} (.map (.headers final-response)))
       :body (.body final-response)})
    (throw (ex-info "PrusaLink auth config not found"
                    {:path (auth/auth-file-path)}))))

(defn request
  "Fetch a PrusaLink API path using local Digest credentials.
   Returns a map with :status, :headers, and string :body."
  [path]
  (request* path (HttpResponse$BodyHandlers/ofString)))

(defn request-bytes
  "Fetch a PrusaLink API path using local Digest credentials.
   Returns a map with :status, :headers, and byte-array :body."
  [path]
  (request* path (HttpResponse$BodyHandlers/ofByteArray)))

(defn request-json
  "Fetch a PrusaLink API path and parse a successful JSON response."
  [path]
  (let [{:keys [status body] :as response} (request path)]
    (cond-> response
      (and (<= 200 status 299) (not (str/blank? body)))
      (assoc :json (json/read-str body :key-fn keyword)))))

(defn status
  "Fetch the printer status snapshot used by the PrusaLink dashboard."
  []
  (request-json "/api/v1/status"))

(defn job
  "Fetch the active PrusaLink job details."
  []
  (request-json "/api/v1/job"))

(defn connection
  "Fetch PrusaLink connection settings/status."
  []
  (request-json "/api/connection"))
