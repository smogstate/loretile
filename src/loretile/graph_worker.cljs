(ns loretile.graph-worker
  "The fold and the guard inside the browser's graph-store Worker
   (wiki/Graph-Store.md § Next, step 1): an event judged and turned into row
   changes against SQLite through a reader — the server's `SqlReader`, here
   over the Worker's sqlite-wasm database. Compiled as an ES module
   (shadow-cljs build :graphfold) that public/js/graphstore.js imports beside
   SQLite; it calls the database synchronously, as the fold expects."
  (:require [cljs.reader :as edn]
            [loretile.fold :as fold]
            [loretile.graph-store :as gs]))

(defn- rows [^js db sql & params] (js->clj (.selectArrays db sql (clj->js params))))
(defn- value [^js db sql & params] (.selectValue db sql (clj->js params)))
(defn- kw-str [k] (if-let [n (namespace k)] (str n "/" (name k)) (name k)))
(defn- id-nodes [rs] (map (fn [[id d]] [id (gs/<-json d)]) rs))

(defn- edge-data [db graph status from type to]
  (some-> (value db "SELECT data FROM edges WHERE graph=? AND status=? AND \"from\"=? AND type=? AND \"to\"=? AND to_seq IS NULL"
                 graph status from (kw-str type) to)
          gs/row->edge))

(defn- dump [db graph]
  {:nodes (rows db "SELECT id, class, data FROM nodes WHERE graph=? AND to_seq IS NULL" graph)
   :edges (rows db "SELECT status, data FROM edges WHERE graph=? AND to_seq IS NULL" graph)
   :discarded (map first (rows db "SELECT key FROM discarded WHERE graph=?" graph))
   :heads (some-> (value db "SELECT props FROM heads WHERE graph=?" graph) edn/read-string)})

;; the server's SqlReader (server/…/projection.clj), query for query
(defrecord SqliteReader [db graph]
  fold/Reader
  (heads [_] (or (some-> (value db "SELECT props FROM heads WHERE graph=?" graph) edn/read-string) {}))
  (node [_ id] (when-let [[c d] (first (rows db "SELECT class, data FROM nodes WHERE graph=? AND id=? AND to_seq IS NULL" graph id))]
                 (gs/row->node c d)))
  (edge [_ from type to] (edge-data db graph "accepted" from type to))
  (edge-proposal [_ from type to] (edge-data db graph "proposed" from type to))
  (link? [_ l] (some? (edge-data db graph "link" (:from l) (:type l) (:to l))))
  (edges-of [_ id] (map (comp gs/row->edge first)
                        (rows db "SELECT data FROM edges WHERE graph=? AND status='accepted' AND to_seq IS NULL AND (\"from\"=? OR \"to\"=?)" graph id id)))
  (links-of [_ id] (map (comp gs/row->edge first)
                        (rows db "SELECT data FROM edges WHERE graph=? AND status='link' AND to_seq IS NULL AND (\"from\"=? OR \"to\"=?)" graph id id)))
  (edges-of-type [_ type]
    (map (fn [[f t]] {:from f :to t :type type})
         (rows db "SELECT \"from\", \"to\" FROM edges WHERE graph=? AND type=? AND status='accepted' AND to_seq IS NULL" graph (kw-str type))))
  ;; ponytail: one indexed query per node walked (edges_now prefix); an
  ;; adjacency in memory like the server's when a walk shows up in a profile
  (successors [_ type id]
    (map first (rows db "SELECT \"to\" FROM edges WHERE graph=? AND \"from\"=? AND type=? AND status='accepted' AND to_seq IS NULL" graph id (kw-str type))))
  (written-from [_ s]
    (let [s (gs/encode s)]   ; data is tagged JSON: a string starting with ":" or "'" is stored with a "'"
      {:objects (id-nodes (rows db "SELECT id, data FROM nodes WHERE graph=? AND class='objects' AND to_seq IS NULL AND json_extract(data, '$.\"grounded-in\"')=?" graph s))
       :concepts (id-nodes (rows db "SELECT id, data FROM nodes WHERE graph=? AND class='concepts' AND to_seq IS NULL AND json_extract(data, '$.\"source-id\"')=?" graph s))}))
  (all-of [_ k] (seq (id-nodes (rows db "SELECT id, data FROM nodes WHERE graph=? AND class=? AND to_seq IS NULL" graph (name k)))))
  (children [_ id] (for [[c nid d] (rows db "SELECT class, id, data FROM nodes WHERE graph=? AND parent=? AND class IN ('objects','items') AND to_seq IS NULL" graph id)
                         :let [n (gs/<-json d)]
                         :when (= id (if (= c "objects") (:concept-id n) (:primary-concept-id n)))]
                     [(keyword c) nid n]))
  (discarded? [_ k] (some? (value db "SELECT 1 FROM discarded WHERE graph=? AND key=?" graph (pr-str k))))
  (materialize [_] (gs/rows->kg (dump db graph))))

(defn ^:export judge
  "The guard on event `ev` (EDN) against graph `graph` as SQLite holds it:
   nil when it may be appended, else the refusal as EDN."
  [db graph ev]
  (some-> (fold/guard-with (->SqliteReader db graph) (edn/read-string ev)) pr-str))

(defn ^:export changes
  "Event `ev` (EDN) as row changes for SQLite, as event number `n` — the
   shape graphstore.js `apply` takes."
  [db graph ev n]
  (let [r (->SqliteReader db graph)
        e (edn/read-string ev)
        ch (gs/changes (fold/delta r e) (fold/heads r) n)]
    #js {:seq n :lastId (:ev/id e)
         :nodes (clj->js (:nodes ch)) :removedNodes (clj->js (:removed-nodes ch))
         :edges (clj->js (:edges ch)) :removedEdges (clj->js (:removed-edges ch))
         :discarded (clj->js (:discarded ch))
         :heads (some-> (:heads ch) pr-str)}))
