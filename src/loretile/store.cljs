(ns loretile.store
  "Client state: the event vector is the truth, the KG is a reaction over
   its fold. Every graph's log lives in memory and is saved to IndexedDB
   (wiki/Graph-Sync.md B2); the registry and small settings stay in
   localStorage; export/import moves log.edn in and out of the git repo
   (wiki/Authoring.md file layout)."
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            [reagent.core :as r]
            [loretile.agent-md :as export]
            [loretile.fold :as fold]
            [loretile.graph-store :as gs]
            [loretile.ops :as ops]
            [loretile.profile :as profile]
            [loretile.rdf :as rdf]
            [loretile.schema :as schema]
            [loretile.turtle :as turtle]
            [loretile.i18n :as i18n]
            [goog.object :as gobj]))

;; SEVERAL GRAPHS (wiki/Continuous-Learning.md §9): `events` is the graph
;; you have open, `shelf` every other graph's log — all in memory, read once
;; at boot. Each log is saved WHOLE to IndexedDB under its graph id
;; (2026-11-20, wiki/Graph-Sync.md B2: localStorage's ~5 MB filled up). The
;; old localStorage logs ("loretile-kg-log" = the open graph's working copy,
;; "loretile-kg-log:{id}" = shelved ones) move over at boot — every boot, so
;; a log put there (a check's seed) still opens.
(def ^:private store-key "loretile-kg-log")
(def ^:private graphs-key "loretile-graphs")     ; [{:id :title}]
(def ^:private current-key "loretile-graph")     ; id of the open graph

(defonce events (r/atom []))
(defonce ^:private shelf (atom {}))   ; graph id -> log, every graph but the open one
(defonce graphs (r/atom []))
(defonce current (r/atom "demo"))
(defonce last-error (r/atom nil))
;; called with [graph-id event] after every event stored HERE (an edit, an
;; undo, a copy into another graph) — loretile.sync notes it as pending
(defonce on-append (atom nil))
(declare import-edn! read-log sync-bound! load-registry!)

;; the fold is pure, so it is cached on the identity of the event vector:
;; renders call (kg) many times a frame, and every call re-folded the whole
;; log — at ~1000 events (business dogfood 2026-10-15) one accept took
;; ~3.5 s. The deref stays, so a render still re-runs when events change.
;; AS-OF VIEW (2026-10-31): {:valid ms :recorded ms} or nil. While set, EVERY
;; reader of `kg` sees the graph as it stood then (fold/events-as-of) and
;; `append!` refuses — the past is for looking at. Never persisted: a reload
;; is back at HEAD.
(defonce as-of (r/atom nil))

(defonce ^:private kg-cache (atom [nil nil nil]))
(defn- grew?
  "Is `new` the log `old` with events appended — the same events, by
   identity, in front? (A persistent vector conj keeps them.)"
  [old new]
  (and old (< (count old) (count new))
       (let [n (count old)] (and (pos? n) (identical? (nth old (dec n)) (nth new (dec n)))
                                 (identical? (nth old 0) (nth new 0))))))

;; the deltas of the events the incremental fold just applied, by event — the
;; browser's projection (graph_local) writes them as rows instead of folding
;; a second copy of the graph: {:graph id :items [[event delta heads-before] …]}
(defonce recent-deltas (atom nil))
(defonce ^:private befores (atom nil))   ; {:graph id :by [[event kg-before] …]} — the last 50 appends

(defn kg []
  (let [evs @events at @as-of [k a v] @kg-cache]
    (cond
      (and (identical? k evs) (= a at)) v
      ;; an append (wiki/Graph-Store.md): fold only the new events onto the
      ;; graph already folded — a whole refold per edit took seconds at 40k
      (and (nil? at) (nil? a) (grew? k evs))
      (let [[v items] (reduce (fn [[g items] e]
                                (let [d (fold/delta (fold/map-reader g) e)]
                                  ;; [event delta heads-before graph-before]
                                  [(fold/apply-delta g d) (conj items [e d (select-keys g [:graph :profile :ontology :ontology-graph? :authors :owner]) g])]))
                              [v []] (subvec evs (count k)))]
        (reset! recent-deltas {:graph @current :items (mapv #(subvec % 0 3) items)})
        ;; the graph just before each of the last 50 edits — what undo reads
        ;; (refolding the log up to the event took seconds at 40k)
        (swap! befores (fn [m] {:graph @current
                                :by (vec (take-last 50 (into (if (= (:graph m) @current) (:by m) [])
                                                             (map (fn [[e _ _ g]] [e g]) items))))}))
        (reset! kg-cache [evs at v])
        v)
      :else
      (let [v (fold/kg (fold/events-as-of evs at))] (reset! kg-cache [evs at v]) v))))

(defn locale-for-new
  "The locale new content in the open graph is stamped with: the graph's own
   content language, else the interface language (an empty graph)."
  []
  (or (fold/content-locale (kg)) (name @i18n/locale)))

(defn- read-ls [k default]
  (try (or (some-> (.getItem js/localStorage k) reader/read-string) default)
       (catch :default _ default)))

;; Every write REPORTS. Swallowing a failed setItem is how a full quota
;; used to destroy a graph in silence: the shelf write failed, the working
;; copy was then replaced by the graph being switched to, and the drawer
;; still listed a graph whose log no longer existed anywhere.
(defn write!
  "true on success; false and a visible error when storage refuses."
  [k v]
  (try (js/localStorage.setItem k v) true
       (catch :default _
         (reset! last-error {:error :storage-full :detail k})
         false)))

;; --- the logs in IndexedDB ------------------------------------------------------

(defonce ^:private db (atom nil))
;; other tabs of this origin hear which graph's log was saved and re-read it
(defonce ^:private tabs (when (exists? js/BroadcastChannel) (js/BroadcastChannel. "loretile-logs")))

(defn- idb-open []
  (js/Promise.
   (fn [ok no]
     ;; version 2: an "aside" store for logs in the old event format; version 3
     ;; (wiki/Graph-Store.md): "events", ONE RECORD PER EVENT keyed [graph seq] —
     ;; an edit writes its event, not the whole log (tens of MB at 40k topics).
     ;; "logs" (a whole log per graph) is read once more and moved over
     (let [r (.open js/indexedDB "loretile" 3)]
       (set! (.-onupgradeneeded r)
             (fn [] (let [d (.-result r)]
                      (doseq [st ["logs" "aside" "events"]]
                        (when-not (.contains (.-objectStoreNames d) st) (.createObjectStore d st))))))
       (set! (.-onsuccess r) #(ok (.-result r)))
       (set! (.-onerror r) #(no (.-error r)))))))

(defn- graph-range [gid] (js/IDBKeyRange.bound #js [gid 0] #js [gid js/Infinity]))

;; what "events" holds per graph: [count last-id] — a save of a log that only
;; grew writes the new events; any other log is rewritten whole
(defonce ^:private saved (atom {}))
(declare read-event)

(defn- idb-read
  "Promise of {graph id → log}: every log, or just `gid`'s — the per-event
   records, plus any whole log still in the old \"logs\" store (`:legacy`
   lists those graph ids, to move over)."
  ([] (idb-read nil))
  ([gid]
   (js/Promise.
    (fn [ok no]
      (let [tx (.transaction @db #js ["events" "logs"] "readonly")
            ev (.objectStore tx "events")
            lg (.objectStore tx "logs")
            eks (if gid (.getAllKeys ev (graph-range gid)) (.getAllKeys ev))
            evs (if gid (.getAll ev (graph-range gid)) (.getAll ev))
            lks (if gid (.getAllKeys lg gid) (.getAllKeys lg))
            lvs (if gid (.getAll lg gid) (.getAll lg))]
        (set! (.-onerror tx) #(no (.-error tx)))
        (set! (.-oncomplete tx)
              (fn []
                (let [per (reduce (fn [m [k v]]
                                    (let [g (aget k 0)]
                                      (update m g (fnil conj []) (try (read-event v) (catch :default _ nil)))))
                                  {} (map vector (.-result eks) (.-result evs)))
                      per (into {} (map (fn [[g es]] [g (vec (remove nil? es))])) per)
                      legacy (into {} (keep (fn [[k v]] (when-not (contains? per k)
                                                          [k (try (reader/read-string v) (catch :default _ []))])))
                                   (map vector (.-result lks) (.-result lvs)))]
                  (doseq [[g es] per] (swap! saved assoc g [(count es) (:ev/id (peek es))]))
                  ;; graphs still holding EDN records: rewritten as JSON once, after boot
                  (ok (with-meta (merge per legacy)
                        {:legacy (set (keys legacy))
                         :edn (set (keep (fn [[k v]] (when-not (str/starts-with? v "{\"") (aget k 0)))
                                         (map vector (.-result eks) (.-result evs))))}))))))))))

(defn- read-event
  "One stored event: tagged JSON (since 2026-11-24 — parsed natively, ~2.5×
   faster than EDN at boot for a 40k-topic log), or EDN written before."
  [s]
  (if (str/starts-with? s "{\"") (gs/<-json s) (reader/read-string s)))

(defn- write-log!
  "Into transaction `tx`: graph `gid`'s log `evs` — only the events past what
   is saved when the log grew, all of it otherwise."
  [^js tx gid evs]
  (let [st (.objectStore tx "events")
        [n last-id] (get @saved gid)
        grew? (and n (<= n (count evs)) (or (zero? n) (= last-id (:ev/id (nth evs (dec n))))))
        from (if grew? n 0)]
    (when-not grew? (.delete st (graph-range gid)))
    (doseq [i (range from (count evs))]
      (.put st (gs/->json (nth evs i)) #js [gid i]))
    (.delete (.objectStore tx "logs") gid)
    (swap! saved assoc gid [(count evs) (some-> (peek evs) :ev/id)])))

(defn- save-log!
  "Save graph `gid`'s log (its new events, when it only grew). false when
   storage refuses at once (no database, a full disk reported
   synchronously) — the edit is then refused; a refusal that arrives later
   raises the banner, and the log stays in memory, so the next save writes
   it whole again."
  [gid evs]
  ;; a refused or aborted transaction wrote NOTHING: what was saved before
  ;; still is — forgetting it would force a whole rewrite at the next save
  (let [prev (get @saved gid)
        fail! #(do (swap! saved (fn [m] (if prev (assoc m gid prev) (dissoc m gid))))
                   (reset! last-error {:error :storage-full :detail gid}))]
    (try
      (let [tx (.transaction @db #js ["events" "logs"] "readwrite")]
        (write-log! tx gid evs)
        (set! (.-oncomplete tx) #(some-> tabs (.postMessage gid)))
        (set! (.-onabort tx) fail!)
        true)
      (catch :default _ (fail!) false))))

(defn- idb-put-all
  "Promise, resolved once every log of {gid → log} is stored (one transaction)."
  [logs]
  (js/Promise.
   (fn [ok no]
     (let [tx (.transaction @db #js ["events" "logs"] "readwrite")]
       (doseq [[gid evs] logs] (swap! saved dissoc gid) (write-log! tx gid evs))
       (set! (.-oncomplete tx) ok)
       (set! (.-onabort tx) #(no (.-error tx)))))))

(defn- old-format?
  "A log written before every event was a `change` (the learning slice,
   2026-11-20) — not read any more (Denis: no backward compatibility)."
  [evs]
  (boolean (some #(not= :change (:ev/type %)) evs)))

(defn- set-aside!
  "Old-format logs leave the graph list: kept whole in the \"aside\" store
   (an insurance, never read), removed from the logs."
  [logs]
  (try
    (let [tx (.transaction @db #js ["aside" "logs" "events"] "readwrite")]
      (doseq [[gid evs] logs]
        (.put (.objectStore tx "aside") (pr-str evs) gid)
        (.delete (.objectStore tx "logs") gid)
        (.delete (.objectStore tx "events") (graph-range gid))
        (swap! saved dissoc gid)))
    (catch :default _ nil)))

(defn- drop-log! [gid]
  (swap! saved dissoc gid)
  (try (let [tx (.transaction @db #js ["logs" "events"] "readwrite")]
         (.delete (.objectStore tx "logs") gid)
         (.delete (.objectStore tx "events") (graph-range gid)))
       (catch :default _ nil)))

(defn- persist! [] (save-log! @current @events))

(defn- persist-registry! []
  (and (write! graphs-key (pr-str @graphs))
       (write! current-key (pr-str @current))))

(defn- shelve! [id evs]
  (swap! shelf assoc id evs)
  (save-log! id evs))

(defn graph-events
  "The log of any graph, open or shelved."
  [id]
  (if (= id @current) @events (get @shelf id [])))

;; the checks read logs here (they used to read localStorage)
(gobj/set js/window "loretileLog" (fn [id] (pr-str (graph-events (or id @current)))))
(gobj/set js/window "loretileLogs" (fn [] (str/join "\n" (map pr-str (cons @events (vals @shelf))))))

(defn graph-title [id]
  (or (:title (first (filter #(= id (:id %)) @graphs))) id))

(defn- ls-logs
  "The logs still in localStorage: {graph id → log}, the working copy under
   the open graph's id, and the localStorage keys they came from."
  [cur]
  (let [ks (for [i (range (.-length js/localStorage))
                 :let [k (.key js/localStorage i)]
                 :when (or (= k store-key) (= k "tessella-kg-log") (str/starts-with? k (str store-key ":")))]
             k)]
    {:keys (vec ks)
     :logs (into {} (keep (fn [k]
                            (when-let [evs (try (reader/read-string (js/localStorage.getItem k)) (catch :default _ nil))]
                              [(if (str/starts-with? k (str store-key ":")) (subs k (inc (count store-key))) cur) evs])))
                 ;; the pre-rename key loses to the current one
                 (sort-by #(= % store-key) ks))}))

(defn load!
  "Read every log (IndexedDB, then whatever localStorage still holds — it
   wins, and moves over), then the registry. A promise; the app renders
   after it. When IndexedDB cannot open, the localStorage logs are read and
   KEPT, and every edit is refused with the storage banner — never lost."
  []
  (let [cur (read-ls current-key "demo")
        ;; `ls-keys`, never `keys`: a local named keys shadowed clojure.core/keys below
        {ls-keys :keys logs :logs} (ls-logs cur)]
    (-> (idb-open)
        (.then (fn [d] (reset! db d) (idb-read)))
        (.then (fn [stored]
                 ;; localStorage's copies go only once IndexedDB has them; a whole
                 ;; log still in the old "logs" store becomes per-event records
                 (-> (idb-put-all (merge (select-keys stored (:legacy (meta stored))) logs))
                     (.then #(doseq [k ls-keys] (js/localStorage.removeItem k)))
                     (.catch #(reset! last-error {:error :storage-full :detail "indexeddb"}))
                     (.then (constantly (merge stored logs))))))
        (.catch (fn [_] (reset! last-error {:error :storage-full :detail "indexeddb"}) logs))
        (.then (fn [all]
                 ;; logs stored as EDN (before 2026-11-24): rewritten as tagged JSON
                 ;; once the app is up — the next boot parses natively
                 (when-let [edn (seq (:edn (meta all)))]
                   (js/setTimeout #(-> (idb-put-all (select-keys all edn)) (.catch (fn [_] nil))) 3000))
                 (let [old (into {} (filter (comp old-format? val)) all)
                       all (apply dissoc all (keys old))]
                   (when (seq old)
                     (when @db (set-aside! old))
                     (reset! last-error {:error :old-format :detail (count old)}))
                   (reset! shelf (dissoc all cur))
                   (reset! events (vec (get all cur [])))
                   (load-registry! (set (keys old)))
                   ;; the registry may open ANOTHER graph (the saved one was set
                   ;; aside, or is unknown): its log becomes the working copy —
                   ;; keeping the old one would save it over that graph's log
                   (when (not= cur @current)
                     (reset! events (vec (get @shelf @current [])))
                     (swap! shelf dissoc @current))))))))

(defn- load-registry! [gone]
  ;; migration: a pre-multi-graph install has a working copy and no
  ;; registry — it becomes the first graph, keeping its id so the ev/ns
  ;; already stamped on its events still matches
  (let [reg (some->> (read-ls graphs-key nil) (remove #(gone (:id %))) vec)
        gs (or (not-empty reg) [{:id "demo" :title (i18n/t :ui/graph-first)}])
        cur (read-ls current-key "demo")]
    ;; a `current` outside the registry made the open graph invisible to the
    ;; catalog and unreachable from the drawer. Its log is the working copy:
    ;; register it back — relabeling it as the first graph meant the next
    ;; switch shelved it over that graph's own log (review 2026-10-16 A15)
    (let [known? (some #(= cur (:id %)) gs)
          orphan? (and (not known?) (string? cur) (seq cur) (seq @events))
          gs (cond-> gs orphan? (conj {:id cur :title (i18n/t :ui/graph-recovered)}))]
      (reset! graphs gs)
      (reset! current (if (or known? orphan?) cur (:id (first gs))))
      (when (or orphan? (empty? reg) (seq gone)) (persist-registry!)))))

(defn watch-other-tabs!
  "Two tabs share one origin and one storage. Without this they overwrite
   each other last-writer-wins: one tab's registry drops the other's new
   graph, and a shelved log becomes unreachable. Re-read rather than fight."
  []
  (.addEventListener
   js/window "storage"
   (fn [^js e]
     (let [k (.-key e)]
       (cond
         (= k graphs-key) (reset! graphs (read-ls graphs-key @graphs))
         ;; the open graph changed in another tab: adopt it rather than
         ;; keep writing over it from here
         (= k current-key)
         (let [other (read-ls current-key @current)]
           (when (not= other @current)
             (swap! shelf assoc @current @events)
             (reset! current other)
             (reset! events (get @shelf other []))
             (reset! last-error {:error :graph-switched-elsewhere :detail other})))
         :else nil))))
  ;; a log another tab saved: read it again
  (some-> tabs (.addEventListener "message"
                                  (fn [^js e]
                                    (let [gid (.-data e)]
                                      (.then (idb-read gid)
                                             (fn [m] (when-let [evs (get m gid)]
                                                       (if (= gid @current)
                                                         (when (not= evs @events) (reset! events evs))
                                                         (swap! shelf assoc gid evs))))))))))

(defn- open!
  "Make graph `id` with log `evs` the open one, the registry becoming `gs`:
   the working copy first, then the registry. A refused write puts the
   previous graph back — the working copy and `current` must never name
   different graphs, or the next switch shelves one graph's log over
   another's only copy (review 2026-10-16 A15). The previous graph is
   already on its shelf when this runs. true when opened."
  [id evs gs]
  (let [old-id @current old-evs @events old-gs @graphs]
    (reset! current id) (reset! events evs) (reset! graphs gs)
    (or (and (persist!) (persist-registry!))
        (let [err @last-error]
          (reset! current old-id) (reset! events old-evs) (reset! graphs old-gs)
          (persist!) (persist-registry!)
          (reset! last-error err)
          false))))

(defn switch-graph!
  "Shelve what is open, then open another. ABORTS if the shelf write fails:
   losing the open graph is never an acceptable cost of navigating."
  [id]
  (and (not= id @current) (shelve! @current @events)
       (open! id (get @shelf id []) @graphs)))

(declare append!)

(defn new-graph!
  "Shelve what is open, start an empty graph. Returns its id, or nil when
   the open graph could not be shelved. `personal?` marks a learner's own
   graph (built from their material): what they accept there is `generated`
   (ruling 2026-10-02) and it stays private (decision #1)."
  [title & [{:keys [personal?]}]]
  (let [id (str "g-" (.toString (js/Date.now) 36))]
    (when (and (shelve! @current @events)
               (open! id [] (conj @graphs (cond-> {:id id :title title} personal? (assoc :personal? true)))))
      ;; the title is a graph property too, so it travels with the log (a
      ;; server lists graphs by it — wiki/Graph-Sync.md)
      (append! {:ev/type :change :ops [[:graph-set {:title title}]]})
      id)))

(defn profile
  "The open graph's ontology (the learning profile unless its log set one)."
  []
  (fold/profile (kg)))

(defn- turtle-file?
  "A .ttl name, or text that opens like Turtle (@prefix, PREFIX, @base, <iri>)."
  [text fname]
  (or (str/ends-with? (str/lower-case (str fname)) ".ttl")
      (boolean (re-find #"^\s*(#[^\n]*\n\s*)*(@prefix|@base|PREFIX|BASE|<)" (str text)))))

(defn read-profile
  "An author's ontology file → {:profile p :report r?} or {:error e}: EDN
   in Loretile's own shape, or Turtle from an ontology editor (Protégé,
   TopBraid, VocBench — OWL/RDFS classes and object properties,
   `loretile.turtle`; 2026-10-23). Validated here so the drawer can refuse
   before a graph is created for it."
  ([text] (read-profile text nil))
  ([text fname]
   (if (turtle-file? text fname)
     (try
       (let [{:keys [profile report error]} (turtle/profile (turtle/parse text) {:lang (name @i18n/locale)})]
         (cond
           error {:error {:error :bad-ontology :detail error}}
           (profile/problems profile) {:error {:error :bad-profile :detail (profile/problems profile)}}
           :else {:profile profile :report report}))
       (catch :default e {:error {:error :bad-ontology :detail (ex-message e)}}))
     (try
       (let [p (reader/read-string text)]
         (if-let [why (profile/problems p)]
           {:error {:error :bad-profile :detail why}}
           {:profile p}))
       (catch :default e {:error {:error :bad-edn :detail (str e)}})))))

(defn graph-personal?
  "Is graph `id` a learner's own (built from their material)?"
  [id]
  (boolean (:personal? (first (filter #(= id (:id %)) @graphs)))))

(defn personal?
  "Is the open graph a learner's own?"
  []
  (graph-personal? @current))


(defn forget-graph!
  "Drop a graph and its log. The open one cannot be dropped."
  [id]
  (when (not= id @current)
    (swap! graphs (fn [gs] (vec (remove #(= id (:id %)) gs))))
    (swap! shelf dissoc id)
    (drop-log! id)
    (persist-registry!)))

(defn graph-kgs
  "Every graph folded — what the catalog spans. The author edits one graph
   at a time; the learner browses across all of them."
  []
  (for [g @graphs] (assoc g :kg (fold/kg (graph-events (:id g))))))

(defn find-course
  "Which graph holds this course? Returns {:id :title :kg :course} or nil.
   The learner's route carries only the course id, so the graph is resolved
   rather than routed."
  [course-id]
  (first (for [g (graph-kgs)
               :let [c (get-in g [:kg :courses course-id])]
               :when c]
           (assoc g :course c))))

(defn append-to?
  "Is `graph-id` still the open graph? Long-running work (an LLM round trip)
   captures the graph it started in and checks before writing."
  [graph-id]
  (or (nil? graph-id) (= graph-id @current)))

;; WHO writes (2026-10-21: every event said «denis»). One stable id per
;; device, made once; the person's name is set in Settings and written into
;; each graph they change as `author-named`, so an exported graph still says
;; who added and changed what. Renaming writes a new `author-named`; the id
;; — what every event carries — never changes.
(def ^:private author-key "loretile-author")

(defonce author
  (r/atom (let [a (read-ls author-key nil)]
            (if (and (map? a) (seq (:id a)))
              a
              ;; written on the first change, not here: a page that only reads writes nothing
              {:id (str "a-" (subs (str (random-uuid)) 0 8)) :name ""}))))

(defn set-author-name!
  "The person's name for every change from this device. true when saved."
  [n]
  (let [a (assoc @author :name (str/trim (str n)))]
    (when (write! author-key (pr-str a))
      (reset! author a)
      true)))

(declare append!)

(defn- name-author!
  "Before a change, the graph learns the author's name when it does not
   know it yet (or knows an older one). Nothing when no name is set."
  []
  (let [{:keys [id name]} @author]
    (when (and (seq name) (not= name (get-in (kg) [:authors id])))
      (append! (ops/name-author id name)))))

(defn- naming?
  "Is `e` the change that names an author (it must not name one first)?"
  [e]
  (some (fn [[op a]] (and (= :graph-set op) (contains? a :authors))) (:ops e)))

;; undo / redo (2026-11-20): this session's own edits, per graph, newest
;; last. An undo is a NEW change (fold/undo-ops) — the log stays append-only.
(defonce ^:private undo-stack (atom {}))   ; graph id -> [ev-id …]
(defonce ^:private redo-stack (atom {}))   ; graph id -> [ev-id of an undo …]
(defonce ^:private replaying? (atom false))

(defn append!
  "Guard at the door, then append + persist. Returns nil on success,
   the guard error otherwise (the UI shows it)."
  [e]
  (if @as-of
    (do (reset! last-error {:error :past-view}) @last-error)
  (do
  (when-not (naming? e) (name-author!))
  ;; the device's id is kept from its first change on
  (when (nil? (read-ls author-key nil)) (write! author-key (pr-str @author)))
  (let [e (cond-> (merge {:ev/id (str (random-uuid)) :ev/ns @current
                          :ev/actor (:id @author) :ev/ts (js/Date.now)} e)
            ;; an event that brought its own clock (a merge file) is RECORDED
            ;; now — the second clock of the bitemporal cut (fold/events-as-of)
            (and (:ev/ts e) (not (:ev/recorded e))) (assoc :ev/recorded (js/Date.now)))]
    (if-let [err (fold/guard (kg) e)]
      (do (reset! last-error err) err)
      (do (swap! events conj e)
          ;; a refused write is NOT a save: the event leaves memory again and
          ;; the storage error stays in the banner — clearing it here showed
          ;; «saved» for work that was gone on reload (review 2026-10-16 A1)
          (if (persist!)
            (do (reset! last-error nil)
                (some-> @on-append (apply [@current e]))
                ;; an edit undo can reverse goes on this graph's stack; a fresh
                ;; edit forgets what was undone (redo only follows undo)
                (when (and (not @replaying?) (fold/undoable? e))
                  (swap! undo-stack update @current (fnil conj []) (:ev/id e))
                  (swap! redo-stack dissoc @current))
                ;; an ontology graph's change flows into the graphs bound to it
                (when (:ontology-graph? (kg)) (sync-bound! @current))
                nil)
            (do (swap! events pop) @last-error))))))))

;; --- the ontology as its own graph (wiki/Ontology-Graph.md) ------------------

(defonce sync-refused (r/atom {}))   ; data graph id -> guard error of the last copy it refused

(defn- stamp [gid e]
  (merge {:ev/id (str (random-uuid)) :ev/ns gid :ev/actor (:id @author) :ev/ts (js/Date.now)} e))

(defn- append-to-log!
  "Guard and append one event to ANY graph's log, open or shelved. nil on
   success, the guard error otherwise (or false when storage refused)."
  [gid e]
  (let [evs (graph-events gid)]
    (or (fold/guard (fold/kg evs) e)
        (let [evs (conj evs e)
              err (if (= gid @current)
                    (do (reset! events evs) (when-not (persist!) (swap! events pop) false))
                    (when-not (shelve! gid evs) false))]
          (when (nil? err) (some-> @on-append (apply [gid e])))
          err))))

(defn rename-graph!
  "The registry row and the graph's own `:title` property (it travels with
   the log, so a server and other browsers see the new name)."
  [id title]
  (swap! graphs (fn [gs] (mapv #(if (= id (:id %)) (assoc % :title title) %) gs)))
  (append-to-log! id (stamp id {:ev/type :change :ops [[:graph-set {:title title}]]}))
  (persist-registry!))

(defn replace-log!
  "Put a graph's whole log, open or shelved — what sync does when others'
   events are laid in. true when written."
  [gid evs]
  (if (= gid @current)
    (let [before @events]
      (reset! events (vec evs))
      (or (persist!) (do (reset! events before) false)))
    (shelve! gid (vec evs))))

(defn add-server-graph!
  "A graph kept by the server, brought onto this device: its log and a
   registry row, then opened. Its id, or nil when the open graph could not
   be shelved."
  [{:keys [id title ontology]} evs]
  (when (and (shelve! @current @events) (shelve! id (vec evs)))
    (reset! graphs (conj (vec (remove #(= id (:id %)) @graphs))
                         (cond-> {:id id :title (or title id)} ontology (assoc :ontology ontology))))
    (when (open! id (vec evs) @graphs) id)))

(defn ontology-graphs
  "The graphs on this device that describe a vocabulary (their ontology is
   the built-in types one), as registry rows."
  []
  (filter #(= fold/types-ontology (:ontology %)) @graphs))

(defn- copy-event
  "The event that gives a data graph the ontology graph `oid` as it stands:
   a graph property holding the vocabulary read out of it (wiki/Property-Graph.md)."
  [oid]
  (let [evs (graph-events oid)
        okg (fold/kg evs)
        p (assoc (fold/vocabulary okg) :id oid)
        ;; its competency questions travel too, to be checked over each graph's data
        cqs (vec (for [[id q] (sort-by key (:cqs okg))] (assoc (dissoc q :inherited?) :id id)))]
    (when (and (seq (:node-types p)) (not (profile/problems p)))
      (stamp nil {:ev/type :change
                  :ops [[:graph-set {:ontology oid :ontology-version (:ev/id (peek evs)) :ontology-copy p
                                     :ontology-cqs cqs}]]}))))

(defn ontology-latest
  "The ontology graph `oid` as a copy would carry it now: the vocabulary, or
   nil when it is not whole."
  [oid]
  (some-> (copy-event oid) :ops first second :ontology-copy))

(defn stuck
  "Is the open graph held on an old version of its ontology — because the
   newest would drop types or relations its data uses? {:breaks :latest}
   or nil. Computed live, so it survives a reload (the refusal note does not)."
  []
  (let [k (kg)]
    (when-let [oid (get-in k [:ontology :graph])]
      (when-let [latest (some-> (ontology-latest oid) profile/normalize)]
        (when-let [b (fold/sync-breaks k latest)] {:breaks b :latest latest})))))

(defn- relink-ops
  "The ops that move every edge of `k` whose relation is in `relations`
   {old new-or-:remove} to the new relation (its own properties kept), or
   remove it."
  [k relations]
  (mapcat (fn [{:keys [from to type] :as edge}]
            (when-let [n (get relations type)]
              (cond-> [[:edge-remove from type to]]
                (not= :remove n)
                (conj [:edge-set from n to (into (select-keys edge [:justification :inferred?])
                                                 (fold/edge-props edge))]))))
          (:edges k)))

(defn migrate!
  "Unstick the open graph (2026-11-20): it refused its ontology's newest
   version because the version drops types or relations its data uses.
   `plan` = {:types {old new} :relations {old new-or-:remove}}. ONE change:
   the new copy, the nodes of each dropped type retyped, the links of each
   dropped relation retyped (their own properties kept) or removed — the
   guard judges it whole. nil on success, else the guard error."
  [plan]
  (let [k (kg)
        oid (get-in k [:ontology :graph])
        e (copy-event oid)
        retype (for [[id c] (:concepts k) :let [n (get-in plan [:types (:kind c)])] :when n]
                 [:node-set id {:kind n}])
        relink (relink-ops k (:relations plan))]
    (if-not e
      {:error :no-whole-ontology}
      (let [err (append! {:ev/type :change :ops (vec (concat (:ops e) retype relink))})]
        (when (nil? err) (swap! sync-refused dissoc @current))
        err))))

(defn rename-relation!
  "In the open ontology graph: relation `old` gets the id written `text`
   (2026-11-20). ONE change — every edge of it moved to the new id, its
   words kept — and the rename recorded in the graph property
   `:relation-renames`, so a graph using the ontology moves its own links
   when it takes the new copy (`sync-bound!`). nil when done or nothing to
   do, else the error."
  [old text]
  (let [k (kg)
        new (keyword (str/replace (str/lower-case (str/trim (str text))) #"\s+" "-"))
        taken (set (map :type (:edges k)))]
    (cond
      (or (str/blank? (str text)) (= new old)) nil
      (taken new) (reset! last-error {:error :relation-exists :detail (name new)})
      :else (append! {:ev/type :change
                      :ops (vec (concat
                                 (for [{:keys [from to type] :as e} (:edges k) :when (= old type)
                                       op [[:edge-remove from old to]
                                           [:edge-set from new to (dissoc e :from :to :type :changed-by :changed-at)]]]
                                   op)
                                 [[:graph-set {:relation-renames (assoc (get-in k [:graph :relation-renames]) old new)}]]))}))))

(defn whole-ontology?
  "Can a data graph take the ontology graph `oid` as it stands (a type, the
   one ordering relation, the whole-profile rules)?"
  [oid]
  (some? (copy-event oid)))

(defn graph-on-device? [id] (some #(= id (:id %)) @graphs))

(defn add-to-ontology!
  "Write ops into the ontology graph `oid` — another graph of this device —
   and copy it to every graph bound to it (slice 4: a missing type joins the
   ontology from a data graph's intake). nil on success, else the error."
  [oid ops]
  ;; a vector: callers build ops with concat, and the schema refuses a seq
  (let [err (append-to-log! oid (stamp oid {:ev/type :change :ops (vec ops)}))]
    (when (nil? err) (sync-bound! oid))
    err))

(defn make-types-graph!
  "The open graph becomes one that describes types (its ontology property
   set to the built-in types one) — the one door's «this is an ontology» for
   an empty graph. nil on success, else the guard error."
  []
  (let [err (append! {:ev/type :change :ops [[:graph-set {:ontology fold/types-ontology}]]})]
    (when (nil? err)
      (reset! graphs (mapv #(cond-> % (= @current (:id %)) (assoc :ontology fold/types-ontology)) @graphs))
      (persist-registry!))
    err))

(defn bind!
  "Give graph `gid` the ontology graph `oid`: its copy goes into gid's log
   and the registry remembers the binding, so changes flow. nil on success,
   else the guard error (an incomplete ontology: `:no-whole-ontology`)."
  [gid oid]
  (if-let [e (copy-event oid)]
    (let [err (append-to-log! gid (assoc e :ev/ns gid))]
      (when (nil? err)
        (reset! graphs (mapv #(cond-> % (= gid (:id %)) (assoc :ontology oid)) @graphs))
        (persist-registry!))
      err)
    {:error :no-whole-ontology}))

(defn sync-bound!
  "After a change in the ontology graph `oid`: every graph bound to it on
   this device takes the new copy — or refuses it when it would break data,
   and stays on its version (`sync-refused` says why)."
  [oid]
  (when-let [e (copy-event oid)]
    (doseq [{:keys [id]} (filter #(= oid (:ontology %)) @graphs)
            :let [k (fold/kg (graph-events id))
                  v (get-in k [:ontology :version])]
            :when (not= v (get-in e [:ops 0 1 :ontology-version]))]
      ;; a RENAMED relation (2026-11-20): the graph's own links move to the new
      ;; id in the same change that takes the copy — never a stuck graph
      (let [renames (get-in (fold/kg (graph-events oid)) [:graph :relation-renames])
            now (set (map :id (get-in e [:ops 0 1 :ontology-copy :relations])))
            follow (fn [r] (loop [r r seen #{}] (if-let [n (and (not (seen r)) (get renames r))] (recur n (conj seen r)) r)))
            plan (into {} (for [{:keys [type]} (:edges k)
                                :let [n (follow type)]
                                :when (and (not= n type) (now n) (not (now type)))]
                            [type n]))
            e (update e :ops (fn [ops] (vec (concat ops (relink-ops k plan)))))
            err (append-to-log! id (assoc e :ev/ns id :ev/id (str (random-uuid))))]
        (swap! sync-refused (if (and err (:error err)) #(assoc % id err) #(dissoc % id)))))))

(defn- vocabulary-ops
  "A vocabulary written as an ontology graph's nodes and edges — what an
   old graph's own ontology becomes when it moves over. A relation open at
   an end (any type) has no edge to draw and is left out."
  [p]
  (concat
   (for [t (:node-types p)]
     [:node-set (name (:id t)) (cond-> {:title (:label t)}
                                 (seq (:description t)) (assoc :definition (:description t))
                                 (:color t) (assoc :color (:color t))
                                 (:icon t) (assoc :icon (:icon t))
                                 (:learnable? t) (assoc :learnable? true))])
   (for [r (:relations p) :when (not= :grounded-in (:id r)) f (:from r) to (:to r)]
     [:edge-set (name f) (:id r) (name to)
      (select-keys r [:label :inverse :description :ordering? :acyclic? :symmetric?])])))

(defn new-ontology-graph!
  "Shelve what is open, start an ontology graph (optionally from a
   vocabulary) and open it. An ontology graph is an ordinary graph whose
   graph property says so. Its id, or nil when the open graph could not be
   shelved."
  [title & [p]]
  (let [id (str "o-" (.toString (js/Date.now) 36))
        e (stamp id {:ev/type :change
                     :ops (into [[:graph-set {:ontology fold/types-ontology :title title}]] (vocabulary-ops p))})]
    (when (and (shelve! @current @events)
               (open! id [e] (conj @graphs {:id id :title title :ontology fold/types-ontology})))
      id)))

(defn- download! [text type fname]
  (let [a (js/document.createElement "a")]
    (set! (.-href a) (js/URL.createObjectURL (js/Blob. #js [text] #js {:type type})))
    (set! (.-download a) fname)
    (.click a)))

(defn- kg-of [id] (if (= id @current) (kg) (fold/kg (graph-events id))))

(defn- reverse-of!
  "Append the change that reverses the event `id` of the open graph; the id
   of that new change, or nil (nothing to reverse, or the guard said no)."
  [id]
  (let [evs @events
        i (first (keep-indexed #(when (= id (:ev/id %2)) %1) evs))]
    (when i
      (when-let [ops (fold/undo-ops (or (when (= (:graph @befores) @current)
                                          (some (fn [[e g]] (when (identical? e (nth evs i)) g)) (:by @befores)))
                                        (fold/kg (subvec evs 0 i)))
                                    (nth evs i))]
        (reset! replaying? true)
        (let [err (try (append! {:ev/type :change :ops ops}) (finally (reset! replaying? false)))]
          (when (nil? err) (:ev/id (peek @events))))))))

(defn can-undo? [] (seq (get @undo-stack @current)))
(defn can-redo? [] (seq (get @redo-stack @current)))

(defn undo!
  "Reverse this session's last edit of the open graph. true when done."
  []
  (when-let [id (peek (get @undo-stack @current))]
    (when-let [uid (reverse-of! id)]
      (swap! undo-stack update @current pop)
      (swap! redo-stack update @current (fnil conj []) uid)
      true)))

(defn redo!
  "Reverse the last undo. true when done."
  []
  (when-let [uid (peek (get @redo-stack @current))]
    (when-let [rid (reverse-of! uid)]
      (swap! redo-stack update @current pop)
      (swap! undo-stack update @current (fnil conj []) rid)
      true)))

(defn export-edn!
  "Download a graph's log as log.edn (the open one by default) — the author
   drops it into kg/{ns}/ in git."
  ([] (export-edn! @current))
  ([id] (download! (with-out-str (doseq [e (graph-events id)] (prn e))) "application/edn" "log.edn")))

(defn export-md!
  "Download a graph as ONE markdown file for a coding agent
   (wiki/Profiles.md): typed relations in both directions, sources, stale
   flags. Dropped into a repo's docs/ and pointed at from CLAUDE.md."
  ([] (export-md! @current))
  ([id] (download! (export/markdown (kg-of id) (graph-title id)) "text/markdown" "graph.md")))

(defn export-ttl!
  "Download a graph as RDF (Turtle) for an organisation's own graph tools
   (loretile.rdf). The graph's IRI is a URN of its id: nothing pretends to
   be hosted anywhere."
  ([] (export-ttl! @current))
  ([id] (let [k (kg-of id)]
          (download! (rdf/turtle k {:title (graph-title id)
                                    :base (str "urn:loretile:" (js/encodeURIComponent id) "/")
                                    :lang (or (fold/content-locale k) (name @i18n/locale))})
                     "text/turtle" "graph.ttl"))))

;; --- managing graphs (the Graphs and Ontologies views) -----------------------

(defn ontology-users
  "The graphs on this device bound to the ontology graph `oid`."
  [oid]
  (filter #(= oid (:ontology %)) @graphs))

(defn import-as-new-graph!
  "A log file becomes a graph of its own (the Graphs view; importing used to
   REPLACE the open graph). The registry learns its ontology from its log. The file is checked BEFORE a graph is made: a refused log leaves
   the open graph open and nothing behind. Its id, or nil."
  [title text]
  (if-let [err (:error (read-log text))]
    (do (reset! last-error err) nil)
    (when-let [id (new-graph! title)]
      (when (import-edn! text)
        (when-let [o (get-in (kg) [:graph :ontology])]
          (reset! graphs (mapv #(cond-> % (= id (:id %)) (assoc :ontology o)) @graphs))
          (persist-registry!))
        id))))

(defn delete-graph!
  "Remove a graph from this device: its log and its registry row. Refused
   for an ontology graph a graph here still uses (Denis 2026-11-19 — its
   delete is absent then). The open graph is left first — for another data
   graph, else a fresh empty one — and a failed switch aborts. true when gone."
  [id]
  (cond
    (seq (ontology-users id)) (do (reset! last-error {:error :ontology-in-use :detail id}) false)
    (and (= id @current)
         (not (if-let [other (first (remove #(= id (:id %)) @graphs))]
                (switch-graph! (:id other))
                (new-graph! (i18n/t :ui/graph-first)))))
    false
    :else
    (do (swap! sync-refused dissoc id)
        (boolean (forget-graph! id)))))

(defn load-demo!
  "Fetch a shipped demo graph in the UI's language (kg/demo/{slug}.{loc}.edn,
   served beside the app) into a graph of its OWN. Three subjects are three
   graphs, not one soup: the author edits one at a time, while the catalog
   spans them all. Picking an already-loaded demo just switches to it.
   Switching the UI language later does NOT re-translate a loaded graph:
   content lives in the log, not in the dictionary."
  ([slug] (load-demo! slug nil))
  ([slug title]
   (-> (js/fetch (str "demo/" (name slug) "." (name @i18n/locale) ".edn"))
       (.then (fn [^js r] (if (.-ok r) (.text r) (js/Promise.reject (js/Error. (str (.-status r)))))))
       (.then (fn [text]
                ;; each demo is its OWN graph (three subjects, three graphs)
                ;; — re-picking one switches to it instead of duplicating
                (let [id (str "demo-" (name slug))]
                  (if (some #(= id (:id %)) @graphs)
                    (switch-graph! id)
                    (let [{evs :events err :error} (read-log text)]
                      (if err
                        (reset! last-error err)
                        (and (shelve! @current @events)
                             (open! id evs (conj @graphs {:id id :title (or title (name slug))})))))))))
       (.catch (fn [e] (reset! last-error {:error :demo-unavailable :detail (str e)}))))))

(defn- read-log
  "log.edn text → {:events evs} or {:error e}. A log from another device is
   untrusted (security review 2026-10-03): every event must pass the
   schema AND the guard, replayed in order onto `base` (default: an empty
   graph) as if appended — the schema alone let a crafted file declare
   `requires` in a custom profile, a cycle or a dangling edge (review
   2026-10-16 A3). `skip` = ids already in the graph, not replayed."
  [text & [base skip]]
  (try
    (let [evs (vec (reader/read-string (str "[" text "]")))]
      (if-let [bad (first (remove schema/valid? evs))]
        {:error {:error :bad-import :detail (schema/explain bad)}}
        (let [fresh (vec (remove #(contains? (or skip #{}) (:ev/id %)) evs))
              r (fold/replay (or base fold/empty-kg) fresh)]
          (if-let [err (:error r)]
            {:error {:error :bad-import :detail {:event (:ev/id (:bad r)) :refused (:error err)}}}
            {:events fresh}))))
    (catch :default e {:error {:error :bad-edn :detail (str e)}})))

(defn import-edn!
  "Replace the working copy with the log in `text`; `append?` adds instead,
   skipping events already present by :ev/id. true when written; a refused
   write leaves the working copy as it was."
  ([text] (import-edn! text false))
  ([text append?]
   (let [{evs :events err :error} (if append?
                                     (read-log text (kg) (set (map :ev/id @events)))
                                     (read-log text))]
     (if err
       (do (reset! last-error err) false)
       (let [before @events]
         (if append? (swap! events into evs) (reset! events evs))
         (or (persist!) (do (reset! events before) false)))))))
