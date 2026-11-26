(ns loretile.server.main
  "Loretile served by itself: public/ as static files, index.html carrying
   <meta name=\"loretile-backend\"> (the browser's part of the config), and
   /api for what needs the server's credentials:

     GET  /api/health
     POST /api/llm                → [:llm :url] with the gateway token
     POST /api/embed              → [:llm :embed-url] with the gateway token
     GET  /api/merges/?prefix=…   → the merge bucket listed as S3 XML
     GET  /api/merges/<key>       → one merge file
     GET  /api/graphs             → every server-kept graph (EDN)
     GET  /api/graphs/<id>?after=n[&format=json] → {:seq :events} after batch n (0: all); json: tagged like IndexedDB
     GET  /api/graphs/<id>/rows → every current row as one JSON string (the browser builds its graph from it)
     POST /api/graphs/<id>/events → judge + store {:events [...] :after n};
                                    answers {:seq :stored :events :head} (what
                                    it lacked), or 422 {:error … :event id}
     GET  /api/graphs/<id>/structure        → {nodes: […], edges: […]} (JSON,
                                              ETag = head, If-None-Match → 304)
     GET  /api/graphs/<id>/nodes/<node-id>  → {node children out in} (JSON)
     GET  /api/graphs/<id>/search?q=…&limit=n&mode=hybrid|semantic|verbatim → [[id class title short parent score]]
     GET  /api/graphs/<id>/changes?since=n  → {nodes edges head} (JSON)

   No authentication: it runs behind an auth proxy."
  (:gen-class)
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [loretile.server.config :as config]
            [loretile.server.embedder :as embedder]
            [loretile.server.graphs :as graphs]
            [loretile.server.log :refer [log]]
            [loretile.server.s3 :as s3]
            [loretile.server.token :as token])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.io File)
           (java.net InetSocketAddress URI URLDecoder)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse
                          HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files)
           (java.time Duration)
           (java.util.concurrent Executors)))

(defn- send! [^HttpExchange ex status content-type ^bytes body]
  (let [h (.getResponseHeaders ex)]
    (when content-type (.set h "Content-Type" content-type)))
  (if (or (= "HEAD" (.getRequestMethod ex)) (zero? (alength body)))
    (.sendResponseHeaders ex status -1)
    (do (.sendResponseHeaders ex status (alength body))
        (with-open [out (.getResponseBody ex)] (.write out body)))))

(defn- send-text! [ex status text]
  (send! ex status "text/plain; charset=utf-8" (.getBytes (str text) StandardCharsets/UTF_8)))

;; --- static files ---------------------------------------------------------------

(def ^:private types
  {"html" "text/html; charset=utf-8" "js" "text/javascript" "mjs" "text/javascript"
   "css" "text/css" "json" "application/json" "svg" "image/svg+xml" "png" "image/png"
   "ico" "image/x-icon" "wasm" "application/wasm" "edn" "application/edn; charset=utf-8"
   "txt" "text/plain; charset=utf-8" "zip" "application/zip" "data" "application/octet-stream"
   "woff2" "font/woff2" "map" "application/json"})

(defn- type-of [^File f]
  (get types (str/lower-case (or (second (re-find #"\.([^.]+)$" (.getName f))) ""))
       "application/octet-stream"))

(defn- html-attr [s]
  (-> s (str/replace "&" "&amp;") (str/replace "\"" "&quot;") (str/replace "<" "&lt;")))

(defn browser-config
  "What the browser may know: no gateway url, no token."
  [cfg]
  (let [{:keys [shape model max-output embed-url embed-model]} (:llm cfg)]
    (cond-> {:shape shape :model model :merges? (boolean (get-in cfg [:merges :url]))
             :graphs? (boolean (get-in cfg [:graphs :url]))}
      max-output (assoc :max-output max-output)
      (and embed-url embed-model) (assoc :embed-model embed-model))))

(defn index-html [^String html cfg]
  (str/replace-first
   html "<meta charset=\"utf-8\">"
   (str "<meta charset=\"utf-8\">\n"
        "<meta name=\"loretile-backend\" content=\"" (html-attr (pr-str (browser-config cfg))) "\">"
        (when-not (get-in cfg [:app :demos]) "\n<meta name=\"loretile-demos\" content=\"off\">"))))

(defn- serve-file! [^HttpExchange ex]
  (let [root (.getCanonicalFile (File. ^String (config/get-in-config [:server :public-dir])))
        path (URLDecoder/decode (.getRawPath (.getRequestURI ex)) StandardCharsets/UTF_8)
        f (.getCanonicalFile (File. root path))
        f (if (.isDirectory f) (File. f "index.html") f)]
    (cond
      (not (#{"GET" "HEAD"} (.getRequestMethod ex))) (send-text! ex 405 "Method not allowed")
      (not (str/starts-with? (.getPath f) (str (.getPath root) File/separator))) (send-text! ex 404 "Not found")
      (not (.isFile f)) (send-text! ex 404 "Not found")
      (= "index.html" (.getName f))
      (do (.set (.getResponseHeaders ex) "Cache-Control" "no-cache")
          (send! ex 200 (type-of f) (.getBytes ^String (index-html (slurp f) @config/current) StandardCharsets/UTF_8)))
      :else (send! ex 200 (type-of f) (Files/readAllBytes (.toPath f))))))

;; --- the gateway --------------------------------------------------------------------

(def ^:private http (delay (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofSeconds 20)) .build)))

(def ^:private passed-headers ["Content-Type" "anthropic-version" "anthropic-beta"])

(defn- forward [^HttpExchange ex url ^bytes body tok]
  (let [{:keys [auth-header auth-prefix timeout-ms]} (config/get-in-config [:llm])
        b (-> (HttpRequest/newBuilder (URI. url))
              (.timeout (Duration/ofMillis timeout-ms))
              (.POST (HttpRequest$BodyPublishers/ofByteArray body)))]
    (doseq [h passed-headers
            :let [v (.getFirst (.getRequestHeaders ex) h)]
            :when v]
      (.header b h v))
    (when tok (.header b auth-header (str auth-prefix (:token tok))))
    (.send ^HttpClient @http (.build b) (HttpResponse$BodyHandlers/ofByteArray))))

(defn- proxy! [^HttpExchange ex url-key]
  (let [url (config/get-in-config [:llm url-key])]
    (cond
      (not= "POST" (.getRequestMethod ex)) (send-text! ex 405 "Method not allowed")
      (str/blank? url) (send-text! ex 404 (str "llm " (name url-key) " is not configured"))
      :else
      (let [body (.readAllBytes (.getRequestBody ex))
            tok (token/current)
            ^HttpResponse r (forward ex url body tok)
            ^HttpResponse r (if (and tok (= 401 (.statusCode r)))
                              (do (token/invalidate! (:gen tok))
                                  (forward ex url body (token/current)))
                              r)]
        (send! ex (.statusCode r)
               (.orElse (.firstValue (.headers r) "content-type") "application/json")
               (.body r))))))

;; --- merge files --------------------------------------------------------------------

(defn- listing-xml [keys]
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?><ListBucketResult>"
       (apply str (for [k keys] (str "<Contents><Key>" (html-attr k) "</Key></Contents>")))
       "</ListBucketResult>"))

(defn- query-param [^HttpExchange ex k]
  (some (fn [kv] (let [[a ^String b] (str/split kv #"=" 2)]
                   (when (= a k) (URLDecoder/decode (or b "") StandardCharsets/UTF_8))))
        (some-> (.getRawQuery (.getRequestURI ex)) (str/split #"&"))))

(defn- merges! [^HttpExchange ex]
  (let [u (config/get-in-config [:merges :url])
        aws (config/get-in-config [:aws])
        rel (subs (URLDecoder/decode (.getRawPath (.getRequestURI ex)) StandardCharsets/UTF_8)
                  (count "/api/merges/"))]
    (if-not u
      (send-text! ex 404 "merges url is not configured")
      (let [{:keys [bucket key]} (s3/parse-url u)
            strip #(subs % (count (s3/join-key key "")))]
        (cond
          (not= "GET" (.getRequestMethod ex)) (send-text! ex 405 "Method not allowed")
          (str/blank? rel)
          (let [prefix (or (query-param ex "prefix") "")]
            (if-not (str/starts-with? prefix "merges/")
              (send-text! ex 400 "prefix must start with merges/")
              (send! ex 200 "application/xml"
                     (.getBytes ^String (listing-xml (map strip (s3/list-keys aws bucket (s3/join-key key prefix))))
                                StandardCharsets/UTF_8))))
          (not (str/starts-with? rel "merges/")) (send-text! ex 404 "Not found")
          :else (send! ex 200 "application/edn; charset=utf-8"
                       (:bytes (s3/get-object aws bucket (s3/join-key key rel) nil))))))))

;; --- graphs kept by the server (loretile.server.graphs) -----------------------------

(defn- send-edn! [ex status data]
  (send! ex status "application/edn; charset=utf-8" (.getBytes ^String (pr-str data) StandardCharsets/UTF_8)))

(defn- send-json! [ex status ^String text]
  (send! ex status "application/json; charset=utf-8" (.getBytes text StandardCharsets/UTF_8)))

(defn- structure! [^HttpExchange ex gid]
  (let [[head text] (graphs/structure gid)
        etag (str "\"" head "\"")
        h (.getResponseHeaders ex)]
    (.set h "ETag" etag)
    (.set h "Cache-Control" "no-cache")
    (if (= etag (.getFirst (.getRequestHeaders ex) "If-None-Match"))
      (send! ex 304 nil (byte-array 0))
      (send-json! ex 200 text))))

(defn- graph-read! [^HttpExchange ex gid tail node-id]
  (let [n (fn [k default] (or (some-> (query-param ex k) parse-long) default))]
    (case tail
      "structure" (structure! ex gid)
      "search" (send-json! ex 200 (json/write-str (graphs/search gid (query-param ex "q") (min 200 (max 1 (n "limit" 20)))
                                                                  (#{"verbatim" "semantic" "hybrid"} (query-param ex "mode")))))
      "changes" (send-json! ex 200 (json/write-str (graphs/changes-since gid (n "since" 0))))
      "rows" (send-json! ex 200 (graphs/rows-json gid))
      (if-let [r (graphs/node gid node-id)]
        (send-json! ex 200 (json/write-str r))
        (send-text! ex 404 "no such node")))))

(defn- graphs-api! [^HttpExchange ex]
  (let [method (.getRequestMethod ex)
        rel (subs (.getPath (.getRequestURI ex)) (count "/api/graphs"))
        [_ gid tail node-id] (re-matches #"/?([^/]*)(?:/(events|structure|search|changes|rows|nodes/(.+?)))?/?" rel)
        tail (if node-id "nodes" tail)]
    (cond
      (not (graphs/configured?)) (send-text! ex 404 "graphs url is not configured")
      (nil? gid) (send-text! ex 404 "Not found")
      (str/blank? gid) (if (= "GET" method) (send-edn! ex 200 (graphs/list-graphs)) (send-text! ex 405 "Method not allowed"))
      (not (graphs/valid-id? gid)) (send-text! ex 400 "bad graph id")
      (and (nil? tail) (= "GET" method))
      (let [after (some-> (query-param ex "after") parse-long)]
        (if (= "json" (query-param ex "format"))
          (send-json! ex 200 (graphs/events-after-json gid after))
          (send-edn! ex 200 (graphs/events-after gid after))))
      (and (= "events" tail) (= "POST" method))
      (let [body (edn/read-string (String. (.readAllBytes (.getRequestBody ex)) StandardCharsets/UTF_8))]
        (if (and (map? body) (sequential? (:events body)))
          (let [r (graphs/append! gid (vec (:events body)) (:after body))]
            ;; stored: the new texts are embedded now, not at the next poll
            (when (pos? (or (:stored r) 0)) (embedder/nudge!))
            (send-edn! ex (if (:error r) 422 200) r))
          (send-text! ex 400 "body must be {:events [...] :after n}")))
      (and tail (not= "events" tail) (= "GET" method)) (graph-read! ex gid tail node-id)
      :else (send-text! ex 405 "Method not allowed"))))

;; --- routes ---------------------------------------------------------------------------

(defn- route! [^HttpExchange ex]
  (let [path (.getPath (.getRequestURI ex))]
    (cond
      (= path "/api/health") (send-text! ex 200 "ok")
      (= path "/api/llm") (proxy! ex :url)
      (= path "/api/embed") (proxy! ex :embed-url)
      (str/starts-with? path "/api/merges/") (merges! ex)
      (or (= path "/api/graphs") (str/starts-with? path "/api/graphs/")) (graphs-api! ex)
      (str/starts-with? path "/api/") (send-text! ex 404 "Not found")
      :else (serve-file! ex))))

(defn- handler []
  (reify HttpHandler
    (handle [_ ex]
      (try (route! ex)
           (catch Exception e
             (log (.getRequestMethod ex) (.getRequestURI ex) "failed:" (ex-message e))
             (try (send-text! ex 502 (or (ex-message e) "error")) (catch Exception _)))
           (finally (.close ex))))))

(defn start! []
  (let [port (config/get-in-config [:server :port])
        server (HttpServer/create (InetSocketAddress. (int port)) 0)]
    (.createContext server "/" (handler))
    (.setExecutor server (Executors/newVirtualThreadPerTaskExecutor))
    (.start server)
    (log "Loretile on port" port "config" (or (config/location) "(defaults + environment)"))
    server))

(defn -main [& _]
  (try (config/load!)
       (catch Exception e
         (log "cannot load config:" (ex-message e))
         (System/exit 1)))
  (config/start-reloading!)
  (when (graphs/configured?) (embedder/start! graphs/db))
  (start!))
