(ns loretile.server.graphs
  "Graphs kept by the server (wiki/Graph-Sync.md): browsers work local-first
   and sync their event logs here. A graph is its log, stored as BATCHES —
   one object per accepted POST, `{graph-id}/{seq:012d}.edn`, one event per
   line — in a directory (file://) or a bucket and prefix (s3://), from
   [:graphs :url]. S3 cannot append, so a batch is its own object; the seq
   is the order every browser agrees on.

   Since milestone 4 (wiki/Graph-Store.md) the server JUDGES: under the
   per-graph lock a batch is guarded event by event against the SQLite
   projection (loretile.server.projection) as the batch's earlier events
   left it, and projected in the same transaction — one refusal stores
   nothing. An event whose :ev/id it already has is still skipped (a resend
   is harmless). The projection answers the reads (structure, node, search,
   changes) and is rebuilt from the batches when it lags them or another
   fold wrote it.

   ponytail: one server process — the per-graph lock and the cache of known
   ids live in memory. Several instances need a conditional write (S3
   If-None-Match on the batch key) before they can share a store."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [loretile.server.config :as config]
            [loretile.server.embedder :as embedder]
            [loretile.server.log :refer [log]]
            [loretile.server.projection :as proj]
            [loretile.server.s3 :as s3])
  (:import (java.io File)
           (java.nio.charset StandardCharsets)))

(def ^:private id-re #"[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

(defn valid-id?
  "A graph id the store accepts — no path, no traversal."
  [id]
  (boolean (and (string? id) (re-matches id-re id))))

(defn- store []
  (let [u (config/get-in-config [:graphs :url])]
    (cond
      (str/blank? u) nil
      (str/starts-with? u "file://") {:kind :file :dir (subs u (count "file://"))}
      (str/starts-with? u "s3://") (assoc (s3/parse-url u) :kind :s3 :aws (config/get-in-config [:aws]))
      :else (throw (ex-info (str "graphs url must be file:// or s3://, got " u) {})))))

(defn configured? [] (some? (store)))

(defn- batch-name [n] (format "%012d.edn" n))
(defn- batch-seq [name] (some-> (re-matches #"(\d{12})\.edn" name) second parse-long))

;; --- the two backends -------------------------------------------------------------

(defn- list-batches
  "The seqs of a graph's batches, ascending."
  [{:keys [kind dir bucket key aws]} gid]
  (sort (keep batch-seq
              (case kind
                :file (some->> (.listFiles (File. (str dir "/" gid))) (map #(.getName ^File %)))
                :s3 (map #(last (str/split % #"/")) (s3/list-keys aws bucket (s3/join-key key (str gid "/"))))))))

(defn- read-batch [{:keys [kind dir bucket key aws]} gid n]
  (let [text (case kind
               :file (slurp (str dir "/" gid "/" (batch-name n)) :encoding "UTF-8")
               :s3 (String. ^bytes (:bytes (s3/get-object aws bucket (s3/join-key key (str gid "/" (batch-name n))) nil))
                            StandardCharsets/UTF_8))]
    (into [] (comp (remove str/blank?) (map edn/read-string)) (str/split-lines text))))

(defn- write-batch! [{:keys [kind dir bucket key aws]} gid n events]
  (let [text (str (str/join "\n" (map pr-str events)) "\n")]
    (case kind
      :file (let [f (io/file dir gid (batch-name n))
                  tmp (io/file dir gid (str "." (batch-name n) ".tmp"))]
              (io/make-parents f)
              ;; written aside, then renamed: a half-written batch is never read
              (spit tmp text :encoding "UTF-8")
              (.renameTo tmp f))
      :s3 (s3/put-object! aws bucket (s3/join-key key (str gid "/" (batch-name n)))
                          (.getBytes text StandardCharsets/UTF_8) "application/edn; charset=utf-8"))))

(defn- graph-ids [{:keys [kind dir bucket key aws]}]
  (sort (filter valid-id?
                (case kind
                  :file (some->> (.listFiles (File. ^String dir)) (filter #(.isDirectory ^File %)) (map #(.getName ^File %)))
                  :s3 (distinct (keep #(let [rel (subs % (count (s3/join-key key "")))
                                             [g f] (str/split rel #"/" 2)]
                                         (when (and g f (batch-seq f)) g))
                                      (s3/list-keys aws bucket (s3/join-key key ""))))))))

;; --- what the server knows of a graph -----------------------------------------------

(defn- meta-of
  "What the list shows, read off the events: the title and ontology a
   graph-set last gave, how many events, the last one's time."
  [m events]
  (reduce (fn [m e]
            (let [sets (for [[op a] (:ops e) :when (= :graph-set op)] a)
                  last-set (fn [k] (some #(when (contains? % k) [k (get % k)]) (reverse sets)))]
              (cond-> (-> m (update :events (fnil inc 0)) (update :updated #(max (or % 0) (or (:ev/ts e) 0))))
                (last-set :title) (assoc :title (second (last-set :title)))
                (last-set :ontology) (assoc :ontology (second (last-set :ontology))))))
          m events))

(defonce ^:private graphs (atom {}))   ; gid -> {:seq :ids #{} :meta {} :batches [[seq events] …]}
(defonce ^:private locks (atom {}))

(defn- lock-of [gid] (or (get @locks gid) (get (swap! locks update gid #(or % (Object.))) gid)))

(defn- loaded
  "The graph as stored, loaded once per process."
  [st gid]
  (or (get @graphs gid)
      (let [batches (vec (for [n (list-batches st gid)] [n (read-batch st gid n)]))
            all (mapcat second batches)
            g {:seq (or (first (last batches)) 0)
               :ids (set (keep :ev/id all))
               :meta (meta-of {:id gid} all)
               :batches batches}]
        (swap! graphs assoc gid g)
        g)))

;; --- the projection ------------------------------------------------------------------

(defn- projection-path []
  (or (config/get-in-config [:graphs :projection])
      (let [{:keys [kind dir]} (store)]
        (if (= :file kind)
          (str dir "/projection.sqlite3")
          (str (System/getProperty "java.io.tmpdir") "/loretile-projection.sqlite3")))))

(defonce db (delay (let [p (projection-path)] (log "projection in" p) (proj/open p))))

(defn- event-count [g] (get-in g [:meta :events] 0))

(defn- projected
  "The projection, caught up with graph `g` (rebuilt when it lags the
   batches or another fold wrote it). Call under the graph's lock."
  [gid g]
  (when-let [ms (proj/ensure! @db gid (event-count g) #(vec (mapcat second (:batches g))))]
    (log "projection of" gid "rebuilt:" (event-count g) "events in" ms "ms"))
  @db)

(defn- read-graph [gid f]
  (let [st (store)]
    (locking (lock-of gid)
      (let [g (loaded st gid)]
        (f (projected gid g) g)))))

(defn structure "[head json]" [gid] (read-graph gid (fn [db g] [(event-count g) (proj/structure db gid)])))
(defn node [gid id] (read-graph gid (fn [db _] (proj/node db gid id))))
(defn changes-since [gid n] (read-graph gid (fn [db _] (proj/changes-since db gid n))))

(defn search
  "[[id class title short parent score]]: FTS, merged with cosine over the vectors
   by reciprocal rank fusion when an embedding model is configured and the
   query could be embedded (inside the daily cap)."
  [gid text limit]
  (let [model (embedder/model)
        qv (when (and model (not (str/blank? text)))
             (try (first (embedder/embed! @db [text]))   ; outside the lock: a gateway call
                  (catch Exception e (log "query embedding failed, words only:" (ex-message e)) nil)))]
    (read-graph gid (fn [db _]
                      (let [fts (proj/search db gid text (* 2 limit))]
                        (if-not qv
                          (vec (take limit fts))
                          (let [near (embedder/nearest (proj/vectors db gid model) qv (* 2 limit))
                                fused (embedder/rrf [(map first fts) near] limit)
                                info (into {} (map (juxt first identity)) (proj/rows-of db gid (map key fused)))]
                            (vec (for [[id score] fused :let [r (info id)] :when r] (conj r score))))))))))

;; --- the API -----------------------------------------------------------------------

(defn list-graphs
  "Every stored graph: {:id :title :ontology :events :updated :seq}."
  []
  (let [st (store)]
    (vec (for [gid (graph-ids st)
               :let [g (locking (lock-of gid) (loaded st gid))]]
           (assoc (:meta g) :seq (:seq g))))))

(defn events-after
  "{:seq :events} — every event stored after batch `after` (0: the whole graph)."
  [gid after]
  (let [st (store)
        g (locking (lock-of gid) (loaded st gid))]
    {:seq (:seq g)
     :events (vec (mapcat second (filter #(> (first %) (or after 0)) (:batches g))))}))

(defn append!
  "Judge, project and store the events of a POST as one batch (those already
   known skipped) and answer {:seq :stored :events :head}: the new batch
   seq, how many were stored, every OTHER event after the client's batch
   `after` — what it has not seen — and the graph's event count. The first
   refused event stores nothing: {:error … :event id}."
  [gid events after]
  (let [st (store)]
    (locking (lock-of gid)
      (let [g (loaded st gid)
            fresh (vec (distinct (filter #(and (map? %) (string? (:ev/id %)) (not ((:ids g) (:ev/id %)))) events)))
            n (inc (:seq g))
            refused (when (seq fresh)
                      (proj/append! (projected gid g) gid (event-count g) fresh #(write-batch! st gid n fresh)))]
        (or refused
            (let [g (if (seq fresh)
                      (-> g
                          (assoc :seq n)
                          (update :ids into (map :ev/id fresh))
                          (update :meta meta-of fresh)
                          (update :batches conj [n fresh]))
                      g)
                  sent (set (keep :ev/id events))]
              (swap! graphs assoc gid g)
              {:seq (:seq g)
               :stored (count fresh)
               :events (vec (remove #(sent (:ev/id %)) (mapcat second (filter #(> (first %) (or after 0)) (:batches g)))))
               :head (event-count g)}))))))
