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
            [loretile.store :as store]))

(def ^:private build-fold (fold-hash))
(def ^:private lock-name "loretile-graph-store")

(defonce ^:private worker (atom nil))
(defonce ^:private calls (atom {}))      ; request id → [resolve reject]
(defonce ^:private next-id (atom 0))
(defonce ready (atom false))              ; this tab holds the store and it is open
(defonce ^:private synced (atom {}))      ; graph id → {:evs log :kg kg}: what the rows hold
(defonce ^:private chain (atom (p/resolved nil)))   ; syncs run one after another
(defonce last-sync (atom nil))           ; {:graph :mode :events :ms} — what the checks read

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

(defn- project!
  "Fold `evs` from index `from` (the rows already hold the graph `k` of the
   events before it) into row changes, written in batches. Resolves to the
   graph after the last event."
  [gid k evs from]
  (let [batch 2000]
    (p/loop [k k i from]
      (if (>= i (count evs))
        k
        (let [end (min (count evs) (+ i batch))
              [k' out] (reduce (fn [[k out] j]
                                 (let [e (nth evs j)
                                       d (fold/delta (fold/map-reader k) e)
                                       ch (gs/changes d (select-keys k gs/head-keys) (inc j))]
                                   [(fold/apply-delta k d) (doto out (.push (js-changes ch e)))]))
                               [k #js []] (range i end))]
          (p/do (call :apply {:graph gid :changes out :fold build-fold})
                (p/recur k' end)))))))

(defn- sync-graph!
  "Bring graph `gid`'s rows to log `evs`: nothing when they hold it, the new
   events' deltas when the log only grew, a rebuild otherwise."
  [gid evs]
  (let [t0 (js/performance.now)
        n (count evs)]
    (p/let [^js h (call :head {:graph gid})
            seq-n (if h (.-seq h) 0)
            prev (get @synced gid)
            same-prefix? (and h (= build-fold (.-fold h)) (<= seq-n n)
                              (or (zero? seq-n) (= (.-lastId h) (:ev/id (nth evs (dec seq-n))))))
            mode (cond (and same-prefix? (= seq-n n)) :current
                       same-prefix? :append
                       :else :rebuild)
            _ (when (= :rebuild mode) (call :clear {:graph gid}))
            ;; the graph the rows hold: folded already, or folded now — not at
            ;; boot when nothing changed (it is needed only for the next append)
            held (when (and prev (:kg prev) (= seq-n (count (:evs prev)))) (:kg prev))
            k (case mode
                :current held
                :append (project! gid (or held (fold/kg (subvec evs 0 seq-n))) evs seq-n)
                :rebuild (project! gid fold/empty-kg evs 0))]
      (swap! synced assoc gid {:evs evs :kg (or k (when (= evs @store/events) (when-not @store/as-of (store/kg))))})
      (reset! last-sync {:graph gid :mode mode :events (- n (if (= :rebuild mode) 0 seq-n))
                         :ms (js/Math.round (- (js/performance.now) t0))})
      mode)))

(defn- schedule! [gid evs]
  (when @ready
    (swap! chain (fn [c] (-> c (p/then (fn [_] (sync-graph! gid evs)))
                             (p/catch (fn [e] (js/console.warn "graph store:" (ex-message e)))))))))

(defn current?
  "Do the rows hold graph `gid` exactly as its log `evs` stands?"
  [gid evs]
  (and @ready (identical? evs (:evs (get @synced gid)))))

;; --- reads ---------------------------------------------------------------------------

(defn search
  "FTS over graph `gid`: [{:id :class :title :short :parent :score}] best first."
  [gid text limit]
  (p/let [rows (call :search {:graph gid :text text :limit limit})]
    (vec (for [[id class title short parent score] (js->clj rows)]
           {:id id :class class :title title :short short :parent parent :score score}))))

(defn structure [gid] (p/let [s (call :structure {:graph gid})] (js->clj (js/JSON.parse s))))

(defn equivalence
  "The gate (wiki/Graph-Store.md): the rows, read back as a kg, against the
   fold of the log — {:equal bool :differs [kg keys]}."
  [gid]
  (p/let [^js d (call :dump {:graph gid})]
    (let [rows {:nodes (js->clj (.-nodes d)) :edges (js->clj (.-edges d))
                :discarded (js->clj (.-discarded d))
                :heads (some-> (.-heads d) cljs.reader/read-string)}
          norm (fn [kg] (into {} (remove (fn [[k v]] (or (nil? v) (= k ::fold/eidx)))) kg))
          a (norm (gs/rows->kg rows))
          b (norm (fold/kg (store/graph-events gid)))]
      {:equal (= a b)
       :differs (vec (filter #(not= (get a %) (get b %)) (set (concat (keys a) (keys b)))))})))

;; --- one owner per browser --------------------------------------------------------------

(defn- start-worker! []
  (let [w (js/Worker. (.-href (js/URL. "js/graphstore.js" (.-baseURI js/document))) #js {:type "module"})]
    (set! (.-onmessage w) on-message)
    (reset! worker w)
    (p/let [_ (call :open {})]
      (reset! ready true)
      ;; hand the handles over as this page goes (a frozen page may keep them)
      (.addEventListener js/window "pagehide" (fn [_] (reset! ready false) (some-> @worker (.postMessage #js {:op "pause" :id -1}))))
      (schedule! @store/current @store/events))))

(defn start!
  "Queue for the store's lock; the tab that gets it opens the Worker and keeps
   it until it goes. Without Web Locks or OPFS: no projection, in-memory only."
  []
  (when (and (exists? js/navigator.locks) (exists? js/navigator.storage) (exists? js/Worker))
    (add-watch store/events ::follow (fn [_ _ old new] (when-not (identical? old new) (schedule! @store/current new))))
    (.request js/navigator.locks lock-name
              (fn [_]
                (-> (start-worker!) (p/catch (fn [e] (js/console.warn "graph store unavailable:" (ex-message e)))))
                (js/Promise. (fn [_ _])))))   ; held for the page's life
  ;; the checks drive it from outside
  (set! (.-loretileGraphStore js/window)
        #js {:ready (fn [] @ready)
             :lastSync (fn [] (clj->js @last-sync))
             :current (fn [] (current? @store/current @store/events))
             :equivalence (fn [gid] (p/let [r (equivalence (or gid @store/current))] (clj->js r)))
             :search (fn [text] (p/let [r (search @store/current text 20)] (clj->js r)))
             :structure (fn [] (p/let [s (call :structure {:graph @store/current})] s))}))
