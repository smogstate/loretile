(ns loretile.store
  "Client state: the event vector is the truth, the KG is a reaction over
   its fold. localStorage persists the working copy; export/import moves
   log.edn in and out of the git repo (wiki/Authoring.md file layout)."
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            [reagent.core :as r]
            [loretile.agent-md :as export]
            [loretile.fold :as fold]
            [loretile.profile :as profile]
            [loretile.rdf :as rdf]
            [loretile.schema :as schema]
            [loretile.turtle :as turtle]
            [loretile.i18n :as i18n]))

;; SEVERAL GRAPHS, one working copy (wiki/Continuous-Learning.md §9):
;; `events` is the graph you have open and still lives under the original
;; key, so nothing about the working copy moved; every other graph is
;; SHELVED under "loretile-kg-log:{id}" — the same shelf pattern the chat's
;; sittings and the learner's runs already use. `ev/ns` finally carries the
;; graph id instead of the "demo" stub.
(def ^:private store-key "loretile-kg-log")
(def ^:private graphs-key "loretile-graphs")     ; [{:id :title}]
(def ^:private current-key "loretile-graph")     ; id of the open graph

(defonce events (r/atom []))
(defonce graphs (r/atom []))
(defonce current (r/atom "demo"))
(defonce last-error (r/atom nil))
(declare import-edn! read-log sync-bound!)

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
(defn kg []
  (let [evs @events at @as-of [k a v] @kg-cache]
    (if (and (identical? k evs) (= a at))
      v
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

(defn- persist! [] (write! store-key (pr-str @events)))

(defn- persist-registry! []
  (and (write! graphs-key (pr-str @graphs))
       (write! current-key (pr-str @current))))

(defn- shelf-key [id] (str store-key ":" id))

(defn- shelve! [id evs] (write! (shelf-key id) (pr-str evs)))

(defn graph-events
  "The log of any graph, open or shelved."
  [id]
  (if (= id @current) @events (read-ls (shelf-key id) [])))

(defn graph-title [id]
  (or (:title (first (filter #(= id (:id %)) @graphs))) id))

(defn load! []
  ;; "tessella-kg-log" = the pre-rename key; read once so nothing is lost
  (try (when-let [s (or (js/localStorage.getItem store-key)
                        (js/localStorage.getItem "tessella-kg-log"))]
         (reset! events (reader/read-string s)))
       (catch :default _ nil))
  ;; migration: a pre-multi-graph install has a working copy and no
  ;; registry — it becomes the first graph, keeping its id so the ev/ns
  ;; already stamped on its events still matches
  (let [reg (read-ls graphs-key nil)
        gs (or reg [{:id "demo" :title (i18n/t :ui/graph-first)}])
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
      (when (or orphan? (not reg)) (persist-registry!)))))

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
             (reset! current other)
             (reset! events (read-ls store-key []))
             (reset! last-error {:error :graph-switched-elsewhere :detail other})))
         (= k store-key) (when-not (= (pr-str @events) (.-newValue e))
                           (reset! events (read-ls store-key @events)))
         :else nil)))))

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
       (open! id (read-ls (shelf-key id) []) @graphs)))

(declare append!)

(defn new-graph!
  "Shelve what is open, start an empty graph. Returns its id, or nil when
   the open graph could not be shelved. `personal?` marks a learner's own
   graph (built from their material): what they accept there is `generated`
   (ruling 2026-10-02) and it stays private (decision #1)."
  [title & [{:keys [personal? profile]}]]
  (let [id (str "g-" (.toString (js/Date.now) 36))]
    (when (and (shelve! @current @events)
               (open! id [] (conj @graphs (cond-> {:id id :title title} personal? (assoc :personal? true)))))
      ;; a custom ontology (wiki/Profiles.md) is the FIRST event of its log
      (when profile (append! {:ev/type :profile-set :profile profile}))
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

(defn rename-graph! [id title]
  (swap! graphs (fn [gs] (mapv #(if (= id (:id %)) (assoc % :title title) %) gs)))
  (persist-registry!))

(defn forget-graph!
  "Drop a graph and its log. The open one cannot be dropped."
  [id]
  (when (not= id @current)
    (swap! graphs (fn [gs] (vec (remove #(= id (:id %)) gs))))
    (try (js/localStorage.removeItem (shelf-key id)) (catch :default _ nil))
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
      (append! {:ev/type :author-named :author {:id id :name name}}))))

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
  (when-not (= :author-named (:ev/type e)) (name-author!))
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
        (let [evs (conj evs e)]
          (if (= gid @current)
            (do (reset! events evs) (when-not (persist!) (swap! events pop) false))
            (when-not (shelve! gid evs) false))))))

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
        relink (mapcat (fn [{:keys [from to type] :as edge}]
                         (when-let [n (get-in plan [:relations type])]
                           (cond-> [[:edge-remove from type to]]
                             (not= :remove n)
                             (conj [:edge-set from n to (into (select-keys edge [:justification :inferred?])
                                                              (fold/edge-props edge))]))))
                       (:edges k))]
    (if-not e
      {:error :no-whole-ontology}
      (let [err (append! {:ev/type :change :ops (vec (concat (:ops e) retype relink))})]
        (when (nil? err) (swap! sync-refused dissoc @current))
        err))))

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
  (let [err (append-to-log! oid (stamp oid {:ev/type :change :ops ops}))]
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
            :let [v (get-in (fold/kg (graph-events id)) [:ontology :version])]
            :when (not= v (get-in e [:ops 0 1 :ontology-version]))]
      (let [err (append-to-log! id (assoc e :ev/ns id :ev/id (str (random-uuid))))]
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

(defn migrate-ontologies!
  "Once per graph: a graph that carries its own custom ontology (`profile-set`,
   the pre-2026-11-19 shape) gets an ontology graph made from it and is bound
   to it — the vocabulary moves, the data graph keeps a copy."
  []
  (doseq [{:keys [id title ontology]} @graphs
          :when (not ontology)
          :let [kg (fold/kg (graph-events id)) p (:profile kg)]
          :when (and p (not (profile/learning? p)) (not (:ontology kg)) (not (:ontology-graph? kg)))]
    (let [oid (str "o-" id)
          t (str (i18n/t :ui/ontology-of) " " title)
          e (stamp oid {:ev/type :change
                        :ops (into [[:graph-set {:ontology fold/types-ontology :title t}]] (vocabulary-ops p))})]
      (when (and (not (some #(= oid (:id %)) @graphs)) (shelve! oid [e]))
        (reset! graphs (conj @graphs {:id oid :title t :ontology fold/types-ontology}))
        (persist-registry!)
        (bind! id oid)))))

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
      (when-let [ops (fold/undo-ops (fold/kg (subvec evs 0 i)) (nth evs i))]
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
