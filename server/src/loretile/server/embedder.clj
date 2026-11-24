(ns loretile.server.embedder
  "The server's embedder (wiki/Graph-Store.md § Embeddings): texts of the
   projection with no vector for [:llm :embed-model] → the gateway's
   OpenAI-shaped /embeddings ([:llm :embed-url], with its token) → the
   `vectors` table. Every call, the search query's too, passes a DAILY
   token cap ([:llm :embed-daily-tokens], tokens ≈ chars/4, kept in the
   projection's `spend` table so a restart does not reopen it) that fails
   CLOSED: past it nothing is embedded until the next UTC day. A failed
   embedding never fails a write; the node stays pending and is retried.

   ponytail: polls every 5 s; a nudge from append! when freshness matters."
  (:require [clojure.data.json :as json]
            [loretile.server.config :as config]
            [loretile.server.log :refer [log]]
            [loretile.server.projection :as proj]
            [loretile.server.token :as token])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse HttpResponse$BodyHandlers)
           (java.time Duration LocalDate ZoneOffset)))

(defn model
  "The vectors' model key, or nil when embeddings are not configured."
  []
  (let [{:keys [embed-url embed-model]} (config/get-in-config [:llm])]
    (when (and (seq embed-url) (seq embed-model)) embed-model)))

(defn- tokens [texts] (reduce + (map #(quot (+ (count %) 3) 4) texts)))
(defn- today [] (str (LocalDate/now ZoneOffset/UTC)))

(defonce ^:private closed-day (atom nil))
(def ^:private cap-lock (Object.))

(defn- admit!
  "Count `texts` against today's cap; false (and nothing counted) when they
   do not fit."
  [db texts]
  (locking cap-lock
    (let [cap (config/get-in-config [:llm :embed-daily-tokens])
          day (today)
          n (tokens texts)]
      (if (> (+ (proj/spent db day) n) cap)
        (do (when-not (= day @closed-day)
              (reset! closed-day day)
              (log "embedding cap reached:" cap "tokens on" day "(UTC) — nothing more is embedded until tomorrow"))
            false)
        (do (proj/spend! db day n) true)))))

(def ^:private http (delay (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofSeconds 20)) .build)))

(defn- post!
  "texts → vectors from the gateway; a 401 gets one retry with a new token
   (as main.clj's proxy)."
  [texts]
  (let [{:keys [embed-url embed-model auth-header auth-prefix timeout-ms]} (config/get-in-config [:llm])
        body (json/write-str {:model embed-model :input texts})
        send (fn [tok]
               (let [b (-> (HttpRequest/newBuilder (URI. embed-url))
                           (.timeout (Duration/ofMillis timeout-ms))
                           (.header "Content-Type" "application/json")
                           (.POST (HttpRequest$BodyPublishers/ofString body)))]
                 (when tok (.header b auth-header (str auth-prefix (:token tok))))
                 (.send ^HttpClient @http (.build b) (HttpResponse$BodyHandlers/ofString))))
        tok (token/current)
        ^HttpResponse r (send tok)
        ^HttpResponse r (if (and tok (= 401 (.statusCode r)))
                          (do (token/invalidate! (:gen tok)) (send (token/current)))
                          r)]
    (when-not (= 200 (.statusCode r))
      (throw (ex-info (str "the gateway answered " (.statusCode r)) {})))
    (->> (get (json/read-str (.body r)) "data") (sort-by #(get % "index")) (mapv #(get % "embedding")))))

(defn embed!
  "texts → vectors, or nil when no model is configured or the cap refuses."
  [db texts]
  (when (and (model) (seq texts) (admit! db texts))
    (post! texts)))

(defn- run-once!
  "Embed pending texts batch by batch until none are left or the cap stops it."
  [db]
  (when-let [m (model)]
    (loop []
      (let [p (proj/pending db m 64)]
        (when (seq p)
          (when-let [vs (embed! db (map second p))]
            (proj/put-vectors! db m (map vector (map first p) vs))
            (recur)))))))

(defn start!
  "The background embedder over the projection `@db`."
  [db]
  (Thread/startVirtualThread
   (fn []
     (loop []
       (try (when (model) (run-once! @db))
            (catch Exception e (log "embedding failed, retried later:" (ex-message e))))
       (Thread/sleep 5000)
       (recur)))))

;; --- search by meaning ---------------------------------------------------------------

(defn nearest
  "The ids of the `k` vectors closest to `q` by cosine, best first.
   ponytail: decodes every vector of the graph per query (the Worker keeps
   them in memory, ≈100 ms at 40k); cache per [graph model head] when slow."
  [id-vecs q k]
  (let [q (float-array q)
        norm (fn [^floats v] (Math/sqrt (areduce v i s 0.0 (+ s (* (aget v i) (aget v i))))))
        qn (max (norm q) 1e-9)]
    (->> id-vecs
         (map (fn [[id ^floats v]]
                [id (/ (areduce v i s 0.0 (+ s (* (aget v i) (aget q i)))) (* qn (max (norm v) 1e-9)))]))
         (sort-by (comp - second))
         (take k)
         (map first))))

(defn rrf
  "Reciprocal rank fusion of ranked id lists → [[id score]] best first."
  [lists limit]
  (->> lists
       (mapcat (fn [ids] (map-indexed (fn [i id] {id (/ 1.0 (+ 61 i))}) ids)))
       (apply merge-with +)
       (sort-by (comp - val))
       (take limit)))
