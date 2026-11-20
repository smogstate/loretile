(ns loretile.fold
  "The graph IS this fold: kg-state × event → kg-state, pure, cljc.
   S3/git stores events; this namespace is the whole 'database layer'.
   Projections (publishable?, stale set, closure) are further pure fns.

   Fold trusts accepted events — validation (schema, cycle check) happens
   at the door, before an event is appended, via `guard`."
  (:require [clojure.string :as str]
            [clojure.set]
            [loretile.profile :as profile]
            [loretile.schema :as schema]))

(def empty-kg
  {:concepts {}      ; id -> concept (+ :status :stale? :version)
   :edges    #{}     ; #{ {:from :to :type ...} }
   :objects  {}      ; id -> object (+ :status :version)
   :object-links #{} ; #{ {:from :to :type} } — between objects of one topic
   :items    {}      ; id -> item   (+ :status :version)
   :sources  {}      ; id -> source
   :courses  {}      ; id -> course
   :questions {}     ; id -> {:concept-id :question :answer}
   :proposals {}     ; id -> pending proposal (concept/edge), by id or edge-key
   :discarded #{}    ; keys of things a curator rejected — never re-propose
   :profile  nil     ; the graph's ontology (wiki/Profiles.md); nil = learning
   :cqs      {}})    ; id -> competency question {:question :pattern}

(defn- edge-key [{:keys [from to type]}] [from to type])

;; ---------------------------------------------------------------------------
;; guards — run BEFORE append, not inside the fold

(defn cycle?
  "Would adding an edge of `type` from->to create a cycle in that relation?"
  [kg type from to]
  ;; one visited set for the whole walk: a set per PATH walked every path
  ;; of a graph with no cycle — 2^k for k parallel steps (review 2026-10-16 A12)
  (let [req (group-by :from (filter #(= type (:type %)) (:edges kg)))]
    (loop [stack [to] seen #{to}]
      (if-let [node (peek stack)]
        (if (= node from)
          true
          (let [next (remove seen (map :to (req node)))]
            (recur (into (pop stack) next) (into seen next))))
        false))))

(defn requires-cycle?
  "Would adding a :requires edge from->to create a cycle?"
  [kg from to]
  (cycle? kg :requires from to))

(defn- bad-edge-for-profile
  "The profile's say on an edge: an unknown relation, or endpoints whose
   node types it does not join. nil when fine. A proposal's endpoints may
   still be proposals themselves; their kinds are read where they are."
  [kg {:keys [from to type]}]
  (let [p (:profile kg)
        kind-of (fn [id] (or (get-in kg [:concepts id :kind]) (get-in kg [:proposals id :kind])))]
    (cond
      (nil? (profile/relation p type)) {:error :unknown-relation :detail type}
      (and (not (profile/learning? p)) (not= :grounded-in type)
           (not (profile/fits? p type (kind-of from) (kind-of to))))
      {:error :relation-misfit :detail [from to type]}
      :else nil)))

(defn- split-problem
  "Why a concept-split would damage the graph, or nil."
  [kg {:keys [id into item-remap object-remap]}]
  (let [ids (map :id into)
        targets (set ids)
        p (:profile kg)
        own-items (keep (fn [[iid i]] (when (= id (:primary-concept-id i)) iid)) (:items kg))
        own-objects (keep (fn [[oid o]] (when (= id (:concept-id o)) oid)) (:objects kg))]
    (cond
      (< (count into) 2) :fewer-than-two
      (not= (count ids) (count (distinct ids))) :duplicate-ids
      (some #(and (not= id %) (contains? (:concepts kg) %)) ids) :id-taken
      (some #(str/blank? (:title %)) into) :blank-title
      (and (not (profile/learning? p)) (some #(nil? (profile/node-type-of p (:kind %))) into)) :unknown-node-type
      (some #(not (targets (get item-remap %))) own-items) :item-unassigned
      (some #(not (targets (get object-remap %))) own-objects) :object-unassigned
      :else nil)))

;; --- the ontology as its own graph (wiki/Ontology-Graph.md) -----------------

(def ^:private vocabulary-events #{:profile-set :node-type-added :relation-added :vocabulary-described})

(defn sync-breaks
  "What a new copy of the ontology would leave without meaning in this data
   graph — node types its nodes are, relations its edges are — or nil."
  [kg p]
  (let [types (set (map :id (:node-types p)))
        rels (set (map :id (:relations p)))
        lost-types (->> (vals (:concepts kg)) (keep :kind) (remove types) distinct sort vec)
        lost-rels (->> (:edges kg) (map :type) (remove rels) distinct sort vec)]
    (when (or (seq lost-types) (seq lost-rels))
      {:node-types lost-types :relations lost-rels})))

(defn- vocabulary-problems
  "An ontology graph is a vocabulary still being written (`draft-problems`);
   anywhere else the profile must be whole (`problems`)."
  [kg p]
  (if (:ontology-graph? kg) (profile/draft-problems p) (profile/problems p)))

;; --- the property-graph core (wiki/Property-Graph.md) -----------------------

;; Every graph has an ontology, named by its `:ontology` graph property:
;; absent = the built-in LEARNING one; `types-ontology` = the built-in one
;; for describing vocabularies (a graph using it has types for nodes and
;; relations for edges — what other graphs then use as their ontology); any
;; other value = a graph of the set, whose vocabulary the graph carries as
;; `:ontology-copy`. No kind of graph is special (Denis 2026-11-19).
(def types-ontology "ontology")

(def ^:private managed
  "What the fold keeps itself and no op may set."
  #{:id :status :version :changed-by :changed-at :added-by :stale?})

(def node-core
  "A node's keys that are not free properties: the fold's own, the words the
   panel shows, and the style (shown as controls)."
  #{:id :title :definition :kind :status :version :changed-by :changed-at :added-by
    :stale? :tags :evidence :source-id :color :icon :learnable?})

(def edge-core
  "An edge's keys that are not free properties: its identity, provenance and
   the relation words an ontology edge carries (shown as their own fields)."
  #{:from :to :type :provenance :justification :inferred? :min-level :status
    :label :inverse :description :ordering? :acyclic? :symmetric? :changed-by :changed-at})

(defn own-props
  "What a person set on a node or an edge (wiki/Property-Graph.md): its keys
   minus `core`, sorted by key — the popup's rows, the exports' properties."
  [m core]
  (sort-by (comp name key) (remove (comp core key) m)))

(defn node-props [c] (own-props c node-core))
(defn edge-props [e] (own-props e edge-core))

(defn- merge-props [m props]
  (reduce-kv (fn [m k v] (if (nil? v) (dissoc m k) (assoc m k v))) (or m {}) props))

(defn- same-edge? [[from type to]] #(= [from type to] [(:from %) (:type %) (:to %)]))

(defn- apply-op
  "One operation of a `change` event, applied mechanically: the same for
   every graph. Readers give the properties their meaning."
  [kg [op a b c d] e]
  (let [stamp #(assoc % :changed-by (:ev/actor e) :changed-at (:ev/ts e))]
    (case op
      :graph-set
      (let [g (merge-props (:graph kg) a)
            kg (assoc kg :graph g :ontology-graph? (= types-ontology (:ontology g)))]
        (cond
          (not (or (contains? a :ontology) (contains? a :ontology-copy))) kg
          (:ontology-copy g)
          (-> kg
              (assoc :profile (profile/normalize (:ontology-copy g))
                     :ontology {:graph (:ontology g) :version (:ontology-version g)})
              ;; the ontology's competency questions travel in the copy and are
              ;; checked HERE, over this graph's data; the graph's own stay
              (update :cqs (fn [cs] (merge (into {} (remove (comp :inherited? val)) cs)
                                           (into {} (for [{:keys [id] :as q} (:ontology-cqs g)]
                                                      [id (assoc (dissoc q :id) :inherited? true)]))))))
          :else (-> kg (dissoc :profile :ontology)
                    (update :cqs (fn [cs] (into {} (remove (comp :inherited? val)) cs))))))
      :node-set
      (update-in kg [:concepts a]
                 (fn [n] (stamp (merge-props (or n {:id a :title a :definition "" :status :accepted :version 1
                                                    :added-by (:ev/actor e)})
                                             (apply dissoc b managed)))))
      :node-remove
      (-> kg (update :concepts dissoc a)
          (update :edges (fn [es] (set (remove #(or (= a (:from %)) (= a (:to %))) es)))))
      :edge-set
      (let [same? (same-edge? [a b c])
            old (first (filter same? (:edges kg)))]
        (update kg :edges (fn [es] (conj (set (remove same? es))
                                          (stamp (merge-props (or old {:from a :type b :to c :provenance :authored})
                                                              (dissoc d :from :to :type :changed-by :changed-at)))))))
      :edge-remove
      (update kg :edges (fn [es] (set (remove (same-edge? [a b c]) es)))))))

(defn- op-problem
  "Why one op may not apply to the graph as it stands, or nil. Structure for
   every graph; conformance to the ontology in a data graph only — an
   ontology graph's edges ARE its relations (no usage checks there, Denis
   2026-11-19)."
  [kg [op a b c d] & [migration?]]
  (let [;; a MIGRATION (a change carrying a new ontology copy) is judged whole,
        ;; at its end — op by op only structure counts
        onto? (or (:ontology-graph? kg) migration?)
        p (:profile kg)
        custom? (and p (not (profile/learning? p)))]
    (case op
      :graph-set
      (let [g (merge-props (:graph kg) a)]
        (when (and (not migration?) (contains? a :ontology-copy) (:ontology-copy g))
          (let [v (profile/normalize (:ontology-copy g))]
            (cond (profile/problems v) {:error :bad-profile :detail (profile/problems v)}
                  (sync-breaks kg v) {:error :ontology-breaks-data :detail (sync-breaks kg v)}))))
      :node-set
      (let [kind (if (contains? b :kind) (:kind b) (get-in kg [:concepts a :kind]))]
        (cond
          (some managed (keys b)) {:error :invalid-schema :detail {:node [(str "managed: " (pr-str (filterv managed (keys b))))]}}
          (and (not onto?) custom? (nil? (profile/node-type-of p kind))) {:error :unknown-node-type :detail kind}))
      :node-remove
      (cond (not (contains? (:concepts kg) a)) {:error :missing :detail a}
            (some (fn [crs] (some #(= a (first %)) (:targets crs))) (vals (:courses kg))) {:error :in-course :detail a})
      :edge-set
      (let [new? (not-any? (same-edge? [a b c]) (:edges kg))
            bad (when (and new? (not onto?)) (bad-edge-for-profile kg {:from a :to c :type b}))]
        (cond
          (not (and (contains? (:concepts kg) a) (contains? (:concepts kg) c))) {:error :missing-endpoint :detail [a c b]}
          bad bad
          (and new? (not onto?) (profile/acyclic? p b) (cycle? kg b a c)) {:error :requires-cycle :detail [a c b]}))
      :edge-remove
      (when (not-any? (same-edge? [a b c]) (:edges kg)) {:error :missing :detail [a c b]}))))

;; --- undo (2026-11-20): an edit is never erased, it is reversed ---------------
;; The log is append-only, so an undo is a NEW `change` whose ops put back
;; what an event changed, computed from the graph as it stood BEFORE it.
;; Covers the property-graph ops and the editor's own events; learning
;; material (objects, items, courses) waits for the learning migration.

(defn restore-node-ops
  "Ops that bring node `id` back as it stood in `kg`, with its links (a
   link to a SOURCE is not a node edge and is not restored)."
  [kg id]
  (when-let [c (get-in kg [:concepts id])]
    (into [[:node-set id (apply dissoc c managed)]]
          (for [e (:edges kg)
                :when (and (or (= id (:from e)) (= id (:to e))) (not= :grounded-in (:type e))
                           (get-in kg [:concepts (if (= id (:from e)) (:to e) (:from e))]))]
            [:edge-set (:from e) (:type e) (:to e) (dissoc e :from :to :type :changed-by :changed-at)]))))

(defn- inverse-op
  "The ops that undo one op, read off the graph just before it."
  [kg [op a b c d]]
  (case op
    :graph-set [[:graph-set (into {} (for [k (keys a)] [k (get-in kg [:graph k])]))]]
    :node-set (if-let [n (get-in kg [:concepts a])]
                [[:node-set a (into {} (for [k (keys (apply dissoc b managed))] [k (get n k)]))]]
                [[:node-remove a]])
    :node-remove (restore-node-ops kg a)
    :edge-set (if-let [old (first (filter (same-edge? [a b c]) (:edges kg)))]
                [[:edge-set a b c (into {} (for [k (keys (dissoc d :from :to :type))] [k (get old k)]))]]
                [[:edge-remove a b c]])
    :edge-remove (when-let [old (first (filter (same-edge? [a b c]) (:edges kg)))]
                   [[:edge-set a b c (dissoc old :from :to :type :changed-by :changed-at)]])))

(defn undoable?
  "Is `e` an edit undo covers? Cheap — read off its type, no fold."
  [e]
  (or (#{:change :concept-accepted :concept-discarded :edge-accepted :edge-discarded} (:ev/type e))
      (and (= :version-created (:ev/type e)) (= :concept (:entity e)))))

(defn undo-ops
  "The ops of a `change` that reverses event `e`, given the graph as it
   stood before `e`; nil when `e` is not an edit undo covers."
  [kg-before e]
  (let [edge-ops (fn [{:keys [from to type]}] [from type to])]
    (case (:ev/type e)
      :change (loop [k kg-before [o & more] (:ops e) inv ()]
                (if o
                  (recur (apply-op k o e) more (concat (inverse-op k o) inv))
                  (not-empty (vec inv))))
      :concept-accepted [[:node-remove (get-in e [:concept :id])]]
      :concept-discarded (restore-node-ops kg-before (:id e))
      :edge-accepted [(into [:edge-remove] (edge-ops (:edge e)))]
      :edge-discarded (let [[f t to] (edge-ops (:edge e))]
                        (when-let [old (first (filter (same-edge? [f t to]) (:edges kg-before)))]
                          [[:edge-set f t to (dissoc old :from :to :type :changed-by :changed-at)]]))
      :version-created (when (= :concept (:entity e))
                         (when-let [n (get-in kg-before [:concepts (:id e)])]
                           [[:node-set (:id e) (into {} (for [k (keys (apply dissoc (:payload e) managed))] [k (get n k)]))]]))
      nil)))

(declare vocabulary)

(defn- change-problem
  "The first op that may not apply, or — in an ontology graph — a vocabulary
   the result would leave unsound (a reserved id, a second ordering
   relation): the copy into the graphs that use it could never be taken."
  [kg ops]
  (let [migration? (and (not (:ontology-graph? kg))
                        (some (fn [[op a]] (and (= :graph-set op) (contains? a :ontology-copy) (:ontology-copy a))) ops))]
    (loop [k kg [o & more] ops]
      (if o
        (or (op-problem k o migration?) (recur (apply-op k o {}) more))
        (cond
          (:ontology-graph? k)
          (when-let [why (profile/draft-problems (vocabulary k))]
            {:error :bad-profile :detail why})
          ;; a migration (2026-11-20): the new copy and the retyped data,
          ;; judged together — whole, nothing left without meaning, every
          ;; edge fitting its relation
          migration?
          (let [p (:profile k)
                kind-of #(get-in k [:concepts % :kind])]
            (cond
              (profile/problems p) {:error :bad-profile :detail (profile/problems p)}
              (sync-breaks k p) {:error :ontology-breaks-data :detail (sync-breaks k p)}
              :else (when-let [e (first (filter #(and (not= :grounded-in (:type %))
                                                      (not (profile/fits? p (:type %) (kind-of (:from %)) (kind-of (:to %)))))
                                                (:edges k)))]
                      {:error :relation-misfit :detail [(:from e) (:to e) (:type e)]}))))))))

(defn guard
  "nil when event may be appended, else {:error ...}."
  [kg e]
  (cond
    (not (schema/valid? e)) {:error :invalid-schema :detail (schema/explain e)}
    ;; the property-graph core: every op checked in order on the graph as it
    ;; would stand, the whole event refused at the first problem
    (= :change (:ev/type e)) (change-problem kg (:ops e))
    ;; a bound graph's vocabulary is edited in its ontology graph, never here:
    ;; a local addition would be lost — or block — at the next copy
    (and (:ontology kg) (vocabulary-events (:ev/type e)))
    {:error :ontology-elsewhere :detail (get-in kg [:ontology :graph])}
    ;; the ontology is chosen for an EMPTY graph, once: a vocabulary is the
    ;; meaning of every edge in the log, so it cannot change under them
    (and (= :profile-set (:ev/type e))
         (or (seq (:concepts kg)) (seq (:edges kg)) (seq (:proposals kg)) (some? (:profile kg))))
    {:error :profile-locked}
    ;; the rules are checked on the profile the fold will STORE: one whose
    ;; only ordering relation was grounded-in passed raw and lost it in
    ;; normalisation (review 2026-10-16)
    (and (= :profile-set (:ev/type e))
         (or (profile/problems (:profile e)) (profile/problems (profile/normalize (:profile e)))))
    {:error :bad-profile :detail (or (profile/problems (:profile e)) (profile/problems (profile/normalize (:profile e))))}
    ;; the ontology grows additively, in a custom profile only
    (and (#{:relation-added :node-type-added} (:ev/type e)) (profile/learning? (:profile kg)))
    {:error :profile-locked}
    (and (= :relation-added (:ev/type e))
         (vocabulary-problems kg (update (:profile kg) :relations conj (:relation e))))
    {:error :bad-profile :detail (vocabulary-problems kg (update (:profile kg) :relations conj (:relation e)))}
    (and (= :node-type-added (:ev/type e))
         (vocabulary-problems kg (update (:profile kg) :node-types conj (:node-type e))))
    {:error :bad-profile :detail (vocabulary-problems kg (update (:profile kg) :node-types conj (:node-type e)))}
    ;; words on the vocabulary: a custom profile's own types and relations
    (and (= :vocabulary-described (:ev/type e)) (profile/learning? (:profile kg)))
    {:error :profile-locked}
    (and (= :vocabulary-described (:ev/type e))
         (let [{:keys [of id]} e
               x (if (= :relation of) (profile/relation (:profile kg) id) (profile/node-type-of (:profile kg) id))]
           (or (nil? x) (:reserved? x))))
    {:error (if (= :relation (:of e)) :unknown-relation :unknown-node-type) :detail (:id e)}
    (and (= :author-named (:ev/type e))
         (or (not= (get-in e [:author :id]) (:ev/actor e)) (str/blank? (get-in e [:author :name]))))
    {:error :invalid-schema :detail {:author ["an author names only themself"]}}
    (and (= :graph-owner-set (:ev/type e)) (str/blank? (get-in e [:owner :team])))
    {:error :invalid-schema :detail {:owner {:team ["a team is named"]}}}
    ;; a competency question's pattern names a relation (and a type) of the profile
    (and (= :cq-raised (:ev/type e))
         (let [{:keys [relation from-type]} (get-in e [:cq :pattern])
               ;; an ontology graph asks its questions of its OWN vocabulary
               p (if (:ontology-graph? kg) (vocabulary kg) (:profile kg))]
           (and relation
                (or (nil? (profile/relation p relation))
                    (and from-type (nil? (profile/node-type-of p from-type)))))))
    {:error :unknown-relation :detail (get-in e [:cq :pattern])}
    (and (= :cq-retired (:ev/type e)) (get-in kg [:cqs (:id e) :inherited?]))
    {:error :ontology-elsewhere :detail (get-in kg [:ontology :graph])}
    ;; the same question twice is one question (business dogfood 2026-10-15:
    ;; a restored pile re-accepted every card and the graph asked each twice)
    (and (= :cq-raised (:ev/type e))
         (let [norm #(-> (str %) str/lower-case str/trim (str/replace #"\s+" " "))
               q (norm (get-in e [:cq :question]))]
           (some #(= q (norm (:question %))) (vals (:cqs kg)))))
    {:error :duplicate :detail (get-in e [:cq :question])}
    ;; a custom profile names its node types: a topic must be one of them
    (and (#{:concept-accepted :concept-proposed} (:ev/type e))
         (not (profile/learning? (:profile kg)))
         (not (profile/node-type-of (:profile kg) (get-in e [:concept :kind]))))
    {:error :unknown-node-type :detail (get-in e [:concept :kind])}
    (and (#{:edge-accepted :edge-proposed} (:ev/type e))
         (not (:ontology-graph? kg))
         (bad-edge-for-profile kg (:edge e)))
    (bad-edge-for-profile kg (:edge e))
    ;; no dangling edges: accepted edges join accepted concepts (grounded-in
    ;; may point at a source); proposed edges may join pending proposals
    (and (= :edge-accepted (:ev/type e))
         (let [{:keys [from to type]} (:edge e)]
           (not (and (contains? (:concepts kg) from)
                     (or (contains? (:concepts kg) to)
                         (and (= :grounded-in type) (contains? (:sources kg) to)))))))
    {:error :missing-endpoint :detail (edge-key (:edge e))}
    (and (= :edge-proposed (:ev/type e))
         (let [known? #(or (contains? (:concepts kg) %) (contains? (:proposals kg) %))
               {:keys [from to]} (:edge e)]
           (not (and (known? from) (known? to)))))
    {:error :missing-endpoint :detail (edge-key (:edge e))}
    ;; an existing concept is edited via version-created, never re-accepted
    (and (= :concept-accepted (:ev/type e))
         (contains? (:concepts kg) (get-in e [:concept :id])))
    {:error :exists :detail (get-in e [:concept :id])}
    ;; every acyclic relation of the profile stays a DAG (`requires` in learning)
    (and (= :edge-accepted (:ev/type e))
         (not (:ontology-graph? kg))
         (profile/acyclic? (:profile kg) (get-in e [:edge :type]))
         (cycle? kg (get-in e [:edge :type]) (get-in e [:edge :from]) (get-in e [:edge :to])))
    {:error :requires-cycle :detail (edge-key (:edge e))}
    ;; the four events the UI writes since 2026-10-14 (the loretile graph's
    ;; check-cqs found them written by nothing): each needs its subject
    (and (#{:concept-deprecated :concept-split} (:ev/type e))
         (not (contains? (:concepts kg) (:id e))))
    {:error :missing :detail (:id e)}
    (and (= :object-promoted (:ev/type e))
         (not= :demoted (get-in kg [:objects (:id e) :status])))
    {:error :missing :detail (:id e)}
    ;; every event about an entity needs the entity: these made records from
    ;; nothing (review 2026-10-16 A16)
    (and (#{:item-accepted :item-retired} (:ev/type e)) (not (contains? (:items kg) (:id e))))
    {:error :missing :detail (:id e)}
    (and (= :object-demoted (:ev/type e)) (not (contains? (:objects kg) (:id e))))
    {:error :missing :detail (:id e)}
    (and (= :version-created (:ev/type e))
         (not (get-in kg [({:object :objects :item :items :concept :concepts} (:entity e)) (:id e)])))
    {:error :missing :detail (:id e)}
    (and (= :source-updated (:ev/type e)) (not (contains? (:sources kg) (:id e))))
    {:error :missing :detail (:id e)}
    ;; one edge per key: a pair drawn by hand and accepted again from the pile
    ;; drew two lines and exported twice (A17)
    (and (= :edge-accepted (:ev/type e))
         (let [k (edge-key (:edge e))] (some #(= k (edge-key %)) (:edges kg))))
    {:error :duplicate :detail (edge-key (:edge e))}
    (and (= :question-answered (:ev/type e))
         (or (not (contains? (:questions kg) (:id e))) (str/blank? (:answer e))))
    {:error :missing :detail (:id e)}
    ;; a split may not orphan anything: every item and object of the topic
    ;; (any status) goes to one of the new topics, which are ≥2, distinct,
    ;; new to the graph (the old id may be reused), of a known node type
    (and (= :concept-split (:ev/type e)) (split-problem kg e))
    {:error :bad-split :detail (split-problem kg e)}
    ;; a topic a course targets cannot vanish under its learners
    (and (#{:concept-discarded :concept-split} (:ev/type e))
         (some (fn [c] (some #(= (:id e) (first %)) (:targets c))) (vals (:courses kg))))
    {:error :in-course :detail (:id e)}
    ;; an object link joins two existing objects of ONE topic, never itself
    (and (= :object-linked (:ev/type e))
         (let [{:keys [from to]} (:link e) a (get-in kg [:objects from]) b (get-in kg [:objects to])]
           (not (and a b (not= from to) (= (:concept-id a) (:concept-id b))))))
    {:error :bad-object-link :detail (:link e)}
    (and (= :concept-proposed (:ev/type e))
         (contains? (:discarded kg) (get-in e [:concept :id])))
    {:error :was-discarded :detail (get-in e [:concept :id])}
    ;; the slug/edge-key IS the canonical proposal hash — re-ingesting the
    ;; same material must not re-propose what already exists or is pending
    (and (= :concept-proposed (:ev/type e))
         (let [id (get-in e [:concept :id])]
           (or (contains? (:concepts kg) id) (contains? (:proposals kg) id))))
    {:error :duplicate :detail (get-in e [:concept :id])}
    (and (= :edge-proposed (:ev/type e))
         (let [k (edge-key (:edge e))]
           (or (contains? (:proposals kg) k)
               (contains? (:discarded kg) k)
               (some #(= k (edge-key %)) (:edges kg)))))
    {:error :duplicate :detail (edge-key (:edge e))}
    :else nil))

;; ---------------------------------------------------------------------------
;; the fold

(defn- material-stale
  "A material change on a source stales what was WRITTEN from it (decided
   2026-10-02, review B3): every object grounded in it, and a concept whose
   own definition was drafted from it (`:source-id`). It never stales a
   concept merely because another team's object on it cites the source —
   that object is stale, the concept shows it through `stale?`."
  [kg source-id scope]
  ;; `scope` = the topics the curator answered «material» for (the re-upload
  ;; card, decided 2026-10-02 #7); nil = every topic written from the source
  (let [in? (fn [cid] (or (nil? scope) (contains? scope cid)))]
    (-> kg
        (update :objects (fn [os] (reduce-kv (fn [m oid o] (assoc m oid (cond-> o (and (= source-id (:grounded-in o)) (in? (:concept-id o)))
                                                                          (assoc :stale? true))))
                                             {} os)))
        (update :concepts (fn [cs] (reduce-kv (fn [m cid c] (assoc m cid (cond-> c (and (= source-id (:source-id c)) (in? cid))
                                                                           (assoc :stale? true))))
                                              {} cs))))))

(defmulti apply-event (fn [_kg e] (:ev/type e)))

(defmethod apply-event :profile-set [kg {:keys [profile]}]
  (assoc kg :profile (profile/normalize profile)))

(defmethod apply-event :change [kg {:keys [ops] :as e}]
  (reduce #(apply-op %1 %2 e) kg ops))

(defmethod apply-event :relation-added [kg {:keys [relation]}]
  (update-in kg [:profile :relations] conj (dissoc relation :reserved?)))

(defmethod apply-event :node-type-added [kg {:keys [node-type]}]
  (update-in kg [:profile :node-types] conj node-type))

(defmethod apply-event :vocabulary-described [kg {:keys [of id description]}]
  (let [d (str/trim description)]
    (update-in kg [:profile (if (= :relation of) :relations :node-types)]
               (fn [xs] (mapv #(if (= id (:id %)) (if (seq d) (assoc % :description d) (dissoc % :description)) %) xs)))))

(defmethod apply-event :author-named [kg {:keys [author]}]
  (assoc-in kg [:authors (:id author)] (str/trim (:name author))))

(defn author-name
  "Who an actor id is: the name the graph's log gives it, else the id
   (old logs say «denis», the codebase graph «build.py»)."
  [kg actor]
  (or (get-in kg [:authors actor]) actor))

(defn- touched
  "A topic's authorship: who added it, who changed it last and when — its
   definition, its version or its material."
  [kg cid e]
  (cond-> kg
    (and cid (get-in kg [:concepts cid]))
    (update-in [:concepts cid] assoc :changed-by (:ev/actor e) :changed-at (:ev/ts e))))

(defmethod apply-event :graph-owner-set [kg {:keys [owner]}]
  (assoc kg :owner (into {} (keep (fn [[k v]] (let [v (str/trim v)] (when (seq v) [k v])))) owner)))

(defmethod apply-event :cq-raised [kg {:keys [cq]}]
  (assoc-in kg [:cqs (:id cq)] (dissoc cq :id)))

(defmethod apply-event :cq-retired [kg {:keys [id]}]
  (update kg :cqs dissoc id))

(defmethod apply-event :concept-proposed [kg {:keys [concept provenance]}]
  (assoc-in kg [:proposals (:id concept)] (assoc concept :provenance provenance :of :concept)))

(defmethod apply-event :concept-accepted [kg {:keys [concept] :as e}]
  (-> kg
      (update :proposals dissoc (:id concept))
      (assoc-in [:concepts (:id concept)] (assoc concept :status :accepted :version 1
                                                 :added-by (:ev/actor e) :changed-by (:ev/actor e) :changed-at (:ev/ts e)))
      ;; the intake chat's provenance: concept → the document it came from,
      ;; so a material source update can flag it stale (wiki/Authoring.md step 1)
      (cond-> (:source-id concept)
        (update :edges conj {:from (:id concept) :to (:source-id concept)
                             :type :grounded-in :provenance :llm-drafted}))))

(defmethod apply-event :concept-discarded [kg {:keys [id]}]
  ;; a proposal rejected, or an accepted topic removed by hand — with its edges
  (-> kg (update :proposals dissoc id) (update :discarded conj id)
      (update :concepts dissoc id)
      (update :edges (fn [es] (set (remove #(or (= id (:from %)) (= id (:to %))) es))))))

(defmethod apply-event :concept-deprecated [kg {:keys [id]}]
  (assoc-in kg [:concepts id :status] :deprecated))

(defmethod apply-event :concept-split [kg {:keys [id into item-remap object-remap]}]
  (let [kg (reduce (fn [g c] (assoc-in g [:concepts (:id c)]
                                       (assoc c :status :accepted :version 1)))
                   (update kg :concepts dissoc id) into)
        kg (update kg :items
                   (fn [items] (reduce-kv (fn [m iid item]
                                            (assoc m iid
                                                   (cond-> item
                                                     (= id (:primary-concept-id item))
                                                     (assoc :primary-concept-id (get item-remap iid)))))
                                          {} items)))
        kg (update kg :objects
                   (fn [objs] (reduce-kv (fn [m oid o]
                                           (assoc m oid
                                                  (cond-> o
                                                    (= id (:concept-id o))
                                                    (assoc :concept-id (get object-remap oid)))))
                                         {} objs)))]
    ;; edges touching the split node are dropped; curator re-draws them
    (update kg :edges (fn [es] (set (remove #(or (= id (:from %)) (= id (:to %))) es))))))

(defmethod apply-event :edge-proposed [kg {:keys [edge]}]
  (assoc-in kg [:proposals (edge-key edge)] (assoc edge :of :edge)))

(defmethod apply-event :edge-accepted [kg {:keys [edge]}]
  (-> kg (update :proposals dissoc (edge-key edge)) (update :edges conj edge)))

(defmethod apply-event :edge-discarded [kg {:keys [edge]}]
  (let [k (edge-key edge)]
    (-> kg (update :proposals dissoc k) (update :discarded conj k)
        (update :edges (fn [es] (set (remove #(= k (edge-key %)) es)))))))

(defmethod apply-event :object-added [kg {:keys [object] :as e}]
  (-> (assoc-in kg [:objects (:id object)] (assoc object :status :active :version 1))
      (cond-> (:grounded-in object)
        (update :edges conj {:from (:concept-id object) :to (:grounded-in object)
                             :type :grounded-in :provenance :authored}))
      (touched (:concept-id object) e)))

(defmethod apply-event :object-linked [kg {:keys [link]}]
  (update kg :object-links (fnil conj #{}) (select-keys link [:from :to :type])))
(defmethod apply-event :object-unlinked [kg {:keys [link]}]
  (update kg :object-links (fnil disj #{}) (select-keys link [:from :to :type])))

(defmethod apply-event :object-promoted [kg {:keys [id]}] (assoc-in kg [:objects id :status] :active))
(defmethod apply-event :object-demoted  [kg {:keys [id]}] (assoc-in kg [:objects id :status] :demoted))

(defmethod apply-event :item-added [kg {:keys [item]}]
  (assoc-in kg [:items (:id item)] (assoc item :status :draft :version 1)))

(defmethod apply-event :item-accepted [kg {:keys [id]}] (assoc-in kg [:items id :status] :active))
(defmethod apply-event :item-retired  [kg {:keys [id]}] (assoc-in kg [:items id :status] :retired))

(defmethod apply-event :version-created [kg {:keys [entity id payload] :as e}]
  ;; an author who re-versions a stale object or topic has acted on it: its
  ;; OWN flag clears. A topic's rewrite stales nothing else — staleness
  ;; lives on material (Decisions #5); `:material` only waits for the next cut
  (let [path ({:object :objects :item :items :concept :concepts} entity)
        kg (update-in kg [path id]
                      #(-> (merge % payload)
                           (update :version inc)
                           (cond-> (#{:object :concept} entity) (dissoc :stale?))))]
    (touched kg (case entity :concept id :object (get-in kg [:objects id :concept-id]) nil) e)))

(defmethod apply-event :source-added [kg {:keys [source]}]
  (assoc-in kg [:sources (:id source)] source))

(defmethod apply-event :source-updated [kg {:keys [id change concepts]}]
  (cond-> (update-in kg [:sources id :version] (fnil inc 1))
    (= :material change) (material-stale id (some-> concepts set))))

(defmethod apply-event :question-raised [kg {:keys [id concept-id question]}]
  (assoc-in kg [:questions id] {:concept-id concept-id :question question}))

(defmethod apply-event :question-answered [kg {:keys [id answer]}]
  (assoc-in kg [:questions id :answer] answer))

(defmethod apply-event :course-published [kg {:keys [course]}]
  (assoc-in kg [:courses (:id course)] course))

(defmethod apply-event :default [kg _] kg)

(defn kg
  "The graph IS this fold."
  [events]
  (reduce apply-event empty-kg events))

(declare guard)

(defn replay
  "Fold `events` onto `base` (a kg) through the guard, event by event, the
   way they would have been appended: {:kg} or {:bad e :error err} at the
   first refusal. An imported log is untrusted — the schema alone let a
   crafted file declare `requires` in a custom profile, a cycle or a
   dangling edge (security review 2026-10-03 #3; review 2026-10-16 A3)."
  [base events]
  (reduce (fn [acc e]
            (if-let [err (guard (:kg acc) e)]
              (reduced {:bad e :error err})
              (update acc :kg apply-event e)))
          {:kg base} events))

(defn vocabulary
  "An ontology graph's vocabulary, READ OUT of its nodes and edges
   (wiki/Property-Graph.md): each node a type (title = its name, definition
   = what it is, `:color`, `:icon`, `:learnable?` properties), each edge
   type a relation whose from/to are the types its edges join and whose
   words and flags are its edges' properties (`:label` `:inverse`
   `:description` `:ordering?` `:acyclic?` `:symmetric?`)."
  [kg]
  (let [types (vec (for [c (sort-by :id (vals (:concepts kg))) :when (not= :deprecated (:status c))]
                     (cond-> {:id (keyword (:id c)) :label (or (not-empty (:title c)) (:id c))}
                       (seq (:definition c)) (assoc :description (:definition c))
                       (string? (:color c)) (assoc :color (:color c))
                       (string? (:icon c)) (assoc :icon (:icon c))
                       (true? (:learnable? c)) (assoc :learnable? true))))
        ids (set (map :id types))
        rels (vec (for [[t es] (sort-by (comp str key) (group-by :type (:edges kg)))
                        :when (not= :grounded-in t)
                        :let [es (filter #(and (ids (keyword (:from %))) (ids (keyword (:to %)))) es)]
                        :when (seq es)
                        :let [w (apply merge (map #(select-keys % [:label :inverse :description :ordering? :acyclic? :symmetric?]) es))]]
                    (cond-> {:id t :label (or (not-empty (:label w)) (name t))
                             :from (set (map (comp keyword :from) es)) :to (set (map (comp keyword :to) es))}
                      (seq (:inverse w)) (assoc :inverse (:inverse w))
                      (seq (:description w)) (assoc :description (:description w))
                      (true? (:ordering? w)) (assoc :ordering? true)
                      (true? (:acyclic? w)) (assoc :acyclic? true)
                      (true? (:symmetric? w)) (assoc :symmetric? true))))]
    (profile/normalize {:id "ontology" :title (or (get-in kg [:graph :title]) "")
                        :node-types types :relations rels})))

(defn profile
  "The graph's ontology — the learning profile unless its log set one. An
   ontology graph is described by its OWN vocabulary's relations (so its
   edges read by name) over one node kind, never learnable: courses live in
   the graphs that use it."
  [kg]
  (if (:ontology-graph? kg)
    (-> (vocabulary kg)
        (assoc :node-types [{:id :type :label "type"}])
        (update :relations (fn [rs] (mapv #(dissoc % :ordering? :acyclic?) rs))))
    (or (:profile kg) profile/learning)))

(defn defined
  "The vocabulary as written — an ontology graph's own, read out of it;
   else `profile`."
  [kg]
  (if (:ontology-graph? kg) (vocabulary kg) (profile kg)))

;; --- competency questions (wiki/Profiles.md): what the graph must answer ---
;; A CQ with a pattern is CHECKED here, mechanically — a model's opinion on
;; completeness misleads as often as it helps (OE-Assist, arXiv 2507.14552).
;; A pattern {relation, from-type?, inverse?} asks: does every node of that
;; type have at least one edge of that relation (as its `from` end, or its
;; `to` end when inverse? — «who performs X?» asks from the process)? The
;; nodes that have none are the GAPS.

(defn cq-check
  "{:asked n :answered n :gaps [ids]} for one competency question over the
   graph; nil when it has no pattern (a question only a person can judge)."
  [kg {:keys [pattern]}]
  (when-let [{:keys [relation from-type inverse?]} pattern]
    (let [ids (sort (for [[id c] (:concepts kg)
                          :when (or (nil? from-type) (= from-type (:kind c)))]
                      id))
          end (if inverse? :to :from)
          has? (fn [id] (some #(and (= relation (:type %)) (= id (end %))) (:edges kg)))
          gaps (vec (remove has? ids))]
      {:asked (count ids) :answered (- (count ids) (count gaps)) :gaps gaps})))

(defn cq-report
  "Every CQ with its check: [{:id :question :pattern :check}], gaps first."
  [kg]
  (->> (:cqs kg)
       (map (fn [[id cq]] (assoc cq :id id :check (cq-check kg cq))))
       (sort-by (fn [{:keys [id check]}] [(- (count (:gaps check))) id]))))

(defn unjustified-relations
  "Relations no competency question needs — the «no edge type without a
   reader» rule in mechanical form. Reserved ones are the engine's."
  [kg]
  (let [asked (set (keep (comp :relation :pattern) (vals (:cqs kg))))]
    (for [r (profile/relations (profile kg))
          :when (and (not (:reserved? r)) (not= :grounded-in (:id r)) (not (asked (:id r))))]
      (:id r))))

;; --- pitfalls the fold can see without a model (VSPO/OOPS!-style, the
;; mechanical part): a directed relation used both ways between the same
;; two nodes, nodes with no edge at all, node types and relations nobody
;; uses. The model's adversarial questions come on top (chat/probe-ontology!).
(defn ontology-pitfalls
  "[{:kind :symmetric-use|:isolated|:unused-type|:unused-relation :detail …}]
   for a custom profile; empty for learning."
  [kg]
  (let [p (profile kg)]
    (if (profile/learning? p)
      []
      (let [es (remove #(= :grounded-in (:type %)) (:edges kg))
            ekeys (set (map (juxt :from :to :type) es))
            both-ways (for [{:keys [from to type]} es
                            :let [r (profile/relation p type)]
                            :when (and (not (:symmetric? r)) (pos? (compare from to))
                                       (ekeys [to from type]))]
                        {:kind :symmetric-use :detail [from to type]})
            touched (set (mapcat (juxt :from :to) es))
            isolated (for [id (sort (keys (:concepts kg))) :when (not (touched id))]
                       {:kind :isolated :detail id})
            kinds (set (map :kind (vals (:concepts kg))))
            unused-types (for [t (profile/node-types p) :when (and (seq (:concepts kg)) (not (kinds (:id t))))]
                           {:kind :unused-type :detail (:id t)})
            used-rels (set (map :type es))
            unused-rels (for [r (profile/relations p)
                              :when (and (seq es) (not= :grounded-in (:id r)) (not (used-rels (:id r))))]
                          {:kind :unused-relation :detail (:id r)})]
        (vec (concat both-ways isolated unused-types unused-rels))))))

(defn cq-gaps
  "Node id -> the questions it leaves unanswered. The author's work queue."
  [kg]
  (reduce (fn [m {:keys [id question pattern check]}]
            (reduce #(update %1 %2 (fnil conj []) {:id id :question question :pattern pattern}) m (:gaps check)))
          {} (cq-report kg)))

;; --- publish and pin (wiki/Data-Model.md) ------------------------------------
;; A published version is a CUT in the log, never a copy: fold up to the
;; publication and you have exactly what was published. Authors keep
;; appending past it; a learner pinned at the cut is blind to what follows.

(defn publication-cut
  "The `ev/id` of the newest `course-published` for this course."
  [events course-id]
  (->> events
       (filter #(and (= :course-published (:ev/type %)) (= course-id (get-in % [:course :id]))))
       last :ev/id))

(defn events-at
  "The log as a learner pinned at `cut` sees it: everything up to and
   including the cut, PLUS `:patch`-level corrections appended after it.
   A wrong answer key must reach them; a reworked definition must not."
  [events cut]
  (if-not cut
    events
    (let [i (first (keep-indexed #(when (= cut (:ev/id %2)) %1) events))]
      (if (nil? i)
        events                                 ; cut from another graph, or gone
        ;; a patch carries the whole edited entity, so it reaches the pinned
        ;; learner only for an entity that existed at the cut and had no
        ;; MATERIAL change since — else it carried the rewrite with it, or
        ;; built a ghost from nothing (review 2026-10-16 A4)
        (let [before (take (inc i) events)
              made (fn [e] (case (:ev/type e)
                             :concept-accepted [[:concept (get-in e [:concept :id])]]
                             :concept-split (for [c (:into e)] [:concept (:id c)])
                             :object-added [[:object (get-in e [:object :id])]]
                             :item-added [[:item (get-in e [:item :id])]]
                             nil))
              born (set (mapcat made before))]
          (loop [out (vec before) reworked #{} [e & more :as es] (drop (inc i) events)]
            (if (empty? es)
              out
              (let [k [(:entity e) (:id e)]]
                (cond
                  ;; a split after the cut reworks its parts as a material edit does
                  (= :concept-split (:ev/type e)) (recur out (into reworked (made e)) more)
                  (not= :version-created (:ev/type e)) (recur out reworked more)
                  (= :material (:change e)) (recur out (conj reworked k) more)
                  (and (born k) (not (reworked k))) (recur (conj out e) reworked more)
                  :else (recur out reworked more))))))))))

;; --- bitemporal cut (2026-10-31) ---------------------------------------------
;; Every event carries TWO clocks once merge files exist: `:ev/ts` is the clock
;; of whoever MADE the change (a file written last month keeps its dates), and
;; `:ev/recorded` is when this log took it in — absent for a change made here,
;; which is recorded the moment it is made. So «what did the graph say on day
;; T» and «what did we know by day R» are two filters over the same log, not a
;; second model.

(defn events-as-of
  "The log cut by valid time (`:ev/ts` ≤ valid) and transaction time
   (`:ev/recorded`, else `:ev/ts`, ≤ recorded). A nil bound is unbounded;
   nil `at` is the whole log. Retroactive events (dated before what they
   touch) simply fold to nothing in the earlier view — the guard judged
   them against HEAD, which is the only state it knows."
  [events {:keys [valid recorded] :as at}]
  (if-not at
    events
    (cond->> events
      recorded (filter #(<= (or (:ev/recorded %) (:ev/ts %)) recorded))
      valid (filter #(<= (:ev/ts %) valid)))))

;; ---------------------------------------------------------------------------
;; projections

(defn concept-objects [kg id] (sort-by :id (filter #(and (= id (:concept-id %)) (= :active (:status %)))
                                                   (vals (:objects kg)))))
(defn content-locale
  "The language this graph's content is written in: the most common locale
   among its items, objects and courses; nil for a graph with none. New
   content is stamped with it — a course deals only items in its own
   language, so a German graph's new question stamped \"ru\" was never seen."
  [kg]
  (some->> (concat (vals (:items kg)) (vals (:objects kg)) (vals (:courses kg)))
           (keep :locale) seq frequencies (sort-by (comp - val)) ffirst))

(defn arrange-objects
  "Material in reading order, using the links between objects (2026-10-07).
   `objs` is what the reader may see (already filtered by locale and level);
   links to anything outside it are ignored. Returns
   [{:object o :examples [..] :simpler [..]} ..]: an example sits under what it
   illustrates, a simpler version under what it restates, and «read after»
   moves an entry right behind its predecessor. Unlinked objects keep their
   order. One level deep — a child's own children are not nested."
  [kg objs]
  (let [ids (set (map :id objs))
        links (filter #(and (ids (:from %)) (ids (:to %))) (sort-by (juxt :type :from :to) (:object-links kg)))
        child-of (reduce (fn [m {:keys [from to type]}]
                           (if (and (#{:example-of :simpler-of} type) (not (contains? m from))) (assoc m from [type to]) m))
                         {} links)
        mains (vec (remove #(contains? child-of (:id %)) objs))
        ;; «read after»: move each follower right behind its predecessor
        mains (reduce (fn [v {:keys [from to type]}]
                        (let [a (some #(when (= from (:id %)) %) v)]
                          (if (and (= :follows type) a (some #(= to (:id %)) v) (not= from to))
                            (let [w (vec (remove #(= from (:id %)) v))
                                  k (inc (first (keep-indexed #(when (= to (:id %2)) %1) w)))]
                              (vec (concat (subvec w 0 k) [a] (subvec w k))))
                            v)))
                      mains links)
        kids (fn [main type] (vec (filter #(= [type (:id main)] (child-of (:id %))) objs)))]
    (mapv (fn [m] {:object m :examples (kids m :example-of) :simpler (kids m :simpler-of)}) mains)))

(defn arranged-flat
  "The same reading order as one list — what a model prompt gets."
  [kg objs]
  (mapcat (fn [{:keys [object examples simpler]}] (concat [object] simpler examples)) (arrange-objects kg objs)))

;; --- reader marks in material text (decided 2026-10-02) ----------------------
;; `==…==` marks a piece of material's ESSENCE (the one sentence to take away),
;; `++…++` an EXAMPLE. Signaling: cues to the essential material help novices
;; most; wrong or excessive ones hurt, so drafts carry one essence and a
;; curator sees the marks before accepting. Plain text renders as before.

;; A delimiter counts only where it HUGS its text: the opening one has a
;; non-space (not the delimiter) on its right and no word char, `=` or `+` on
;; its left; the closing one mirrors it. So code — `x == 1 and y == 2`,
;; `i++; j++`, `=====` — stays text. Word chars spelled out: no `u` flag.
(def ^:private mark-re
  #"(?<![0-9A-Za-z_\u00C0-\u024F\u0370-\u03FF\u0400-\u04FF=+])==(?=[^\s=])(.+?)(?<=[^\s=])==(?![0-9A-Za-z_\u00C0-\u024F\u0370-\u03FF\u0400-\u04FF=+])|(?<![0-9A-Za-z_\u00C0-\u024F\u0370-\u03FF\u0400-\u04FF=+])\+\+(?=[^\s+])(.+?)(?<=[^\s+])\+\+(?![0-9A-Za-z_\u00C0-\u024F\u0370-\u03FF\u0400-\u04FF=+])")

(defn- mark-matches
  "[[start end essence example] …] found over the WHOLE text — the
   lookbehind must see what precedes a match, so never on a substring."
  [s]
  #?(:cljs (let [re (js/RegExp. (.-source mark-re) "g")]
             (loop [out []]
               (if-let [m (.exec re s)]
                 (recur (conj out [(.-index m) (+ (.-index m) (count (aget m 0))) (aget m 1) (aget m 2)]))
                 out)))
     :clj (let [m (re-matcher mark-re s)]
            (loop [out []]
              (if (.find m) (recur (conj out [(.start m) (.end m) (.group m 1) (.group m 2)])) out)))))

(defn mark-segments
  "Material text → [[:plain s] [:essence s] [:example s] …] in order."
  [s]
  (let [s (str s)
        [out at] (reduce (fn [[out at] [b e ess ex]]
                           [(cond-> out
                              (< at b) (conj [:plain (subs s at b)])
                              true (conj (if ess [:essence ess] [:example ex])))
                            e])
                         [[] 0] (mark-matches s))]
    (cond-> out (< at (count s)) (conj [:plain (subs s at)]))))

(defn plain-text
  "The text without its marks — what a model or a plain view sees."
  [s]
  (apply str (map second (mark-segments s))))

(defn marked?
  "Does this text carry any mark?"
  [s]
  (some? (re-find mark-re (str s))))

(defn marks->hiccup
  "Hiccup for marked text; with `on?` false, the plain text."
  [s on?]
  (if-not on?
    (plain-text s)
    (into [:span.marked]
          (for [[k t] (mark-segments s)]
            (case k
              :plain t
              :essence [:mark.mk-essence t]
              :example [:mark.mk-example t])))))

(defn concept-items
  ;; sorted: (:items kg) is a hash map, whose order shuffles as it grows
  [kg id]
  (sort-by :id (filter #(and (= id (:primary-concept-id %)) (= :active (:status %)))
                       (vals (:items kg)))))

(defn publishable?
  "Minimum viable node: 1 active object + 3 active items (wiki/Authoring.md)."
  [kg id]
  (and (>= (count (concept-objects kg id)) 1)
       (>= (count (concept-items kg id)) 3)))

(defn publishable-at?
  "Minimum viable node AT a level (and locale, when given) — what a course
   target actually needs (review B6/B5): 1 object + 3 items there."
  ([kg id level] (publishable-at? kg id level nil))
  ([kg id level locale]
   (let [fits? #(and (= level (:level %)) (or (nil? locale) (= locale (:locale %))))]
     (and (>= (count (filter fits? (concept-objects kg id))) 1)
          (>= (count (filter fits? (concept-items kg id))) 3)))))

(defn stale?
  "A concept is stale when its own definition is, or when any of its ACTIVE
   objects is — a demoted object no longer speaks for it."
  [kg id]
  (boolean (or (get-in kg [:concepts id :stale?])
               (some #(and (= id (:concept-id %)) (= :active (:status %)) (:stale? %))
                     (vals (:objects kg))))))

(defn- target-readiness
  "{:concept :level :ready? :stale?} — a stale topic is not ready: a course
   must not (re)publish material its author has not reviewed."
  [kg cid lvl locale]
  (let [st (stale? kg cid)
        dep (= :deprecated (get-in kg [:concepts cid :status]))]
    {:concept cid :level lvl :stale? st :deprecated? dep
     ;; a deprecated topic is kept for history, never (re)published
     :ready? (and (not st) (not dep) (publishable-at? kg cid lvl locale))}))

(defn course-readiness
  "Per target {:concept :level :ready? :stale?}; a course can open when all are ready."
  [kg {:keys [targets locale]}]
  (mapv (fn [[cid lvl]] (target-readiness kg cid lvl locale)) targets))

(defn attempt-concept
  "The topic an attempt counts for NOW: its item's current home, else the
   concept it was logged on. Attempts belong to items (wiki/Data-Model.md),
   so a split carries every learner's record to the new topics."
  [kg a]
  (or (get-in kg [:items (:item a) :primary-concept-id]) (:concept a)))

(defn concept-status
  "For the map shading: :draft | :accepted | :minimum-viable | :stale | :deprecated"
  [kg id]
  (let [c (get-in kg [:concepts id])]
    (cond
      (nil? c) :draft
      (= :deprecated (:status c)) :deprecated
      (stale? kg id) :stale
      (publishable? kg id) :minimum-viable
      :else :accepted)))

(defn requires-closure
  "All concepts reachable upstream from targets via :requires (incl. targets).
   The plan's scope (simulation B, wiki/Data-Model.md)."
  [kg targets]
  (let [req (group-by :from (filter #(= :requires (:type %)) (:edges kg)))]
    (loop [seen (set targets) frontier (vec targets)]
      (if-let [n (peek frontier)]
        (let [ups (remove seen (map :to (req n)))]
          (recur (into seen ups) (into (pop frontier) ups)))
        seen))))

(def level-rank {:novice 0 :apprentice 1 :professional 2})

(defn requires-closure-levels
  "Level-aware closure (review A1): {concept-id level}. A prerequisite is
   needed at the dependent's level unless the edge sets :min-level; when
   two paths disagree the higher level wins."
  [kg targets]
  (let [req (group-by :from (filter #(= :requires (:type %)) (:edges kg)))
        rank #(level-rank % 0)]
    (loop [need (into {} targets) frontier (vec targets)]
      (if-let [[n lvl] (peek frontier)]
        (let [ups (for [e (req n)
                        :let [l (or (:min-level e) lvl) cur (get need (:to e))]
                        :when (or (nil? cur) (> (rank l) (rank cur)))]
                    [(:to e) l])]
          (recur (into need ups) (into (pop frontier) ups)))
        need))))

(defn plan-readiness
  "Readiness over the whole levelled closure — targets plus the
   prerequisites the plan pulls in, each at its derived level. Targets first."
  [kg {:keys [targets locale]}]
  (let [tset (set (map first targets))]
    (->> (requires-closure-levels kg targets)
         (map (fn [[cid lvl]] (assoc (target-readiness kg cid lvl locale) :target? (contains? tset cid))))
         (sort-by (juxt (complement :target?) :concept)))))

;; ---------------------------------------------------------------------------
;; concept index search — the model searches, it never loads the whole tree

(defn- tokens [s]
  ;; explicit class: JS regex literals get no `u` flag, so \p{L} is unusable —
  ;; Latin-1 + Latin Extended, Greek, Cyrillic (ä ö ü ß were dropped)
  (->> (re-seq #"[0-9A-Za-z\u00C0-\u00D6\u00D8-\u00F6\u00F8-\u024F\u0370-\u03FF\u0400-\u04FF]{3,}" (str s))
       (map #(.toLowerCase ^String %))
       set))

(defn- with-neighbours
  "Hits first (at most ¾ of the cap, so structure context always fits),
   then their 1-hop neighbours, capped."
  [kg hits limit]
  (let [hits (take (max 1 (quot (* 3 limit) 4)) hits)
        h (set hits)
        hop (for [e (:edges kg) :when (not= :grounded-in (:type e))
                  :when (or (h (:from e)) (h (:to e)))]
              (if (h (:from e)) (:to e) (:from e)))]
    (set (take limit (distinct (concat hits hop))))))

(defn concept-candidates
  "Concept ids relevant to free text: lexical overlap of the text's words
   with title+definition, plus 1-hop neighbours of hits, capped. Small
   namespaces return everything. The fallback when no vectors exist."
  [kg text limit]
  (let [ids (keys (:concepts kg))]
    (if (<= (count ids) limit)
      (set ids)
      (let [q (tokens text)
            score (fn [id] (let [c (get-in kg [:concepts id])]
                             (count (filter q (tokens (str (:title c) " " (:definition c)))))))
            hits (->> ids (map (juxt identity score)) (filter #(pos? (second %)))
                      (sort-by second >) (map first) (take limit))]
        (with-neighbours kg hits limit)))))

(defn cosine [a b]
  (let [n (min (count a) (count b))]
    (loop [i 0 dot 0.0 na 0.0 nb 0.0]
      (if (< i n)
        (let [x (nth a i) y (nth b i)]
          (recur (inc i) (+ dot (* x y)) (+ na (* x x)) (+ nb (* y y))))
        (if (or (zero? na) (zero? nb)) 0.0 (/ dot (Math/sqrt (* na nb))))))))

(defn concept-candidates-by-vector
  "Same contract as concept-candidates, ranked by cosine against a query
   vector; concepts without a vector are ignored (they get lexical only)."
  [kg qvec vectors limit]
  (let [ids (keys (:concepts kg))]
    (if (<= (count ids) limit)
      (set ids)
      (let [hits (->> ids
                      (keep #(when-let [v (get-in vectors [% :vec])] [% (cosine qvec v)]))
                      (sort-by second >) (map first) (take limit))]
        (with-neighbours kg hits limit)))))

;; ---------------------------------------------------------------------------
;; prerequisite signals — the engine proposes candidate pairs with a
;; direction and a reason; the model only judges them (wiki/Adopted-Features
;; third pass: order of first mention + definition mentions carry most of it)

(defn- evidence-page [c]
  ;; i18n-ignore: the ingestion's stored page-marker format
  (some-> (re-find #"\[стр\. (\d+)\]" (str (:evidence c))) second
          #?(:clj Long/parseLong :cljs js/parseInt)))

(defn- stem
  "A word cut to where Russian (and German) endings start: «весовая» and
   «весовой» → «весов», «бочка»/«бочки»/«бочек» → «боч». Numbers stay whole."
  [w]
  (if (re-matches #"\d+" w) w (subs w 0 (max 3 (min 6 (- (count w) 2))))))

(defn- mentions?
  "Does b's definition or evidence use a's title (case-insensitive)? Also
   when every word of the title occurs with another ending — the whole
   title only missed «на весовой» for «Весовая» (real-model run 2026-11-20)."
  [a b]
  (let [t (str (:title a))
        text (str/lower-case (str (:definition b) " " (:evidence b)))]
    (and (>= (count t) 3)
         (or (str/includes? text (str/lower-case t))
             (let [ws (tokens t)
                   ts (tokens text)]
               (and (seq ws)
                    (every? (fn [w] (let [s (stem w)] (some #(str/starts-with? % s) ts))) ws)))))))

;; --- step order from numbered headings (business dogfood 2026-10-15: a
;; markdown document has no page marks, so the ordering relation stayed thin —
;; 12 `triggers` for 60+ processes — while the text said «#### 1. Sanctions
;; Screening» … «#### 7. Finalize Request Details» in so many words)

(defn numbered-steps
  "The headings of a markdown text, in order: [{:at :level :parent :n :sub}]
   — `:n` and `:sub` only on a numbered heading («#### 2.A. Buyer Review»
   → n 2, sub \"A\"), `:parent` = the text of the nearest heading above it
   with fewer #'s, which names the list the step belongs to."
  [text]
  ;; fenced code is blanked to spaces of the same length (offsets stay
  ;; true): a «# 1. load data» comment is no heading (review 2026-10-16 A23)
  ;; a NUMBERED LIST ITEM («1. Взвешивание на весовой …») is a step too, one
  ;; level below every heading: a regulation numbers its steps so, and their
  ;; order was never asked (real-model run 2026-11-20)
  (let [text (str text)
        scan (str/replace text #"```[\s\S]*?```" #(apply str (map (fn [ch] (if (= ch \newline) ch " ")) %)))]
    (loop [ms (re-seq #"(?m)^(?:(#{1,6})|[ \t]{0,3}(?=\d{1,3}[.)][ \t]))[ \t]*(.+)$" scan) from 0 stack [] out []]
      (if-let [[line hashes title] (first ms)]
        (let [at (str/index-of scan line from)
              level (if hashes (count hashes) 7)
              stack (vec (take-while #(< (:level %) level) stack))
              ;; «2.A Buyer», «3. FP&A», «4) Legal» — a bare «2024 Roadmap» is no step
              [_ n sub] (re-matches #"(\d{1,3})(?:\.([A-Za-z]|\d+)\.?|[.)])\s+.*" (str/trim title))
              ;; the list is named by its parent heading's TEXT and told apart
              ;; by its POSITION: two «### Steps» under two sections are two lists
              h (cond-> {:at at :level level :title (str/trim title)
                         :parent (:title (peek stack)) :parent-at (:at (peek stack))}
                  n (assoc :n #?(:clj (Long/parseLong n) :cljs (js/parseInt n)) :sub sub))]
          (recur (rest ms) (+ at (count line)) (conj stack h) (conj out h)))
        out))))

(defn step-of
  "Where a node sits in a text's numbered steps: {:parent :n :sub} of the
   heading above its evidence quote (else its title), when that heading is
   numbered; nil otherwise."
  [steps text c]
  (let [low (str/lower-case (str text))
        probe (fn [a]
                ;; i18n-ignore: the ingestion's stored page-marker format
                (let [a (-> (str a) (str/replace #"\[стр\. \d+\]" "") str/trim str/lower-case)
                      a (subs a 0 (min 60 (count a)))]
                  (when (>= (count a) 6) (str/index-of low a))))
        at (or (probe (:evidence c)) (probe (:title c)))
        h (when at (last (take-while #(<= (:at %) at) steps)))]
    (when (:n h) (select-keys h [:parent :parent-at :n :sub]))))

(defn sentence-of
  "Where the sentence holding a node's evidence quote starts in `text` (an
   id for «the same sentence»), or nil when the quote is not found."
  [text c]
  (let [low (str/lower-case (str text))
        a (-> (str (:evidence c))
              ;; i18n-ignore: the ingestion's stored page-marker format
              (str/replace #"\[стр\. \d+\]" "") str/trim str/lower-case)
        a (subs a 0 (min 60 (count a)))
        at (when (>= (count a) 6) (str/index-of low a))]
    (when at
      (inc (apply max -1 (keep #(str/last-index-of (subs low 0 at) %) ["." "!" "?" "\n"]))))))

(defn missing-steps
  "The numbered steps no node sits under, list by list: [{:parent :list-text
   :steps [{:n :sub :title :text}]}]. Only lists the model READ — two or
   more numbered steps, at least one with a node — so an index of links is
   never reported. `step?` says which nodes count as a step (a custom
   ontology: kinds its ordering relation joins; a role quoted under a step
   heading is not that step). :text = the step's own section; :list-text =
   the list's whole section, so a re-read pairs the new nodes with their
   neighbours (business dogfood A8: no node for steps 4.B and 5 — a step
   with no node can be ordered by nothing)."
  [text concepts step?]
  (let [text (str text)
        steps (numbered-steps text)
        end-of (fn [h] (or (some #(when (and (> (:at %) (:at h)) (<= (:level %) (:level h))) (:at %)) steps)
                           (count text)))
        has (set (keep #(when (step? %) (some-> (step-of steps text %) (select-keys [:parent-at :n :sub]))) concepts))]
    (vec (for [[[parent parent-at] hs] (group-by (juxt :parent :parent-at) (filter :n steps))
               :let [miss (remove #(has (select-keys % [:parent-at :n :sub])) hs)
                     head (first (filter #(= parent-at (:at %)) steps))]
               :when (and (>= (count hs) 2) (< (count miss) (count hs)) (seq miss))]
           {:parent parent :parent-at parent-at
            :list-text (subs text (if head (:at head) (:at (first hs))) (end-of (or head (last hs))))
            :steps (vec (for [h miss] {:n (:n h) :sub (:sub h) :title (:title h)
                                       :text (subs text (:at h) (end-of h))}))}))))

(defn step-next?
  "Is step `sa` the one directly after `sb` in the same numbered list? Only
   ADJACENT main numbers carry order: 4.x → 7 follows from 4.x → 5 → 6 → 7,
   and the non-adjacent pairs crowded the adjacent ones out of the pair
   budget (business dogfood A7: steps 1 → 2.A → 3.A were never asked)."
  [sa sb]
  (boolean (and sa sb (= (:parent sa) (:parent sb)) (= (:parent-at sa) (:parent-at sb))
                (= (:n sa) (inc (:n sb))))))

(defn pair-signals
  "Signals that a requires b (a = dependent, b = prerequisite). Returns
   {:from a-id :to b-id :why [...]} for the stronger direction, or nil."
  [a b]
  ;; signals are DATA, not prose — the UI localizes them, the intake
  ;; prompt formats them for the model (chat/signal-ru); building strings
  ;; here left Russian on German cards
  (let [pa (evidence-page a) pb (evidence-page b)
        ;; numbered steps of ONE list (same parent heading); 2.A and 2.B are
        ;; parallel, only the main numbers order them
        sa (:step a) sb (:step b)
        a->b (cond-> []
               (and pa pb (< pb pa)) (conj {:sig :earlier :title (:title b) :p1 pb :p2 pa})
               (step-next? sa sb) (conj {:sig :step-after :title (:title b) :n1 (:n sb) :n2 (:n sa)})
               (mentions? b a)       (conj {:sig :in-def :inner (:title b) :outer (:title a)}))
        b->a (cond-> []
               (and pa pb (< pa pb)) (conj {:sig :earlier :title (:title a) :p1 pa :p2 pb})
               (step-next? sb sa) (conj {:sig :step-after :title (:title a) :n1 (:n sa) :n2 (:n sb)})
               (mentions? a b)       (conj {:sig :in-def :inner (:title a) :outer (:title b)}))]
    (cond
      (and (seq a->b) (>= (count a->b) (count b->a))) {:from (:id a) :to (:id b) :why a->b}
      (seq b->a) {:from (:id b) :to (:id a) :why b->a}
      ;; WEAK: both quoted from one sentence — asked after every stronger
      ;; signal, before pure guesses; a barrel and «переводит вино в ёмкость»
      ;; were never paired (real-model run 2026-11-20)
      (and (:sentence a) (= (:sentence a) (:sentence b)))
      {:from (:id a) :to (:id b) :why [{:sig :same-sentence}] :weak? true}
      :else nil)))

;; i18n-ignore: the ingestion's stored page-marker format
(def ^:private page-marker #"\[стр\. (\d+)\]")

(defn text-chunks
  "Material → intake parts of at most `cap` chars, so a long document is
   read in several calls (one reply per part stays short enough to arrive
   whole) and the author sees progress. Cuts between pages when the text is
   page-marked, between paragraphs otherwise; a single unit over the cap is
   cut hard. [{:text :from :to}] — :from/:to page numbers, nil when unmarked."
  [text cap]
  (let [paged? (re-find page-marker text)
        sep (if paged? "\n" "\n\n")
        ;; i18n-ignore
        units (str/split text (if paged? #"\n(?=\[стр\. \d+\])" #"\n\s*\n"))
        units (mapcat #(if (> (count %) cap)
                         (map (fn [i] (subs % i (min (count %) (+ i cap)))) (range 0 (count %) cap))
                         [%])
                      (remove str/blank? units))
        packed (reduce (fn [acc u]
                         (let [cur (peek acc)]
                           (if (and cur (<= (+ (count cur) (count sep) (count u)) cap))
                             (conj (pop acc) (str cur sep u))
                             (conj acc u))))
                       [] units)
        page (fn [s] #?(:clj (Long/parseLong s) :cljs (js/parseInt s)))]
    (mapv (fn [chunk]
            (let [ps (map (comp page second) (re-seq page-marker chunk))]
              {:text chunk :from (first ps) :to (last ps)}))
          packed)))

(defn fitting-relations
  "The relations of profile `p` that may run from a node of kind `ka` to one
   of kind `kb` — by their from/to types, grounded-in (node → source) never.
   A custom ontology's pair search asks the model only about pairs some
   relation can connect, and names those relations in the hint."
  [p ka kb]
  (->> (profile/relations p)
       (remove #(= :grounded-in (:id %)))
       (filter #(profile/fits? p (:id %) ka kb))
       (mapv :id)))

;; --- near-duplicates: the exact-id guard refuses a repeated id, but a
;; repeat under ANOTHER id or a paraphrased competency question passes it
;; (business dogfood 2026-10-15: «Which rule is enforced in which system?»
;; and «… enforced by or checked in which system?» were both raised). Over
;; three real 100–180-node graphs the title/id rule below flagged one pair.

;; stop words of the languages the app speaks — matched, never shown
(def ^:private stop-words
  #{"the" "a" "an" "of" "and" "or" "for" "to" "in" "on" "by" "at" "is" "are" "be" "with" "from" "per"
    ;; i18n-ignore
    "и" "в" "на" "для" "по" "с" "к" "из" "или" "о" "об" "что" "как"
    "der" "die" "das" "und" "oder" "für" "von" "mit" "im" "zu" "ein" "eine"})

(defn- amount
  "«5,000» «5000» «5k» «$5K» → \"5000\"; «1m» → \"1000000\"; else the token."
  [t]
  (if-let [[_ n k] (re-matches #"(\d[\d,.]*)(k|m)?" t)]
    (let [base (str/replace n #"[,]" "")
          mult (case k "k" 1000 "m" 1000000 1)
          v (* mult #?(:clj (Double/parseDouble base) :cljs (js/parseFloat base)))]
      ;; a decimal stays itself: «4.1» and «4.2» were both «4», so
      ;; «Step 4.1» and «Step 4.2» read as one title (review 2026-10-16)
      (if (== v (Math/floor v)) (str (long v)) (str v)))
    t))

(defn- title-tokens*
  [s]
  (->> (re-seq #"[0-9A-Za-z\u00C0-\u024F\u0400-\u04FF][0-9A-Za-z\u00C0-\u024F\u0400-\u04FF,.]*" (str/lower-case (str s)))
       (map #(str/replace % #"[,.]+$" ""))
       (map amount)
       ;; a one-letter word is noise; a one-digit number is not («Step 1» ≠ «Step 2»)
       (remove #(or (stop-words %) (and (< (count %) 2) (not (re-matches #"\d" %)))))
       set))

(def title-tokens
  "The words that make a title (or an id, or a question) what it is: lower
   case, amounts normalised, stop words and one-letter words out. Memoised:
   the pile compares every card with every node on each render."
  (memoize title-tokens*))

(defn similarity
  "Jaccard over title-tokens: 1 = the same words."
  [a b]
  (let [x (title-tokens a) y (title-tokens b)]
    (if (or (empty? x) (empty? y))
      0
      (/ (count (clojure.set/intersection x y)) (count (clojure.set/union x y))))))

(def near-duplicate-at
  "How alike two titles (or ids, or questions) must be to be flagged."
  0.75)

(defn near-duplicate
  "The accepted or proposed node most like `c` ({:id :title}) — by title or
   by id — when it is at least `near-duplicate-at` alike; nil otherwise.
   A card wearing it is left to the author, never accepted in bulk."
  [kg c]
  (let [others (concat (for [[id n] (:concepts kg)] [id (:title n)])
                       (for [[id n] (:proposals kg) :when (:title n)] [id (:title n)]))
        best (->> others
                  (remove #(= (:id c) (first %)))
                  (map (fn [[id title]] {:id id :title title
                                         :score (max (similarity (:title c) title) (similarity (:id c) id))}))
                  (sort-by (comp - :score))
                  first)]
    (when (and best (>= (:score best) near-duplicate-at)) best)))

(defn similar-question
  "The competency question of the graph most like `question`, when at least
   0.7 alike — a paraphrase the exact-match guard lets through («which team
   performs step X» and «… review X» are 0.6 alike and different questions)."
  [kg question]
  (let [best (->> (:cqs kg)
                  (map (fn [[id q]] (assoc q :id id :score (similarity question (:question q)))))
                  (sort-by (comp - :score))
                  first)]
    (when (and best (>= (:score best) 0.7)) best)))

(defn- quote-probe
  "A quote as compared against a text: page marks out, white space folded,
   lower case, its first 60 chars; nil when too short to mean anything."
  [q]
  ;; i18n-ignore: the ingestion's stored page-marker format
  (let [q (-> (str q) (str/replace #"\[стр\. \d+\]" "") (str/replace #"\s+" " ") str/trim str/lower-case)
        q (subs q 0 (min 60 (count q)))]
    (when (>= (count q) 12) q)))

(defn sort-by-quote
  "Topics written from a source, sorted by whether the passage each quotes
   (its :evidence) survives in the source's NEW version: :changed — it was
   in the old text and is gone from the new; :kept — it is still there;
   :unchecked — no quote, or a quote found in neither (when the old text is
   unknown, a quote missing from the new text counts as :changed).
   Change test 2026-10-23: three updated handbook pages, 203 topics written
   from them, 207 flagged when every topic of a new version was marked —
   and exactly the 5 whose quote changed carried the 5 changed facts."
  [kg topics old-text new-text]
  (let [fold-text #(some-> % (str/replace #"\s+" " ") str/lower-case)
        old (fold-text old-text) new (fold-text new-text)]
    (group-by (fn [cid]
                (let [q (quote-probe (get-in kg [:concepts cid :evidence]))]
                  (cond
                    (or (nil? q) (nil? new)) :unchecked
                    (str/includes? new q) :kept
                    (or (nil? old) (str/includes? old q)) :changed
                    :else :unchecked)))
              topics)))

(defn source-window
  "The part of a source text a question about one node should read: `cap`
   chars around the first place an anchor (the node's evidence quote, then
   its title) occurs, a third before it and the rest after; the start of
   the text when none occurs. Business dogfood 2026-10-15: «find the answer»
   read the FIRST 15k chars of a 48k document, so every node from its second
   half was answered «the source never mentions it»."
  [text anchors cap]
  (let [text (str text)
        low (str/lower-case text)
        probe (fn [a]
                ;; i18n-ignore: the ingestion's stored page-marker format
                (let [a (-> (str a) (str/replace #"\[стр\. \d+\]" "") str/trim str/lower-case)
                      a (subs a 0 (min 60 (count a)))]
                  (when (>= (count a) 4) (str/index-of low a))))
        at (some probe anchors)]
    (if (or (<= (count text) cap) (nil? at))
      (subs text 0 (min cap (count text)))
      (let [start (max 0 (min (- at (quot cap 3)) (- (count text) cap)))]
        (subs text start (+ start cap))))))

(defn edge-candidates
  "Candidate pairs among a batch of concepts (maps with :id :title
   :definition :evidence), plus pairs with existing concepts the batch
   mentions. Signalled pairs first, then unsignalled in-batch pairs; capped.
   Direction on signalled pairs is the engine's proposal, for the model to
   confirm, reverse, or reject.
   In a CUSTOM ontology (business dogfood 2026-10-15: a quarter of the
   pairs joined types no relation connects, and every hint named the
   ordering relation) a pair whose node types fit no relation either way is
   never asked, the direction is turned to one a relation fits, and :fits
   names those relations; a learning graph is untouched.
   Step order is a signal only where the ORDERING relation fits: a role or
   a system quoted under «#### 2.A. Buyer Review» sits in that step too,
   and its «step 1 → step 2» hints filled the budget (A7). Adjacent steps
   pair across parts too — an earlier part's topics come in `kg` with
   their :step — as definitions that mention each other do.
   `open?` (2026-11-20, wiki/Ontology-Graph.md slice 4): the ontology can
   grow — a SIGNALLED pair whose types no relation connects is still asked,
   so the model can name the missing relation; unsignalled ones stay out."
  ([kg batch limit] (edge-candidates kg batch limit nil))
  ([kg batch limit {:keys [open?]}]
  (let [p (profile kg)
        typed? (not (profile/learning? p))
        ordering (profile/ordering-relation p)
        kind (fn [id] (or (some #(when (= id (:id %)) (:kind %)) batch) (get-in kg [:concepts id :kind])))
        no-step (fn [c] (let [why (vec (remove #(= :step-after (:sig %)) (:why c)))]
                          (assoc c :why why :signalled? (boolean (seq why)))))
        typed (fn [{:keys [from to] :as c}]
                (let [fwd (fitting-relations p (kind from) (kind to))
                      bwd (fitting-relations p (kind to) (kind from))
                      c (cond
                          (seq fwd) (assoc c :fits fwd)
                          (seq bwd) (assoc c :from to :to from :fits bwd)
                          (and open? (:signalled? c)) (assoc c :fits [])
                          :else nil)]
                  (cond-> c (and c (not (some #{ordering} (:fits c)))) no-step)))
        ids (set (map :id batch))
        existing (remove #(ids (:id %)) (vals (:concepts kg)))
        pairs (for [i (range (count batch)) j (range (inc i) (count batch))]
                [(nth batch i) (nth batch j)])
        cross (for [a batch b existing
                    :when (or (mentions? a b) (mentions? b a)
                              (step-next? (:step a) (:step b)) (step-next? (:step b) (:step a)))]
                [a b])
        scored (for [[a b] (concat pairs cross)]
                 (if-let [s (pair-signals a b)]
                   (assoc s :a (:id a) :b (:id b) :signalled? true)
                   {:a (:id a) :b (:id b) :from (:id a) :to (:id b) :why [] :signalled? false}))]
    (->> (if typed? (keep typed scored) scored)
         (sort-by #(cond (not (:signalled? %)) 2 (:weak? %) 1 :else 0)) (take limit) vec))))

(defn topo-order
  "Prerequisites first. Deterministic (sorted tie-break). `type` = the
   ordering relation (`:requires` unless a profile names another)."
  ([kg ids] (topo-order kg ids :requires))
  ([kg ids type]
  (let [ids (set ids)
        deps (fn [n] (->> (:edges kg)
                          (filter #(and (= type (:type %)) (= n (:from %)) (ids (:to %))))
                          (map :to)))]
    (loop [order [] left (sort ids)]
      (if (empty? left)
        order
        (let [ready (filter #(every? (set order) (deps %)) left)]
          (if (empty? ready)
            (into order left)                    ; cycle shouldn't happen (guard) — degrade
            (recur (into order ready) (remove (set ready) left)))))))))
