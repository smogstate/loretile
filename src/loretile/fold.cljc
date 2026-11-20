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

;; --- the ontology as its own graph (wiki/Ontology-Graph.md) -----------------

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

(declare vocabulary)

(def ^:private managed
  "What the fold keeps itself and no op may set."
  #{:id :version :changed-by :changed-at :added-by :stale?})

(def node-core
  "A node's keys that are not free properties: the fold's own, the words the
   panel shows, and the style (shown as controls)."
  #{:id :title :definition :kind :status :version :changed-by :changed-at :added-by
    :stale? :tags :evidence :source-id :color :icon :learnable? :is :provenance})

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

;; --- classes (the learning slice, 2026-11-20) -----------------------------------
;; Every write is a `change`. A node's `:is` says what the fold FILES it as —
;; absent = an ordinary node (a topic in learning) — so the folded graph keeps
;; one map per class and every reader goes on reading :objects, :items … A
;; node never changes class. A node's lifecycle is its `:status`.

(def ^:private class-map
  {nil :concepts :object :objects :item :items :source :sources
   :question :questions :course :courses :cq :cqs})

(def ^:private class-of (assoc (zipmap (vals class-map) (keys class-map)) :proposals nil))

(def ^:private statuses
  {:concepts #{:proposed :accepted :deprecated}
   :objects #{:active :demoted}
   :items #{:draft :active :retired}})

(def ^:private filed [:concepts :proposals :objects :items :sources :questions :courses :cqs])

(defn node-at
  "Where node `id` is filed and what it holds: [map-key node], or nil."
  [kg id]
  (some (fn [k] (when-let [n (get-in kg [k id])] [k n])) filed))

(defn material-ids
  "The ids of a topic's objects and items — removed with it."
  [kg id]
  (concat (keep (fn [[oid o]] (when (= id (:concept-id o)) oid)) (:objects kg))
          (keep (fn [[iid i]] (when (= id (:primary-concept-id i)) iid)) (:items kg))))

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

(defn- touched
  "A topic's authorship: who changed it last and when — its words, its
   version or its material."
  [kg cid e]
  (cond-> kg
    (and cid (get-in kg [:concepts cid]))
    (update-in [:concepts cid] assoc :changed-by (:ev/actor e) :changed-at (:ev/ts e))))

(defn- grounded
  "The link to the document a node or an object was written from: a topic
   drafted from a source, an object grounded in one — so a material update
   of the source can flag what was written from it."
  [kg cls id]
  (let [n (get-in kg [(class-map cls) id])]
    (case cls
      nil (cond-> kg (:source-id n) (update :edges conj {:from id :to (:source-id n) :type :grounded-in :provenance :llm-drafted}))
      :object (cond-> kg (:grounded-in n) (update :edges conj {:from (:concept-id n) :to (:grounded-in n) :type :grounded-in :provenance :authored}))
      kg)))

(defn- set-node
  "One node-set: a proposal filed aside, a proposal accepted into the graph,
   a new node of its class, or new properties on an existing one."
  [kg a b e]
  (let [stamp #(assoc % :changed-by (:ev/actor e) :changed-at (:ev/ts e))
        [at n] (node-at kg a)
        p (dissoc (apply dissoc b managed) :is :updated)
        edit (:edit e)]
    (cond
      (and (nil? at) (nil? (:is b)) (= :proposed (:status b)))
      (assoc-in kg [:proposals a] (assoc (dissoc p :status) :id a :of :concept))

      (and (= :proposals at) (= :accepted (:status b)))
      (-> kg (update :proposals dissoc a)
          (assoc-in [:concepts a] (stamp (assoc (merge-props (dissoc n :of :provenance) (dissoc p :status))
                                                :id a :status :accepted :version 1 :added-by (:ev/actor e))))
          (grounded nil a))

      (= :proposals at) (update-in kg [:proposals a] merge-props (dissoc p :status))

      (nil? at)
      (let [cls (:is b)
            k (class-map cls)
            base (case k
                   :concepts {:title a :definition "" :status :accepted :version 1 :added-by (:ev/actor e)}
                   :objects {:status :active :version 1}
                   :items {:status :draft :version 1}
                   {})
            node (cond-> (merge-props (assoc base :id a) p) (= :concepts k) stamp)]
        (-> (assoc-in kg [k a] node)
            (grounded cls a)
            (cond-> (= :objects k) (touched (:concept-id node) e))))

      :else
      (let [content? (#{:concepts :objects :items} at)
            kg (update-in kg [at a]
                          #(cond-> (merge-props % p)
                             (= :concepts at) stamp
                             (and edit content?) (update :version (fnil inc 1))
                             ;; an author who re-versions a stale object or topic has acted on it
                             (and edit (#{:concepts :objects} at)) (dissoc :stale?)))
            u (:updated b)]
        (cond-> kg
          (= :objects at) (touched (get-in kg [:objects a :concept-id]) e)
          (and u (= :sources at)) (update-in [:sources a :version] (fnil inc 1))
          (and u (= :sources at) (= :material (:change u))) (material-stale a (some-> (:concepts u) set)))))))

(defn- apply-op
  "One operation of a `change` event, applied mechanically: the same for
   every graph. Readers give the properties their meaning."
  [kg [op a b c d] e]
  (let [stamp #(assoc % :changed-by (:ev/actor e) :changed-at (:ev/ts e))]
    (case op
      :graph-set
      (let [;; authors MERGE: each names only themself
            a (cond-> a (contains? a :authors) (update :authors #(merge-props (get-in kg [:graph :authors]) %)))
            g (merge-props (:graph kg) a)
            kg (cond-> (assoc kg :graph g :ontology-graph? (= types-ontology (:ontology g)))
                 (contains? a :authors) (assoc :authors (or (:authors g) {}))
                 (contains? a :owner) (assoc :owner (:owner g)))]
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
      :node-set (set-node kg a b e)
      :node-remove
      (let [[at _] (node-at kg a)]
        (case at
          :concepts (-> kg (update :concepts dissoc a) (update :discarded conj a)
                        (update :edges (fn [es] (set (remove #(or (= a (:from %)) (= a (:to %))) es)))))
          :proposals (-> kg (update :proposals dissoc a) (update :discarded conj a))
          :objects (-> kg (update :objects dissoc a)
                       (update :object-links (fn [ls] (set (remove #(or (= a (:from %)) (= a (:to %))) ls)))))
          nil kg
          (update kg at dissoc a)))
      :edge-set
      (let [k [a c b]
            same? (same-edge? [a b c])
            old (first (filter same? (:edges kg)))
            w (dissoc d :from :to :type :changed-by :changed-at :status)]
        (cond
          (and (get-in kg [:objects a]) (get-in kg [:objects c]))
          (update kg :object-links (fnil conj #{}) {:from a :to c :type b})
          (and (= :proposed (:status d)) (nil? old))
          (assoc-in kg [:proposals k] (merge-props (merge (get-in kg [:proposals k]) {:from a :to c :type b :of :edge}) w))
          (contains? (:proposals kg) k)
          (-> kg (update :proposals dissoc k)
              (update :edges conj (stamp (merge-props (dissoc (get-in kg [:proposals k]) :of) w))))
          :else
          (update kg :edges (fn [es] (conj (set (remove same? es))
                                            (stamp (merge-props (or old {:from a :type b :to c :provenance :authored}) w)))))))
      :edge-remove
      (let [k [a c b] link {:from a :to c :type b}]
        (cond
          (contains? (:object-links kg) link) (update kg :object-links disj link)
          (contains? (:proposals kg) k) (-> kg (update :proposals dissoc k) (update :discarded conj k))
          :else (-> kg (update :edges (fn [es] (set (remove (same-edge? [a b c]) es))))
                    (update :discarded conj k)))))))

(defn- op-problem
  "Why one op may not apply to the graph as it stands, or nil. Structure for
   every graph; conformance to the ontology in a data graph only — an
   ontology graph's edges ARE its relations (no usage checks there, Denis
   2026-11-19)."
  [kg [op a b c d] e & [migration?]]
  (let [;; a MIGRATION (a change carrying a new ontology copy) is judged whole,
        ;; at its end — op by op only structure counts
        onto? (or (:ontology-graph? kg) migration?)
        p (:profile kg)
        custom? (and p (not (profile/learning? p)))
        [at n] (when (#{:node-set :node-remove} op) (node-at kg a))]
    (case op
      :graph-set
      (let [g (merge-props (:graph kg) a)]
        (cond
          (and (not migration?) (contains? a :ontology-copy) (:ontology-copy g))
          (let [v (profile/normalize (:ontology-copy g))]
            (cond (profile/problems v) {:error :bad-profile :detail (profile/problems v)}
                  (sync-breaks kg v) {:error :ontology-breaks-data :detail (sync-breaks kg v)}))
          ;; an author names only themself
          (and (contains? a :authors)
               (or (not (map? (:authors a)))
                   (some #(not= (:ev/actor e) %) (keys (:authors a)))
                   (some #(str/blank? (str %)) (vals (:authors a)))))
          {:error :invalid-schema :detail {:author ["an author names only themself"]}}
          (and (contains? a :owner) (some? (:owner a)) (str/blank? (get-in a [:owner :team])))
          {:error :invalid-schema :detail {:owner {:team ["a team is named"]}}}))

      :node-set
      (let [cls (if at (class-of at) (:is b))
            k (if at at (class-map (:is b)))
            sk (if (= :proposals k) :concepts k)
            kind (if (contains? b :kind) (:kind b) (:kind n))
            merged (merge (when (not= :proposals at) n) (dissoc b :is :status :updated))]
        (cond
          (some managed (keys b)) {:error :invalid-schema :detail {:node [(str "managed: " (pr-str (filterv managed (keys b))))]}}
          (and (contains? b :is) (not (contains? class-map (:is b)))) {:error :invalid-schema :detail {:is [(str "unknown class " (:is b))]}}
          (and at (contains? b :is) (not= (:is b) cls)) {:error :invalid-schema :detail {:is ["a node does not change class"]}}
          (and (:edit e) (nil? at)) {:error :missing :detail a}
          ;; an op on an unknown id that names neither a class nor a title refers
          ;; to nothing — a status for an item that is not there, an answer…
          (and (nil? at) (nil? (:is b)) (not (contains? b :title))) {:error :missing :detail a}
          (and (contains? b :status) (not (contains? (get statuses sk #{}) (:status b))))
          {:error :invalid-schema :detail {:status [(str "not a status of this node: " (:status b))]}}
          (and (contains? b :updated) (not= :sources at)) {:error :missing :detail a}
          (and (contains? b :answer) (or (not= :questions at) (str/blank? (:answer b)))) {:error :missing :detail a}
          ;; proposals: a slug IS the proposal's hash — never twice, never what was rejected
          (and (= :proposed (:status b)) (#{:concepts :proposals} at)) {:error :duplicate :detail a}
          (and (= :proposed (:status b)) (contains? (:discarded kg) a)) {:error :was-discarded :detail a}
          (and (nil? at) (nil? cls) (str/blank? (str (:title b)))) {:error :invalid-schema :detail {:title ["a new node is named"]}}
          ;; a custom ontology names its node types: a node of the graph is one of them
          (and (nil? cls) (not onto?) custom? (nil? (profile/node-type-of p kind))) {:error :unknown-node-type :detail kind}
          ;; learning material keeps its shape (an object about one topic, an item's spec…)
          (and cls (schema/node-problem cls (assoc merged :id a))) {:error :invalid-schema :detail (schema/node-problem cls (assoc merged :id a))}
          ;; a competency question asks of the vocabulary — an ontology graph of its OWN
          (and (= :cq cls) (nil? at)
               (let [{:keys [relation from-type]} (:pattern b)
                     v (if (:ontology-graph? kg) (vocabulary kg) p)]
                 (and relation (or (nil? (profile/relation v relation))
                                   (and from-type (nil? (profile/node-type-of v from-type))))))) {:error :unknown-relation :detail (:pattern b)}
          (and (= :cq cls) (nil? at)
               (let [norm #(-> (str %) str/lower-case str/trim (str/replace #"\s+" " "))
                     q (norm (:question b))]
                 (some #(= q (norm (:question %))) (vals (:cqs kg))))) {:error :duplicate :detail (:question b)}
          (and (= :cqs at) (:inherited? n)) {:error :ontology-elsewhere :detail (get-in kg [:ontology :graph])}))

      :node-remove
      (cond (nil? at) {:error :missing :detail a}
            (and (= :concepts at) (some (fn [crs] (some #(= a (first %)) (:targets crs))) (vals (:courses kg)))) {:error :in-course :detail a}
            (and (= :cqs at) (:inherited? n)) {:error :ontology-elsewhere :detail (get-in kg [:ontology :graph])})

      :edge-set
      (let [oa (get-in kg [:objects a]) oc (get-in kg [:objects c])
            k [a c b]
            new? (not-any? (same-edge? [a b c]) (:edges kg))
            proposing? (and new? (= :proposed (:status d)))
            known? #(or (contains? (:concepts kg) %) (contains? (:proposals kg) %))
            bad (when (and new? (not onto?)) (bad-edge-for-profile kg {:from a :to c :type b}))]
        (cond
          (or oa oc)
          (when-not (and oa oc (not= a c) (= (:concept-id oa) (:concept-id oc)) (schema/object-link-type? b))
            {:error :bad-object-link :detail {:from a :to c :type b}})
          (and (= :proposed (:status d)) (not new?)) {:error :duplicate :detail [a c b]}
          proposing?
          (cond (not (and (known? a) (known? c))) {:error :missing-endpoint :detail [a c b]}
                (or (contains? (:proposals kg) k) (contains? (:discarded kg) k)) {:error :duplicate :detail [a c b]}
                bad bad)
          (not (and (contains? (:concepts kg) a)
                    (or (contains? (:concepts kg) c) (and (= :grounded-in b) (contains? (:sources kg) c)))))
          {:error :missing-endpoint :detail [a c b]}
          bad bad
          (and new? (not onto?) (profile/acyclic? p b) (cycle? kg b a c)) {:error :requires-cycle :detail [a c b]}))

      :edge-remove
      (when-not (or (some (same-edge? [a b c]) (:edges kg)) (contains? (:proposals kg) [a c b])
                    (contains? (:object-links kg) {:from a :to c :type b}))
        {:error :missing :detail [a c b]}))))

;; --- undo (2026-11-20): an edit is never erased, it is reversed ---------------
;; The log is append-only, so an undo is a NEW `change` whose ops put back
;; what an event changed, computed from the graph as it stood BEFORE it.

(defn- as-props
  "A filed node as the node-set that writes it back."
  [k n]
  (cond-> (apply dissoc n managed) (class-of k) (assoc :is (class-of k))))

(defn restore-node-ops
  "Ops that bring node `id` back as it stood in `kg`: a topic with its
   links, its objects and items; any other node alone (a proposal is not
   restored — it was rejected)."
  [kg id]
  (when-let [[k n] (node-at kg id)]
    (case k
      :proposals nil
      :concepts
      (let [own (fn [m f] (filter #(= id (f (val %))) (get kg m)))
            objs (own :objects :concept-id)
            oids (set (map key objs))]
        (vec (concat [[:node-set id (as-props k n)]]
                     (for [e (:edges kg)
                           :when (and (or (= id (:from e)) (= id (:to e))) (not= :grounded-in (:type e))
                                      (get-in kg [:concepts (if (= id (:from e)) (:to e) (:from e))]))]
                       [:edge-set (:from e) (:type e) (:to e) (dissoc e :from :to :type :changed-by :changed-at)])
                     (for [[oid o] objs] [:node-set oid (as-props :objects o)])
                     (for [[iid i] (own :items :primary-concept-id)] [:node-set iid (as-props :items i)])
                     (for [l (:object-links kg) :when (and (oids (:from l)) (oids (:to l)))]
                       [:edge-set (:from l) (:type l) (:to l) {}]))))
      [[:node-set id (as-props k n)]])))

(defn- inverse-op
  "The ops that undo one op, read off the graph just before it."
  [kg [op a b c d]]
  (case op
    :graph-set [[:graph-set (into {} (for [k (keys a)] [k (get-in kg [:graph k])]))]]
    :node-set (let [[k n] (node-at kg a)]
                (if (and n (not= :proposals k))
                  (when-let [ks (seq (keys (apply dissoc b (concat managed [:is :updated]))))]
                    [[:node-set a (into {} (for [x ks] [x (get n x)]))]])
                  [[:node-remove a]]))
    :node-remove (restore-node-ops kg a)
    :edge-set (if-let [old (first (filter (same-edge? [a b c]) (:edges kg)))]
                [[:edge-set a b c (into {} (for [k (keys (dissoc d :from :to :type :status))] [k (get old k)]))]]
                [[:edge-remove a b c]])
    :edge-remove (cond
                   (contains? (:object-links kg) {:from a :to c :type b}) [[:edge-set a b c {}]]
                   :else (when-let [old (first (filter (same-edge? [a b c]) (:edges kg)))]
                           [[:edge-set a b c (dissoc old :from :to :type :changed-by :changed-at)]]))))

(defn undoable?
  "Is `e` an edit undo covers? Every write is a `change`."
  [e]
  (= :change (:ev/type e)))

(defn undo-ops
  "The ops of a `change` that reverses event `e`, given the graph as it
   stood before `e`; nil when there is nothing to reverse."
  [kg-before e]
  (when (= :change (:ev/type e))
    (loop [k kg-before [o & more] (:ops e) inv ()]
      (if o
        (recur (apply-op k o e) more (concat (inverse-op k o) inv))
        (not-empty (vec inv))))))

(defn- change-problem
  "The first op that may not apply, or — in an ontology graph — a vocabulary
   the result would leave unsound (a reserved id, a second ordering
   relation): the copy into the graphs that use it could never be taken."
  [kg ops e]
  (let [migration? (and (not (:ontology-graph? kg))
                        (some (fn [[op a]] (and (= :graph-set op) (contains? a :ontology-copy) (:ontology-copy a))) ops))]
    (loop [k kg [o & more] ops]
      (if o
        (or (op-problem k o e migration?) (recur (apply-op k o e) more))
        (let [gone (set (remove #(contains? (:concepts k) %) (keys (:concepts kg))))]
          (cond
            ;; a topic removed takes its material with it, or hands it over (a
            ;; split): no object or item is left about nothing
            (and (seq gone)
                 (or (some #(gone (:concept-id %)) (vals (:objects k)))
                     (some #(gone (:primary-concept-id %)) (vals (:items k)))))
            {:error :bad-split :detail :material-unassigned}

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
                        {:error :relation-misfit :detail [(:from e) (:to e) (:type e)]})))))))))

(defn guard
  "nil when event may be appended, else {:error ...}. Every event is a
   `change`; its ops are checked in order on the graph as it would stand,
   the whole event refused at the first problem."
  [kg e]
  (if-not (schema/valid? e)
    {:error :invalid-schema :detail (schema/explain e)}
    (change-problem kg (:ops e) e)))

;; ---------------------------------------------------------------------------
;; the fold

(defn apply-event
  "One event onto the graph: every event is a `change`, applied op by op."
  [kg e]
  (if (= :change (:ev/type e)) (reduce #(apply-op %1 %2 e) kg (:ops e)) kg))

(defn author-name
  "Who an actor id is: the name the graph's log gives it, else the id."
  [kg actor]
  (or (get-in kg [:authors actor]) actor))

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

(defn- publishes? [course-id [op a b]] (and (= :node-set op) (= course-id a) (= :course (:is b))))

(defn publication-cut
  "The `ev/id` of the newest publication of this course (a `change` that
   writes the course node)."
  [events course-id]
  (->> events (filter #(some (partial publishes? course-id) (:ops %))) last :ev/id))

(defn- node-ids [e] (keep (fn [[op a]] (when (#{:node-set :node-remove} op) a)) (:ops e)))

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
        ;; a patch reaches the pinned learner only for nodes that existed at
        ;; the cut and had no other change since — a material edit, a split, a
        ;; removal would carry the rewrite with it or build a ghost from
        ;; nothing (review 2026-10-16 A4)
        (let [before (take (inc i) events)
              born (set (mapcat node-ids before))]
          (loop [out (vec before) reworked #{} [e & more :as es] (drop (inc i) events)]
            (if (empty? es)
              out
              (let [ids (node-ids e)]
                (if (and (= :patch (:edit e)) (seq ids) (every? born ids) (not-any? reworked ids))
                  (recur (conj out e) reworked more)
                  (recur out (into reworked ids) more))))))))))

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
