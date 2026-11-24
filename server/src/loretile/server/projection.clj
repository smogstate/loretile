(ns loretile.server.projection
  "The server's graph projection (wiki/Graph-Store.md, milestone 4): one
   SQLite file, the SAME tables and the same `apply` as the browser's
   (public/js/graphstore.js) — nodes and edges with their history
   (from_seq/to_seq), FTS5 over current rows, heads, vectors. `reader` is
   the fold's `Reader` over a graph's current rows, so `fold/guard-with` and
   `fold/delta` run against the tables; `apply-changes!` writes what
   `graph-store/changes` computed. No Loretile rule lives here.

   ponytail: one connection behind one lock (SQLite has one writer anyway);
   reads wait behind a write. A read-only WAL connection pool when
   concurrent readers matter."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [loretile.fold :as fold]
            [loretile.graph-store :as gs])
  (:import (java.nio ByteBuffer ByteOrder)
           (java.security MessageDigest)
           (java.sql Connection DriverManager PreparedStatement ResultSet)))

(def ^:private schema
  ["CREATE TABLE IF NOT EXISTS nodes (graph TEXT, id TEXT, from_seq INTEGER, to_seq INTEGER,
     class TEXT, status TEXT, title TEXT, short TEXT, parent TEXT, text TEXT, text_hash TEXT, data TEXT,
     PRIMARY KEY (graph, id, from_seq))"
   "CREATE UNIQUE INDEX IF NOT EXISTS nodes_now    ON nodes (graph, id)     WHERE to_seq IS NULL"
   "CREATE INDEX        IF NOT EXISTS nodes_parent ON nodes (graph, parent) WHERE to_seq IS NULL"
   "CREATE INDEX        IF NOT EXISTS nodes_from   ON nodes (graph, from_seq)"
   ;; closed rows only — a plain (graph, to_seq) index is a planner trap (proof finding 1)
   "CREATE INDEX        IF NOT EXISTS nodes_closed ON nodes (graph, to_seq) WHERE to_seq IS NOT NULL"
   "CREATE TABLE IF NOT EXISTS edges (graph TEXT, \"from\" TEXT, type TEXT, \"to\" TEXT, from_seq INTEGER, to_seq INTEGER,
     status TEXT, data TEXT, PRIMARY KEY (graph, \"from\", type, \"to\", from_seq))"
   "CREATE UNIQUE INDEX IF NOT EXISTS edges_now    ON edges (graph, \"from\", type, \"to\") WHERE to_seq IS NULL"
   "CREATE INDEX        IF NOT EXISTS edges_to     ON edges (graph, \"to\")              WHERE to_seq IS NULL"
   "CREATE INDEX        IF NOT EXISTS edges_type   ON edges (graph, type)              WHERE to_seq IS NULL"
   "CREATE INDEX        IF NOT EXISTS edges_fseq   ON edges (graph, from_seq)"
   "CREATE INDEX        IF NOT EXISTS edges_closed ON edges (graph, to_seq) WHERE to_seq IS NOT NULL"
   "CREATE TABLE IF NOT EXISTS discarded (graph TEXT, key TEXT, seq INTEGER, PRIMARY KEY (graph, key))"
   "CREATE TABLE IF NOT EXISTS heads (graph TEXT PRIMARY KEY, seq INTEGER, last_id TEXT, fold TEXT, props TEXT)"
   "CREATE TABLE IF NOT EXISTS vectors (hash TEXT, model TEXT, vec BLOB, PRIMARY KEY (hash, model))"
   "CREATE VIRTUAL TABLE IF NOT EXISTS nodes_fts USING fts5(text, content='', contentless_delete=1)"
   ;; server only: the embedder's daily spend, so a restart does not reopen the cap
   "CREATE TABLE IF NOT EXISTS spend (day TEXT PRIMARY KEY, tokens INTEGER)"])

(def fold-hash
  "A hash of the fold's sources: a projection written by another fold is rebuilt."
  (delay (let [md (MessageDigest/getInstance "SHA-256")]
           (doseq [f ["loretile/fold.cljc" "loretile/schema.cljc" "loretile/profile.cljc" "loretile/graph_store.cljc"]]
             (.update md (.getBytes ^String (slurp (or (io/resource f) (throw (ex-info (str f " is not on the classpath") {}))))
                                    "UTF-8")))
           (subs (format "%064x" (BigInteger. 1 (.digest md))) 0 16))))

(defn open
  "The projection in SQLite file `path` (created with its tables)."
  [path]
  (io/make-parents (io/file path))
  (let [c (DriverManager/getConnection (str "jdbc:sqlite:" path))]
    (with-open [s (.createStatement c)]
      (.execute s "PRAGMA journal_mode=WAL")
      (.execute s "PRAGMA synchronous=NORMAL")
      (doseq [sql schema] (.executeUpdate s sql)))
    {:conn c :stmts (atom {}) :lock (Object.)}))

(defn close [{:keys [^Connection conn]}] (.close conn))

;; --- SQL, synchronous, statements prepared once ----------------------------------

(defn- ps ^PreparedStatement [{:keys [conn stmts]} sql]
  (or (@stmts sql) (let [s (.prepareStatement ^Connection conn sql)] (swap! stmts assoc sql s) s)))

(defn- bind ^PreparedStatement [db sql params]
  (let [s (ps db sql)]
    (dorun (map-indexed (fn [i p] (.setObject s (int (inc i)) p)) params))
    s))

(defn- rows [db sql & params]
  (with-open [^ResultSet rs (.executeQuery (bind db sql params))]
    (let [n (.getColumnCount (.getMetaData rs))]
      (loop [acc (transient [])]
        (if (.next rs)
          (recur (conj! acc (mapv #(.getObject rs (int %)) (range 1 (inc n)))))
          (persistent! acc))))))

(defn- value [db sql & params] (ffirst (apply rows db sql params)))
(defn- exec! [db sql & params] (.executeUpdate (bind db sql params)))

(defn transact!
  "f inside one transaction, under the projection's lock; rolled back when
   f throws or returns {:error …}."
  [{:keys [^Connection conn lock] :as db} f]
  (locking lock
    (.setAutoCommit conn false)
    (try
      (let [r (f)]
        (if (:error r) (.rollback conn) (.commit conn))
        r)
      (catch Throwable t (.rollback conn) (throw t))
      (finally (.setAutoCommit conn true)))))

(defmacro ^:private locked [db & body] `(locking (:lock ~db) ~@body))

;; --- the fold's reader over current rows -----------------------------------------

(defn- kw-str [k] (subs (str k) 1))
(defn- edge-data [db graph status from type to]
  (gs/row->edge (value db "SELECT data FROM edges WHERE graph=? AND \"from\"=? AND type=? AND \"to\"=? AND status=? AND to_seq IS NULL"
                       graph from (kw-str type) to status)))
(defn- id-nodes [rs] (map (fn [[id d]] [id (gs/<-json d)]) rs))

(defn- heads-of [db graph]
  (or (some-> (value db "SELECT props FROM heads WHERE graph=?" graph) edn/read-string) {}))

(defn dump
  "Every current row of a graph — what `graph-store/rows->kg` takes."
  [db graph]
  (locked db
    {:nodes (rows db "SELECT id, class, data FROM nodes WHERE graph=? AND to_seq IS NULL" graph)
     :edges (rows db "SELECT status, data FROM edges WHERE graph=? AND to_seq IS NULL" graph)
     :discarded (map first (rows db "SELECT key FROM discarded WHERE graph=?" graph))
     :heads (heads-of db graph)}))

(defrecord SqlReader [db graph]
  fold/Reader
  (heads [_] (heads-of db graph))
  (node [_ id] (when-let [[c d] (first (rows db "SELECT class, data FROM nodes WHERE graph=? AND id=? AND to_seq IS NULL" graph id))]
                 (gs/row->node c d)))
  (edge [_ from type to] (edge-data db graph "accepted" from type to))
  (edge-proposal [_ from type to] (edge-data db graph "proposed" from type to))
  (link? [_ l] (some? (edge-data db graph "link" (:from l) (:type l) (:to l))))
  (edges-of [_ id] (map (comp gs/row->edge first)
                        (rows db "SELECT data FROM edges WHERE graph=? AND status='accepted' AND to_seq IS NULL AND (\"from\"=? OR \"to\"=?)" graph id id)))
  (links-of [_ id] (map (comp gs/row->edge first)
                        (rows db "SELECT data FROM edges WHERE graph=? AND status='link' AND to_seq IS NULL AND (\"from\"=? OR \"to\"=?)" graph id id)))
  ;; ponytail: edges as {:from :to :type} off the columns, data not decoded —
  ;; all the cycle walk reads; at 40k a full decode per guard costs ~0.5 s.
  ;; Decode `data` here if the fold ever reads more of these edges.
  (edges-of-type [_ type] (map (fn [[f t]] {:from f :to t :type type})
                               (rows db "SELECT \"from\", \"to\" FROM edges WHERE graph=? AND type=? AND status='accepted' AND to_seq IS NULL" graph (kw-str type))))
  (written-from [_ s]
    (let [s (gs/encode s)]   ; data is tagged JSON: a string starting with ":" or "'" is stored with a "'"
      {:objects (id-nodes (rows db "SELECT id, data FROM nodes WHERE graph=? AND class='objects' AND to_seq IS NULL AND json_extract(data, '$.\"grounded-in\"')=?" graph s))
       :concepts (id-nodes (rows db "SELECT id, data FROM nodes WHERE graph=? AND class='concepts' AND to_seq IS NULL AND json_extract(data, '$.\"source-id\"')=?" graph s))}))
  (all-of [_ k] (seq (id-nodes (rows db "SELECT id, data FROM nodes WHERE graph=? AND class=? AND to_seq IS NULL" graph (name k)))))
  ;; `parent` = concept-id, else primary-concept-id; filtered to the map reader's exact rule
  (children [_ id] (for [[c nid d] (rows db "SELECT class, id, data FROM nodes WHERE graph=? AND parent=? AND class IN ('objects','items') AND to_seq IS NULL" graph id)
                         :let [n (gs/<-json d)]
                         :when (= id (if (= c "objects") (:concept-id n) (:primary-concept-id n)))]
                     [(keyword c) nid n]))
  (discarded? [_ k] (some? (value db "SELECT 1 FROM discarded WHERE graph=? AND key=?" graph (pr-str k))))
  (materialize [_] (gs/rows->kg (dump db graph))))

(defn reader [db graph] (->SqlReader db graph))

;; --- the one write ---------------------------------------------------------------

(defn apply-changes!
  "One event's row changes (`graph-store/changes`), as graphstore.js `apply`:
   removals first, the current row closed (to_seq) and the new one inserted
   (from_seq), FTS by rowid, the head moved. Call inside `transact!`."
  [db graph {s :seq :keys [nodes removed-nodes edges removed-edges discarded heads]} last-id]
  (let [close-node! (fn [id]
                      (when-let [rid (value db "SELECT rowid FROM nodes WHERE graph=? AND id=? AND to_seq IS NULL" graph id)]
                        (exec! db "UPDATE nodes SET to_seq=? WHERE rowid=?" s rid)
                        (exec! db "DELETE FROM nodes_fts WHERE rowid=?" rid)))
        close-edge! (fn [from type to]
                      (when-let [rid (value db "SELECT rowid FROM edges WHERE graph=? AND \"from\"=? AND type=? AND \"to\"=? AND to_seq IS NULL" graph from type to)]
                        (exec! db "UPDATE edges SET to_seq=? WHERE rowid=?" s rid)))]
    (run! close-node! removed-nodes)
    (doseq [n nodes]
      (close-node! (:id n))
      (let [rid (value db "INSERT INTO nodes VALUES (?,?,?,NULL,?,?,?,?,?,?,?,?) RETURNING rowid"
                       graph (:id n) s (:class n) (:status n) (:title n) (:short n) (:parent n) (:text n) (:text_hash n) (:data n))]
        (when (seq (:text n)) (exec! db "INSERT INTO nodes_fts (rowid, text) VALUES (?,?)" rid (:text n)))))
    (doseq [[from type to] removed-edges] (close-edge! from type to))
    (doseq [e edges]
      (close-edge! (:from e) (:type e) (:to e))
      (exec! db "INSERT INTO edges VALUES (?,?,?,?,?,NULL,?,?)" graph (:from e) (:type e) (:to e) s (:status e) (:data e)))
    (doseq [k discarded] (exec! db "INSERT OR IGNORE INTO discarded VALUES (?,?,?)" graph k s))
    (exec! db "INSERT INTO heads (graph, seq, last_id, fold) VALUES (?,?,?,?)
               ON CONFLICT(graph) DO UPDATE SET seq=excluded.seq, last_id=excluded.last_id, fold=excluded.fold"
           graph s last-id @fold-hash)
    (when heads (exec! db "UPDATE heads SET props=? WHERE graph=?" (pr-str heads) graph))))

(defn- project!
  "Event `e` as event number `n`: delta through the tables, row changes, applied."
  [db graph n e]
  (let [r (reader db graph)
        c (gs/changes (fold/delta r e) (fold/heads r) n)]
    (apply-changes! db graph c (:ev/id e))))

(defn append!
  "Judge and project `events` (numbered after `head`) in ONE transaction:
   each guarded on the tables as the batch's earlier events left them. The
   first refusal rolls everything back → {:error … :event id}. All accepted
   → `persist!` (the event store's write) runs, then the commit → nil."
  [db graph head events persist!]
  (transact! db
             (fn []
               (or (first (keep-indexed (fn [i e]
                                          (if-let [err (fold/guard-with (reader db graph) e)]
                                            {:error err :event (:ev/id e)}
                                            (do (project! db graph (+ head i 1) e) nil)))
                                        events))
                   (do (persist!) nil)))))

(defn clear! [db graph]
  (transact! db (fn []
                  (doseq [[rid] (rows db "SELECT rowid FROM nodes WHERE graph=? AND to_seq IS NULL" graph)]
                    (exec! db "DELETE FROM nodes_fts WHERE rowid=?" rid))
                  (doseq [t ["nodes" "edges" "discarded" "heads"]]
                    (exec! db (str "DELETE FROM " t " WHERE graph=?") graph)))))

(defn head
  "{:seq :last-id :fold} of a graph, or nil."
  [db graph]
  (locked db (when-let [[s l f] (first (rows db "SELECT seq, last_id, fold FROM heads WHERE graph=?" graph))]
               {:seq s :last-id l :fold f})))

(defn rebuild!
  "Clear a graph's rows and replay every event, guard-less, a few thousand
   per transaction. The vectors stay (keyed by text)."
  [db graph events]
  (clear! db graph)
  (doseq [[i chunk] (map-indexed vector (partition-all 2000 events))]
    (transact! db #(doseq [[j e] (map-indexed vector chunk)]
                     (project! db graph (+ (* i 2000) j 1) e)))))

(defn ensure!
  "Rebuild a graph whose rows lag its `n` events or were written by another
   fold. → ms spent rebuilding, or nil."
  [db graph n events-fn]
  (let [{s :seq f :fold} (head db graph)]
    (when-not (or (and (zero? n) (nil? s)) (and (= n s) (= f @fold-hash)))
      (let [t (System/nanoTime)]
        (rebuild! db graph (events-fn))
        (quot (- (System/nanoTime) t) 1000000)))))

;; --- reads (the shapes graphstore.js answers, as JSON) -----------------------------

(defn structure
  "The structure as one JSON string, built by SQLite."
  [db graph]
  (locked db
    (str "{\"nodes\":" (value db "SELECT json_group_array(json_array(id, class, status, title, short, parent)) FROM nodes WHERE graph=? AND to_seq IS NULL" graph)
         ",\"edges\":" (value db "SELECT json_group_array(json_array(\"from\", type, \"to\", status)) FROM edges WHERE graph=? AND to_seq IS NULL" graph)
         "}")))

(defn node
  "{:node [class data] :children [[class id data]] :out [data] :in [data]} —
   data as the stored JSON strings, like the Worker's — or nil."
  [db graph id]
  (locked db
    (when-let [n (first (rows db "SELECT class, data FROM nodes WHERE graph=? AND id=? AND to_seq IS NULL" graph id))]
      {:node n
       :children (rows db "SELECT class, id, data FROM nodes WHERE graph=? AND parent=? AND to_seq IS NULL" graph id)
       :out (map first (rows db "SELECT data FROM edges WHERE graph=? AND \"from\"=? AND status='accepted' AND to_seq IS NULL" graph id))
       :in (map first (rows db "SELECT data FROM edges WHERE graph=? AND \"to\"=? AND status='accepted' AND to_seq IS NULL" graph id))})))

(defn fts-query
  "Words → an FTS5 query: every word as a prefix, any of them (graphstore.js `ftsQuery`)."
  [text]
  (->> (str/split (str/lower-case (or text "")) #"[^\p{L}\p{N}_]+")
       (filter #(> (count %) 1))
       (map #(str "\"" (str/replace % "\"" "") "\"*"))
       (str/join " OR ")))

(defn search
  "[[id class title short parent score]] best first, by FTS5 bm25 — the parent
   names a material hit's topic (as the browser's store answers)."
  [db graph text limit]
  (let [q (fts-query text)]
    (if (str/blank? q)
      []
      (locked db (rows db "SELECT n.id, n.class, n.title, n.short, n.parent, -bm25(nodes_fts) AS score
                           FROM nodes_fts f JOIN nodes n ON n.rowid = f.rowid
                           WHERE nodes_fts MATCH ? AND n.graph=? AND n.to_seq IS NULL
                           ORDER BY bm25(nodes_fts) LIMIT ?" q graph limit)))))

(defn changes-since [db graph s]
  (locked db
    {:nodes (rows db "SELECT id, class, status, title, short, parent, to_seq FROM nodes WHERE graph=? AND (from_seq > ? OR to_seq > ?)" graph s s)
     :edges (rows db "SELECT \"from\", type, \"to\", status, to_seq FROM edges WHERE graph=? AND (from_seq > ? OR to_seq > ?)" graph s s)
     :head (some-> (head db graph) (#(-> % (assoc :lastId (:last-id %)) (dissoc :last-id))))}))

;; --- vectors ------------------------------------------------------------------------

(defn pending
  "[[hash text]] — current texts of every graph with no vector for `model`."
  [db model limit]
  (locked db (rows db "SELECT DISTINCT n.text_hash, n.text FROM nodes n
                       WHERE n.to_seq IS NULL AND n.text <> ''
                         AND NOT EXISTS (SELECT 1 FROM vectors v WHERE v.hash = n.text_hash AND v.model = ?)
                       LIMIT ?" model limit)))

(defn- f32-bytes ^bytes [v]
  (let [b (.order (ByteBuffer/allocate (* 4 (count v))) ByteOrder/LITTLE_ENDIAN)]   ; = a JS Float32Array
    (doseq [x v] (.putFloat b (float x)))
    (.array b)))

(defn- bytes-f32 ^floats [^bytes bs]
  (let [fb (.asFloatBuffer (.order (ByteBuffer/wrap bs) ByteOrder/LITTLE_ENDIAN))
        a (float-array (.remaining fb))]
    (.get fb a)
    a))

(defn put-vectors! [db model vecs]
  (transact! db #(doseq [[h v] vecs] (exec! db "INSERT OR REPLACE INTO vectors VALUES (?,?,?)" h model (f32-bytes v)))))

(defn vectors
  "[[id float-array]] of a graph's current nodes that have a vector for `model`."
  [db graph model]
  (locked db (mapv (fn [[id b]] [id (bytes-f32 b)])
                   (rows db "SELECT n.id, v.vec FROM nodes n JOIN vectors v ON v.hash = n.text_hash AND v.model = ?
                             WHERE n.graph=? AND n.to_seq IS NULL" model graph))))

(defn rows-of
  "[[id class title short parent]] for these ids (current rows)."
  [db graph ids]
  (locked db (vec (keep #(first (rows db "SELECT id, class, title, short, parent FROM nodes WHERE graph=? AND id=? AND to_seq IS NULL" graph %)) ids))))

(defn spent [db day] (locked db (or (value db "SELECT tokens FROM spend WHERE day=?" day) 0)))
(defn spend! [db day n]
  (locked db (exec! db "INSERT INTO spend VALUES (?,?) ON CONFLICT(day) DO UPDATE SET tokens = tokens + excluded.tokens" day n)))
