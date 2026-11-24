(ns loretile.graph-local
  "The graph store's projection in this browser (wiki/Graph-Store.md): SQLite
   in a Worker (public/js/graphstore.js), held by ONE tab — the Web Lock
   «loretile-graph-store»; other tabs queue for it and take over when the
   holder goes. It follows the OPEN graph: every log change becomes row
   changes computed by the delta fold here (an append: its events' deltas; a
   replaced log — import, rebase, migration — a rebuild). The projection is a
   cache: a tab without it, or a moment when it lags, falls back to the
   in-memory graph. Search reads it (FTS)."
  (:require-macros [loretile.build-info :refer [fold-hash]])
  (:require [cljs.reader]
            [promesa.core :as p]
            [loretile.fold :as fold]
            [loretile.graph-store :as gs]
            [loretile.llm :as llm]
            [loretile.vectors :as vectors]
            [loretile.store :as store]))

(def ^:private fold-src (fold-hash))
;; what the rows were folded by AND from: the fold's sources and the log's
;; generation (a whole rewrite of the log, same ids or not, means a rebuild)
(defn- build-fold-of [gid] (str fold-src "|" (store/log-gen gid)))
(def ^:private lock-name "loretile-graph-store")

(defonce ^:private worker (atom nil))
(defonce ^:private calls (atom {}))      ; request id → [resolve reject]
(defonce ^:private next-id (atom 0))
(defonce ready (atom false))              ; this tab holds the store and it is open
(defonce ^:private synced (atom {}))      ; graph id → {:evs log}: what the rows hold
(defonce ^:private chain (atom (p/resolved nil)))   ; syncs run one after another
(defonce last-sync (atom nil))
(defonce ^:private started-at (js/performance.now))           ; {:graph :mode :events :ms} — what the checks read

(defn- call [op args]
  (if-let [^js w @worker]
    (p/create (fn [ok no]
                (let [id (swap! next-id inc)]
                  (swap! calls assoc id [ok no])
                  (.postMessage w (clj->js (assoc args :op op :id id))))))
    (p/rejected (ex-info "no graph store in this tab" {}))))

(defn- on-message [^js e]
  (let [d (.-data e) id (.-id d)]
    (when-let [[ok no] (get @calls id)]
      (swap! calls dissoc id)
      (if (some? (.-error d)) (no (ex-info (.-error d) {})) (ok (.-ok d))))))

;; --- row changes, shaped for the Worker ------------------------------------------

(defn- js-changes [ch e]
  #js {:seq (:seq ch) :lastId (:ev/id e)
       :nodes (clj->js (:nodes ch)) :removedNodes (clj->js (:removed-nodes ch))
       :edges (clj->js (:edges ch)) :removedEdges (clj->js (:removed-edges ch))
       :discarded (clj->js (:discarded ch))
       :heads (some-> (:heads ch) pr-str)})

(defn- yield! [] (p/create (fn [ok _] (js/setTimeout ok 0))))

(defn- rebuild!
  "Fold all of `evs` into row changes, from nothing, in slices of ~25 ms that
   give the page back in between (a rebuild of 130k events is long; the
   screens must not wait for it). The graph it folds is thrown away after —
   the page keeps one graph, the store's."
  [gid evs]
  (p/loop [k fold/empty-kg i 0]
    (if (>= i (count evs))
      true
      (let [t0 (js/performance.now)
            out #js []
            [k' j] (loop [k k j i]
                     (if (or (>= j (count evs)) (> (- (js/performance.now) t0) 25) (>= (.-length out) 2000))
                       [k j]
                       (let [e (nth evs j)
                             d (fold/delta (fold/map-reader k) e)]
                         (.push out (js-changes (gs/changes d (select-keys k gs/head-keys) (inc j)) e))
                         (recur (fold/apply-delta k d) (inc j)))))]
        (p/do (call :apply {:graph gid :changes out :fold (build-fold-of gid)})
              (yield!)
              (p/recur k' j))))))

(defn- appended!
  "The events of `evs` from `from` were just appended to the open graph: the
   store's incremental fold computed their deltas (`store/recent-deltas`);
   write them as rows. false when it has not (another graph, a past view) —
   the caller rebuilds."
  [gid evs from]
  (when (and (= gid @store/current) (nil? @store/as-of) (identical? evs @store/events))
    (store/kg)                                   ; folds the new events, recording their deltas
    (let [{g :graph items :items} @store/recent-deltas
          fresh (subvec evs from)]
      (when (and (= g gid) (= (count items) (count fresh))
                 (every? true? (map #(identical? (first %1) %2) items fresh)))
        (call :apply {:graph gid :fold (build-fold-of gid)
                      :changes (to-array (map-indexed (fn [i [e d h]] (js-changes (gs/changes d h (+ from i 1)) e)) items))})))))

(defn- appended-by-fold!
  "The log grew, but not by this page's own append (another tab's edit, a
   reload): fold up to what the rows hold, then write only the new events —
   one fold, not a rebuild."
  [gid evs from]
  (let [k (fold/kg (subvec evs 0 from))
        [_ out] (reduce (fn [[k out] j]
                          (let [e (nth evs j)
                                d (fold/delta (fold/map-reader k) e)]
                            [(fold/apply-delta k d) (doto out (.push (js-changes (gs/changes d (select-keys k gs/head-keys) (inc j)) e)))]))
                        [k #js []] (range from (count evs)))]
    (call :apply {:graph gid :changes out :fold (build-fold-of gid)})))

(defn- sync-graph!
  "Bring graph `gid`'s rows to log `evs`: nothing when they hold it, the new
   events' deltas when the log only grew, a rebuild otherwise."
  [gid evs]
  (let [t0 (js/performance.now)
        n (count evs)]
    (p/let [^js h (call :head {:graph gid})
            seq-n (if h (.-seq h) 0)
            same-prefix? (and h (= (build-fold-of gid) (.-fold h)) (<= seq-n n)
                              (or (zero? seq-n) (= (.-lastId h) (:ev/id (nth evs (dec seq-n))))))
            mode (cond (and same-prefix? (= seq-n n)) :current
                       (and same-prefix? (appended! gid evs seq-n)) :append
                       same-prefix? (p/do (appended-by-fold! gid evs seq-n) :append)
                       :else :rebuild)
            ;; a rebuild right after boot waits for the page to settle: the first
            ;; seconds are when a person starts working (a single huge event
            ;; cannot be sliced); meanwhile search uses the in-memory graph
            _ (when (= :rebuild mode)
                (p/do (p/delay (max 0 (- 5000 (- (js/performance.now) started-at))))
                      (call :clear {:graph gid}) (rebuild! gid evs)))]
      (swap! synced assoc gid {:evs evs})
      ;; «nothing to do» does not erase what the last sync of this graph did (a boot from rows)
      (when-not (and (= :current mode) (= gid (:graph @last-sync)))
        (reset! last-sync {:graph gid :mode mode :events (- n (if (= :rebuild mode) 0 seq-n))
                           :ms (js/Math.round (- (js/performance.now) t0))}))
      mode)))

(declare embed-pending!)

(defn- schedule! [gid evs]
  (when @ready
    (swap! chain (fn [c] (-> c (p/then (fn [_] (p/let [m (sync-graph! gid evs)] (embed-pending! gid) m)))
                             (p/catch (fn [e] (js/console.warn "graph store:" (ex-message e)))))))))

(defn current?
  "Do the rows hold graph `gid` exactly as its log `evs` stands?"
  [gid evs]
  (and @ready (identical? evs (:evs (get @synced gid)))))

;; --- reads ---------------------------------------------------------------------------

(defn- hits [rows]
  (vec (for [[id class title short parent score] (js->clj rows)]
         {:id id :class class :title title :short short :parent parent :score score})))

(defn search
  "The store's search (the Worker's; no network): {:text :vec :model :mode
   :limit} → [{:id :class :title :short :parent :score}] best first. The
   caller brings the query's vector — see `search!`."
  [gid {:keys [text model mode limit] qv :vec}]
  (p/let [rows (call :search {:graph gid :text text :vec (some-> qv clj->js) :model model
                              :mode (name (or mode :hybrid)) :limit (or limit 20)})]
    (hits rows)))

(defonce ^:private query-vecs (atom {}))   ; [model text] → vector — a query asked again costs nothing

(defn- query-vec
  "The query's vector from the embedding provider (the user's key, inside the
   daily budget), or nil — no embeddings configured, or refused or failed:
   the search then goes by words."
  [model text]
  (cond
    (not (llm/embeddings?)) (p/resolved nil)
    (contains? @query-vecs [model text]) (p/resolved (get @query-vecs [model text]))
    :else (-> (p/let [[v] (llm/embed! [text])]
                ;; ponytail: forget all past 256 queries — an LRU when it matters
                (swap! query-vecs #(assoc (if (> (count %) 256) {} %) [model text] v))
                v)
              (p/catch (fn [_] nil)))))

(defn search!
  "Search graph `gid` in `mode` (:hybrid by default, :semantic, :verbatim):
   the query's vector is made HERE, by the embedding provider, then the
   store's `search` takes it (wiki/Graph-Store.md — the store calls nothing)."
  [gid text limit & [mode]]
  (let [mode (keyword (or mode :hybrid))
        model (:embed-model (llm/config))]
    (p/let [v (if (= :verbatim mode) nil (query-vec model text))]
      (search gid {:text text :vec v :model model :mode mode :limit limit}))))

;; --- the embedder: vectors for what has none (wiki/Graph-Store.md § Embeddings) ---
;; Pulls the texts with no vector for the configured model, embeds them in
;; batches through the provider (the user's key, inside the daily budget —
;; refused means paused, tried again after the next sync), stores the vectors.
(defonce ^:private embedding (atom nil))   ; the running round, a promise

(defn- embed-pending!
  "A round of embedding for graph `gid` — the running one if there is one.
   A promise, settled when nothing is left (or the provider said no)."
  [gid]
  (cond
    (not (and @ready (llm/embeddings?))) (p/resolved nil)
    @embedding @embedding
    :else
    (let [model (:embed-model (llm/config))
          run (-> (p/loop []
                    (p/let [rows (call :pending {:graph gid :model model :limit 50})]
                      (when (pos? (.-length rows))
                        (p/let [rs (js->clj rows)
                                vecs (llm/embed! (mapv second rs))
                                _ (call :putVectors {:model model :vecs (clj->js (map vector (map first rs) vecs))})]
                          (p/recur)))))
                  (p/catch (fn [e] (js/console.info "graph store: embedding paused:" (ex-message e))))
                  (p/finally #(reset! embedding nil)))]
      (reset! embedding run)
      run)))

(defn- store-vectors
  "For the intake (vectors/from-store): embed what this graph's topics lack,
   then {topic id → vector} from the store — each text embedded once."
  [kg model]
  (let [gid @store/current
        ids-by-hash (group-by #(gs/text-hash (gs/node-text (val %))) (:concepts kg))]
    (p/let [_ (embed-pending! gid)
            rows (call :vectorsFor {:model model :hashes (clj->js (keys ids-by-hash))})]
      (into {} (for [[h v] (js->clj rows), [id _] (get ids-by-hash h)] [id v])))))

(defn structure [gid] (p/let [s (call :structure {:graph gid})] (js->clj (js/JSON.parse s))))

(defn equivalence
  "The gate (wiki/Graph-Store.md): the rows, read back as a kg, against the
   fold of the log — {:equal bool :differs [kg keys]}."
  [gid]
  (p/let [^js d (call :dump {:graph gid})]
    (let [rows {:nodes (js->clj (.-nodes d)) :edges (js->clj (.-edges d))
                :discarded (js->clj (.-discarded d))
                :heads (some-> (.-heads d) cljs.reader/read-string)}
          norm (fn [kg] (into {} (remove (fn [[k v]] (or (nil? v) (#{::fold/eidx ::fold/nbr} k)))) kg))
          a (norm (gs/rows->kg rows))
          b (norm (fold/kg (store/graph-events gid)))]
      {:equal (= a b)
       :differs (vec (filter #(not= (get a %) (get b %)) (set (concat (keys a) (keys b)))))})))

;; --- one owner per browser --------------------------------------------------------------

(defn- boot-kg!
  "Build the open graph from the rows when they hold its log exactly — the
   first render then skips the refold (Obsidian's way: boot from the cache)."
  [gid evs]
  (p/let [^js h (call :head {:graph gid})]
    (when (and h (= (build-fold-of gid) (.-fold h)) (= (.-seq h) (count evs)) (pos? (count evs))
               (= (.-lastId h) (:ev/id (peek evs))))
      (p/let [s (call :dumpJson {:graph gid})]
        (when (store/seed-kg! evs (gs/kg-from-dump s))    ; false: the page folded it already
          (swap! synced assoc gid {:evs evs})
          (reset! last-sync {:graph gid :mode :booted :events 0 :ms 0}))))))

(defn- start-worker! [boot?]
  (let [w (js/Worker. (.-href (js/URL. "js/graphstore.js" (.-baseURI js/document))) #js {:type "module"})]
    (set! (.-onmessage w) on-message)
    (reset! worker w)
    (p/let [_ (call :open {})
            _ (when boot? (-> (boot-kg! @store/current @store/events)
                              (p/catch (fn [e] (js/console.warn "graph store: boot from rows failed:" (ex-message e))))))]
      (reset! ready true)
      (reset! vectors/from-store store-vectors)
      ;; hand the handles over as this page goes (a frozen page may keep them)
      (.addEventListener js/window "pagehide" (fn [_] (reset! ready false) (reset! vectors/from-store nil) (some-> @worker (.postMessage #js {:op "pause" :id -1}))))
      (schedule! @store/current @store/events))))

(defn start!
  "The tab that gets the store's lock opens the Worker and keeps it until it
   goes. A promise, settled once boot may render: when the lock is free at
   once, after the graph is built from the rows (at most 4 s); otherwise at
   once — this tab folds as before and queues for the lock. Without Web Locks
   or OPFS: no projection, in-memory only."
  []
  (if-not (and (exists? js/navigator.locks) (exists? js/navigator.storage) (exists? js/Worker))
    (p/resolved nil)
    (do
      (add-watch store/events ::follow (fn [_ _ old new] (when-not (identical? old new) (schedule! @store/current new))))
      (p/race
       [(p/delay 4000 nil)
        (p/create
         (fn [booted _]
           ;; a reload finds the lock still held by the page it replaces for a
           ;; moment: wait up to 0.5 s (a second tab must not wait), then go on without it
           (-> (.request js/navigator.locks lock-name #js {:signal (js/AbortSignal.timeout 500)}
                         (fn [_]
                           (-> (start-worker! true)
                               (p/catch (fn [e] (js/console.warn "graph store unavailable:" (ex-message e))))
                               (p/finally (fn [] (booted nil))))
                           (js/Promise. (fn [_ _]))))           ; held for the page's life
               (.catch (fn [_]
                         (booted nil)
                         ;; another tab holds it: wait, and take over when it goes
                         (.request js/navigator.locks lock-name
                                   (fn [_] (-> (start-worker! false) (p/catch (fn [_] nil))) (js/Promise. (fn [_ _])))))))))]))))

(defn expose!
  "What the checks drive it by."
  []
  (set! (.-loretileGraphStore js/window)
        #js {:ready (fn [] @ready)
             :lastSync (fn [] (clj->js @last-sync))
             :current (fn [] (current? @store/current @store/events))
             :equivalence (fn [gid] (p/let [r (equivalence (or gid @store/current))] (clj->js r)))
             ;; the graph the app renders (built from rows at boot, or folded) = the fold of its log
             :appGraphIsFold (fn [] (let [norm (fn [kg] (into {} (remove (fn [[k v]] (or (nil? v) (#{::fold/eidx ::fold/nbr} k)))) kg))]
                                      (= (norm (store/kg)) (norm (fold/kg @store/events)))))
             :search (fn [text mode] (p/let [r (search! @store/current text 20 mode)] (clj->js r)))
             :structure (fn [] (p/let [s (call :structure {:graph @store/current})] s))}))
