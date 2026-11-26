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
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [loretile.server.config :as config]
            [loretile.server.embedder :as embedder]
            [loretile.server.log :refer [log]]
            [loretile.fold :as fold]
            [loretile.graph-store :as gs]
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
              ;; renameTo says false, it does not throw: unchecked, the client
              ;; was told «stored» for a batch that was never written
              (when-not (.renameTo tmp f)
                (throw (ex-info (str "could not store batch " (batch-name n) " of " gid) {}))))
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
(defn rows-json
  "Every current row as one JSON string (proj/dump-json), with the batch seq
   it stands at — a browser seeded from it pulls from exactly there."
  [gid]
  (read-graph gid (fn [db g] (let [j (proj/dump-json db gid)] (str "{\"seq\":" (:seq g) "," (subs j 1))))))

;; --- graphs folded from a filtered log (wiki/Graph-Store.md § Backend mode, step 2) ---
;; The browser keeps no log in backend mode; what still needs one asks here.
;; ponytail: folded in memory on every new head (the JVM folds a 40k-topic
;; log in seconds); one entry per graph — an LRU when several views matter.

(defonce ^:private head-kgs (atom {}))    ; gid → [event count, the graph as it stands]
(defonce ^:private views (atom {}))       ; gid → [[event count params] JSON]

(defn- head-kg [gid n evs]
  (let [[k v] (get @head-kgs gid)]
    (if (= k n) v (let [v (fold/kg evs)] (swap! head-kgs assoc gid [n v]) v))))

(defn view
  "The graph folded from a filtered log, in `rows-json`'s shape plus \"cut\"
   and \"changes\". `course` (and `pin`): what a learner pinned at that
   publication sees (`fold/events-at`; a pin missing from the log → the
   newest publication), with how many events since it change the course
   (`fold/course-changes`, on the graph as it stands). `valid` / `recorded`
   (ms): the graph as it stood (`fold/events-as-of`)."
  [gid {:keys [course pin valid recorded] :as params}]
  (let [st (store)]
    (locking (lock-of gid)
      (let [g (loaded st gid)
            n (event-count g)
            [k v] (get @views gid)]
        (if (= k [n params])
          v
          (let [evs (vec (mapcat second (:batches g)))
                newest (when course (fold/publication-cut evs course))
                cut (when course (if (and pin (some #(= pin (:ev/id %)) evs)) pin newest))
                shown (cond course (fold/events-at evs cut)
                            (or valid recorded) (fold/events-as-of evs (cond-> {} valid (assoc :valid valid) recorded (assoc :recorded recorded)))
                            :else evs)
                changes (when (and course newest) (fold/course-changes (head-kg gid n evs) evs newest (get-in (head-kg gid n evs) [:courses course])))
                v (proj/kg->dump-json (fold/kg shown) {"head" n "lastId" (:ev/id (peek evs)) "cut" cut "newest" newest "changes" changes})]
            (swap! views assoc gid [[n params] v])
            v))))))

(defn changes-since [gid n] (read-graph gid (fn [db _] (proj/changes-since db gid n))))

(defonce ^:private query-vecs (atom {}))   ; [model text] → vector — a query asked again costs nothing

(defn- query-vec
  "The query's vector from the embedding provider (inside the daily cap), or
   nil: no model, or the provider refused or failed — the search then goes
   by words."
  [model text]
  (or (get @query-vecs [model text])
      (try (when-let [v (first (embedder/embed! @db [text]))]   ; outside any lock: a gateway call
             ;; ponytail: forget all past 256 queries — an LRU when it matters
             (swap! query-vecs #(assoc (if (> (count %) 256) {} %) [model text] v))
             v)
           (catch Exception e (log "query embedding failed, words only:" (ex-message e)) nil))))

(defn search
  "Search graph `gid` (wiki/Graph-Store.md): the query's vector is made HERE,
   by the embedding provider — the store's search (`proj/search`) takes it
   and calls nothing. `mode`: hybrid (default), semantic, verbatim."
  [gid text limit & [mode]]
  (let [mode (or mode "hybrid")
        model (embedder/model)
        v (when (and model (not= "verbatim" mode) (not (str/blank? text))) (query-vec model text))]
    (read-graph gid (fn [db _] (proj/search db gid {:text text :vec v :model model :mode mode :limit limit})))))

;; --- the API -----------------------------------------------------------------------

(defn list-graphs
  "Every stored graph: {:id :title :ontology :events :updated :seq}."
  []
  (let [st (store)]
    (vec (for [gid (graph-ids st)
               :let [g (locking (lock-of gid) (loaded st gid))]]
           (assoc (:meta g) :seq (:seq g))))))

;; --- changesets (wiki/Changesets.md) ---------------------------------------------
;; TEXT that changes a graph, posted by CI or a tool with the graph's id in its
;; front matter; stored with the graphs under `.changesets/` (the dot keeps it
;; apart from graph ids). Pending = not yet a source `changeset:<id>` in its
;; graph — picking one up registers that source.

(def changeset-max (* 200 1024))

(defn- unquote-value [v]
  (let [v (str/trim v)]
    (if-let [[_ q] (or (re-matches #"\"(.*)\"" v) (re-matches #"'(.*)'" v))]
      q
      (str/trim (str/replace v #"\s+#.*$" "")))))   ; a YAML comment, unless quoted

(defn front-matter
  "Markdown opening with a `---` front matter → [{:field value} body], or nil."
  [text]
  (when-let [[_ head body] (re-matches #"(?s)\A\uFEFF?---\r?\n(.*?)\r?\n---[ \t]*(?:\r?\n(.*))?\z" (str text))]
    [(into {} (keep (fn [line] (when-let [[_ k v] (re-matches #"\s*([A-Za-z][A-Za-z0-9_-]*)\s*:(.*)" line)]
                                 (let [v (unquote-value v)] (when (seq v) [(keyword (str/lower-case k)) v])))))
           (str/split-lines head))
     (or body "")]))

(defn- cs-file [dir id] (io/file dir ".changesets" (str id ".md")))
(defn- cs-key [key id] (s3/join-key key (str ".changesets/" id ".md")))

(defn- cs-text [{:keys [kind dir bucket key aws]} id]
  (case kind
    :file (let [f (cs-file dir id)] (when (.isFile f) (slurp f :encoding "UTF-8")))
    :s3 (try (String. ^bytes (:bytes (s3/get-object aws bucket (cs-key key id) nil)) StandardCharsets/UTF_8)
             (catch Exception _ nil))))

(defn- cs-ids [{:keys [kind dir bucket key aws]}]
  (keep #(second (re-matches #"(.+)\.md" %))
        (case kind
          :file (some->> (.listFiles (io/file dir ".changesets")) (map #(.getName ^File %)))
          :s3 (map #(last (str/split % #"/")) (s3/list-keys aws bucket (s3/join-key key ".changesets/"))))))

(defn- cs-header [id text]
  (let [[fm body] (front-matter text)]
    {:id id :graph (:graph fm) :source (:source fm)
     :title (or (:title fm) (some->> (re-find #"(?m)^#{1,3}\s+(.+)$" body) second str/trim) id)}))

(defonce ^:private cs-headers (atom nil))   ; id → header, read once per process, kept by `post-changeset!`

(defn- headers [st]
  (or @cs-headers
      (reset! cs-headers (into {} (keep (fn [id] (some->> (cs-text st id) (cs-header id) (vector id)))) (cs-ids st)))))

(defn post-changeset!
  "Store a changeset (markdown text): {:id :graph :title} or {:error … :status n}
   — no front matter or no `graph` (400), a graph the server does not hold (400),
   a bad or taken id (400 / 409), too big (413)."
  [text]
  (let [st (store)
        [fm _] (front-matter text)
        gid (:graph fm)
        id (or (:id fm) (str "cs-" (Long/toString (System/currentTimeMillis) 36)))]
    (cond
      (> (count (.getBytes ^String text StandardCharsets/UTF_8)) changeset-max) {:status 413 :error "a changeset is at most 200 KB"}
      (nil? fm) {:status 400 :error "a changeset opens with a --- front matter naming its graph"}
      (nil? gid) {:status 400 :error "the front matter must name the graph: graph: <id>"}
      (not (some #{gid} (graph-ids st))) {:status 400 :error (str "no graph " gid " on this server")}
      (not (valid-id? id)) {:status 400 :error (str "bad changeset id: " id)}
      (contains? (headers st) id) {:status 409 :error (str "changeset " id " exists")}
      :else
      (do (case (:kind st)
            :file (let [f (cs-file (:dir st) id) tmp (io/file (:dir st) ".changesets" (str "." id ".tmp"))]
                    (io/make-parents f)
                    (spit tmp text :encoding "UTF-8")
                    (when-not (.renameTo tmp f) (throw (ex-info (str "could not store changeset " id) {}))))
            :s3 (s3/put-object! (:aws st) (:bucket st) (cs-key (:key st) id) (.getBytes ^String text StandardCharsets/UTF_8)
                                "text/markdown; charset=utf-8"))
          (let [h (cs-header id text)]
            (swap! cs-headers assoc id h)
            (select-keys h [:id :graph :title]))))))

(defn pending-changesets
  "The changesets of graph `gid` not yet applied (no source `changeset:<id>`
   in it): [{:id :title :source}], in id order."
  [gid]
  (let [st (store)
        mine (sort-by :id (filter #(= gid (:graph %)) (vals (headers st))))]
    (read-graph gid (fn [db _]
                      (vec (for [h mine :when (nil? (proj/node db gid (str "changeset:" (:id h))))]
                             (select-keys h [:id :title :source])))))))

(defn changeset
  "One changeset: {:id :graph :title :source :text} (the text without its front matter), or nil."
  [id]
  (when (valid-id? id)
    (when-let [text (cs-text (store) id)]
      (assoc (cs-header id text) :text (second (front-matter text))))))

(defn events-after
  "{:seq :events} — every event stored after batch `after` (0: the whole
   graph); with `since` (an event count) also :rows, what changed in the
   projection after it (`proj/delta-since`) — what a browser in backend mode
   applies instead of folding."
  ([gid after] (events-after gid after nil))
  ([gid after since]
   (let [st (store)]
     (locking (lock-of gid)
       (let [g (loaded st gid)]
         (cond-> {:seq (:seq g)
                  :events (vec (mapcat second (filter #(> (first %) (or after 0)) (:batches g))))}
           since (assoc :rows (proj/delta-since (projected gid g) gid since))))))))

(defn events-after-json
  "`events-after` as JSON: the events tagged like the browser's IndexedDB
   (graph-store/encode) — parsed natively there, ~2.5× faster than EDN."
  [gid after]
  (let [{s :seq evs :events} (events-after gid after)]
    (str "{\"seq\":" s ",\"events\":" (json/write-str (mapv gs/encode evs)) "}")))

(defn append!
  "Judge, project and store the events of a POST as one batch (those already
   known skipped) and answer {:seq :stored :events :head}: the new batch
   seq, how many were stored, every OTHER event after the client's batch
   `after` — what it has not seen — and the graph's event count. The first
   refused event stores nothing: {:error … :event id}."
  [gid events after & [since]]
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
              (cond-> {:seq (:seq g)
                       :stored (count fresh)
                       :events (vec (remove #(sent (:ev/id %)) (mapcat second (filter #(> (first %) (or after 0)) (:batches g)))))
                       :head (event-count g)}
                ;; the rows the client applies instead of folding (backend mode, step 3)
                since (assoc :rows (proj/delta-since (projected gid g) gid since)))))))))
