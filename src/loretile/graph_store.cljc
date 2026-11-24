(ns loretile.graph-store
  "The graph store (wiki/Graph-Store.md): an EVENT STORE (the truth, append
   only), a GRAPH (the projection — rows of nodes and edges with their
   history, rebuildable from the events) and an EMBEDDING PROVIDER, switched
   together: local (the browser) or backend (the server). This namespace is
   what both sides share — the protocols, the row codec (a delta from
   `fold/delta` → row changes; a row → a node again) and the in-memory graph
   over a folded kg (the oracle the SQLite stores are checked against, and
   what small graphs and the MCP server use). Every protocol call returns a
   promesa promise, whichever side answers it."
  (:require [clojure.string :as str]
            [promesa.core :as p]
            [loretile.fold :as fold]
            #?(:cljs [cljs.reader :as edn] :clj [clojure.edn :as edn])
            #?(:clj [clojure.data.json :as json])))

(defprotocol EventStore
  (streams [s] "the list of graphs: [{:id :title :events :updated}]")
  (append! [s graph events] "guard, append, project → {:seq n} | {:error …}")
  (events-after [s graph seq] "the log after seq: undo, export, the past view"))

(defprotocol Graph
  (structure [g graph] "{:nodes [[id class status title short parent] …] :edges [[from type to status] …] :head n}")
  (node [g graph id] "{:node [class node] :children [[class id node] …] :out [edge …] :in [edge …]} or nil")
  (search [g graph opts] "{:text q :vec v? :limit n} → [{:id :class :title :short :score}] best first")
  (head [g graph] "the seq applied up to")
  (changes-since [g graph seq] "rows opened or closed after seq: {:nodes [row] :edges [row] :head n}")
  (apply-changes! [g graph changes] "the ONLY write — row changes for one or more events, one transaction")
  (pending [g graph model limit] "[{:hash :text}] — texts with no vector for this model")
  (put-vectors! [g graph model vecs] "{hash → vector}"))

(defprotocol EmbeddingProvider
  (model [p] "the vectors table's model key")
  (embed! [p texts] "→ promise of vectors, inside a spend cap that fails closed"))

;; --- data as JSON: plain where JSON has the type, tagged where it has not ----
;; keyword → ":name" · a string that starts with ":" or "'" → "'" + it · set →
;; {"#set": [...]} · map keys: keyword → "name", string → "'name", anything
;; else → {"#map": [[k v] ...]}. Readable, `json_extract`-able, exact — a
;; topic has no class schema, so decoding by schema would lose its keywords.

(defn- kw-str [k] (if-let [n (namespace k)] (str n "/" (name k)) (name k)))
(defn- str-key [s] (if-let [i (str/index-of s "/")] (keyword (subs s 0 i) (subs s (inc i))) (keyword s)))

(defn encode
  "EDN data → JSON-shaped data (string keys, vectors, strings, numbers)."
  [x]
  (cond
    (keyword? x) (str ":" (kw-str x))
    (string? x) (if (or (str/starts-with? x ":") (str/starts-with? x "'")) (str "'" x) x)
    (map? x) (if (every? #(or (keyword? %) (string? %)) (keys x))
               (into {} (map (fn [[k v]] [(if (keyword? k) (kw-str k) (str "'" k)) (encode v)])) x)
               {"#map" (mapv (fn [[k v]] [(encode k) (encode v)]) x)})
    (set? x) {"#set" (mapv encode x)}
    (sequential? x) (mapv encode x)
    :else x))

(defn decode
  "JSON-shaped data (as parsed, string keys) → the EDN it was."
  [x]
  (cond
    (string? x) (cond (str/starts-with? x "'") (subs x 1)
                      (str/starts-with? x ":") (str-key (subs x 1))
                      :else x)
    (map? x) (cond (contains? x "#set") (set (map decode (get x "#set")))
                   (contains? x "#map") (into {} (map (fn [[k v]] [(decode k) (decode v)])) (get x "#map"))
                   :else (into {} (map (fn [[k v]] [(if (str/starts-with? k "'") (subs k 1) (str-key k)) (decode v)])) x))
    (sequential? x) (mapv decode x)
    :else x))

(defn ->json [x]
  #?(:cljs (js/JSON.stringify (clj->js (encode x)))
     :clj (json/write-str (encode x))))

(defn <-json [s]
  (when s
    #?(:cljs (decode (js->clj (js/JSON.parse s)))
       :clj (decode (json/read-str s)))))

;; --- a node or an edge as a row ----------------------------------------------------

(defn text-hash
  "The same hex on the JVM and in the browser (vectors are keyed by it)."
  [s]
  (let [h (reduce (fn [h c] (mod (+ (* h 33) c) 4294967296)) 5381 (map #?(:cljs #(.charCodeAt % 0) :clj int) s))]
    #?(:cljs (.toString h 16) :clj (Long/toHexString h))))

(defn node-text
  "What search indexes and the embedder embeds: the node's words. Loretile
   decides this, the store does not."
  [n]
  (str/join ". " (remove str/blank? (map #(some-> % str str/trim)
                                         [(:title n) (:definition n) (:question n) (:text n) (:answer n)]))))

(defn- short-of [n]
  (let [s (or (:definition n) (:text n) (:question n) "")]
    (if (> (count s) 120) (subs s 0 120) s)))

(defn node-row
  "A node filed under `at` as the columns of `nodes`."
  [at id n]
  (let [text (node-text n)]
    {:id id :class (name at) :status (some-> (:status n) kw-str)
     :title (:title n) :short (short-of n)
     :parent (or (:concept-id n) (:primary-concept-id n))
     :text text :text_hash (text-hash text) :data (->json n)}))

(defn edge-row
  "An edge (status \"accepted\"), a proposed edge (\"proposed\") or an object
   link (\"link\") as the columns of `edges`. Its identity is [from type to]."
  [e status]
  {:from (:from e) :type (kw-str (:type e)) :to (:to e) :status status :data (->json e)})

(def head-keys [:graph :profile :ontology :ontology-graph? :authors :owner])

(defn changes
  "One event's delta (fold/delta) as row changes. `heads` = the graph-level
   state before the event; the changes carry it whole when the event touched it."
  [delta heads n]
  (let [{:keys [nodes edges props links discarded]} delta
        row-key (fn [[from to type]] [from (kw-str type) to])]
    {:seq n
     :nodes (vec (for [[id [at n]] nodes :when n] (node-row at id n)))
     :removed-nodes (vec (for [[id [_ n]] nodes :when (nil? n)] id))
     :edges (vec (concat (for [[_ e] edges :when e] (edge-row e "accepted"))
                         (for [[_ p] props :when p] (edge-row p "proposed"))
                         (for [[l v] links :when v] (edge-row l "link"))))
     ;; a key written in the same delta is replaced, so removals go first
     :removed-edges (vec (concat (for [[k e] edges :when (nil? e)] (row-key k))
                                 (for [[k p] props :when (nil? p)] (row-key k))
                                 (for [[l v] links :when (not v)] [(:from l) (kw-str (:type l)) (:to l)])))
     :discarded (mapv pr-str discarded)
     :heads (when (seq (:heads delta))
              (reduce-kv (fn [m k v] (if (nil? v) (dissoc m k) (assoc m k v))) heads (:heads delta)))}))

(defn row->node "[filed-key node] from a `nodes` row's class and data." [class data]
  [(keyword class) (<-json data)])

(defn row->edge [data] (<-json data))

;; --- the in-memory graph: a folded kg behind the Graph protocol ----------------

(defn- lexical-score [q text]
  (let [t (str/lower-case (or text ""))]
    (count (filter #(str/includes? t %) q))))

(defn kg-structure [kg]
  {:nodes (vec (for [at [:concepts :proposals :objects :items :sources :questions :courses :cqs]
                     [id n] (get kg at)
                     :when (string? id)]
                 [id (name at) (some-> (:status n) kw-str) (:title n) (short-of n)
                  (or (:concept-id n) (:primary-concept-id n))]))
   :edges (vec (concat (for [e (:edges kg)] [(:from e) (kw-str (:type e)) (:to e) "accepted"])
                       (for [[k p] (:proposals kg) :when (vector? k)] [(:from p) (kw-str (:type p)) (:to p) "proposed"])))})

(defrecord MemoryGraph [kg-of]   ; kg-of: graph id → its folded kg
  Graph
  (structure [_ graph] (p/resolved (kg-structure (kg-of graph))))
  (node [_ graph id]
    (let [kg (kg-of graph)]
      (p/resolved
       (when-let [[at n] (fold/node-at kg id)]
         {:node [at n]
          :children (vec (fold/children (fold/map-reader kg) id))
          :out (vec (filter #(= id (:from %)) (:edges kg)))
          :in (vec (filter #(= id (:to %)) (:edges kg)))}))))
  (search [_ graph {:keys [text limit]}]
    (let [kg (kg-of graph)
          q (remove str/blank? (str/split (str/lower-case (or text "")) #"\s+"))]
      (p/resolved
       (->> (for [[id n] (:concepts kg) :let [s (lexical-score q (node-text n))] :when (pos? s)]
              {:id id :class "concepts" :title (:title n) :short (short-of n) :score s})
            (sort-by (comp - :score)) (take (or limit 20)) vec))))
  (head [_ _] (p/resolved nil))
  (changes-since [_ _ _] (p/resolved nil))   ; no history in memory: callers re-read the structure
  (apply-changes! [_ _ _] (p/resolved nil))  ; fed by the fold itself
  (pending [_ _ _ _] (p/resolved []))
  (put-vectors! [_ _ _ _] (p/resolved nil)))

(defn memory-graph [kg-of] (->MemoryGraph kg-of))

;; --- the projection, read back: rows → a kg, for the equivalence check ----------

(defn rows->kg
  "A folded kg rebuilt from current rows (nodes [[class data]], edges
   [[status data]], discarded [edn], heads) — must equal the fold's kg (less
   its edge index): the gate every SQLite store is checked by. Nodes come as
   [id class data] — the row's id, since an inherited cq is copied without one."
  [{:keys [nodes edges discarded heads]}]
  (let [kg (merge fold/empty-kg heads)
        kg (reduce (fn [kg [id class data]] (let [[at n] (row->node class data)] (assoc-in kg [at id] n)))
                   kg nodes)
        kg (reduce (fn [kg [status data]]
                     (let [e (row->edge data)]
                       (case status
                         "accepted" (update kg :edges conj e)
                         "proposed" (assoc-in kg [:proposals [(:from e) (:to e) (:type e)]] e)
                         "link" (update kg :object-links conj e))))
                   kg edges)]
    (assoc kg :discarded (into #{} (map edn/read-string) discarded))))
