(ns loretile.profile
  "An ONTOLOGY PROFILE: the vocabulary a graph is written in (wiki/Profiles.md).
   A graph = a profile + its event log. `learning` is today's closed
   ontology, verbatim; any other profile is READ OUT of an ontology graph
   (`fold/vocabulary`) and carried by the graphs that use it as a copy in
   their log (the graph property `:ontology-copy`), so it versions and
   exports with the graph.

   The learning engine reads its relations BY NAME (`:requires`,
   `:is-confused-with`, `:grounded-in`): those ids are reserved — a custom
   profile may not redefine them, so no custom relation can ever reach the
   scheduler, the closure or mastery."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]))

(def learning-kinds [:fact :definition :procedure :principle :judgment])
(def learning-relations [:requires :generalizes :is-confused-with :part-of :see-also :grounded-in])

;; `grounded-in` joins any node to a source in EVERY profile: staleness
;; rides on it, and that is why a business graph beats a wiki page
(def grounded-in
  {:id :grounded-in :label "grounded in" :inverse "grounds" :from #{} :to #{} :reserved? true
   :description "node → source: the document the node was written from; the node goes STALE when it changes."})

(def learning
  "Today's ontology as a profile. One node type; `:kind` on a concept stays
   the Data Model's TAG (fact | definition | …), not a node type."
  {:id "learning" :title "Learning" :brief ""
   :node-types [{:id :concept :label "topic" :learnable? true
                 :description "One small thing a learner can master: a fact, definition, procedure, principle or judgment."}]
   :relations [{:id :requires :label "requires" :inverse "unlocks" :from #{:concept} :to #{:concept}
                :acyclic? true :ordering? true :reserved? true
                :description "A → B: A cannot be learned before B; B is a prerequisite of A."}
               {:id :generalizes :label "generalizes" :inverse "specializes" :from #{:concept} :to #{:concept} :reserved? true
                :description "A → B: A is the general case, B a special case of it."}
               {:id :is-confused-with :label "confused with" :inverse "confused with" :from #{:concept} :to #{:concept}
                :symmetric? true :reserved? true
                :description "A ↔ B: learners mistake one for the other; worth contrasting."}
               {:id :part-of :label "part of" :inverse "contains" :from #{:concept} :to #{:concept} :reserved? true
                :description "A → B: A is a component of B. Grouping only — not a prerequisite."}
               {:id :see-also :label "see also" :inverse "see also" :from #{:concept} :to #{:concept} :symmetric? true :reserved? true
                :description "A ↔ B: related reading, no order and no dependency."}
               grounded-in]})

(def node-type-schema
  [:map
   [:id :keyword]
   [:label :string]
   [:color {:optional true} :string]
   ;; a name from the app's icon set, drawn in the node (2026-11-19)
   [:icon {:optional true} :string]
   [:learnable? {:optional true} :boolean]
   ;; one sentence a MODEL reads: what a node of this type is (2026-10-21 —
   ;; a label alone is how `triggers` came to be read backwards)
   [:description {:optional true} :string]])

(def relation-schema
  [:map
   [:id :keyword]
   [:label :string]
   [:inverse {:optional true} :string]
   [:from {:optional true} [:set :keyword]]     ; node types; empty/absent = any
   [:to {:optional true} [:set :keyword]]
   [:acyclic? {:optional true} :boolean]
   [:ordering? {:optional true} :boolean]
   [:symmetric? {:optional true} :boolean]
   [:reserved? {:optional true} :boolean]
   ;; one sentence: what the relation means and which way it reads
   [:description {:optional true} :string]])

(def schema
  [:map
   [:id :string]
   [:title :string]
   [:brief {:optional true} :string]
   [:node-types [:vector {:min 1} node-type-schema]]
   [:relations [:vector relation-schema]]])

(defn learning? [p] (or (nil? p) (= "learning" (:id p))))

(defn relations [p] (if (learning? p) (:relations learning) (:relations p)))
(defn node-types [p] (if (learning? p) (:node-types learning) (:node-types p)))
(defn relation [p type] (first (filter #(= type (:id %)) (relations p))))
(defn node-type-of [p kind] (first (filter #(= kind (:id %)) (node-types p))))

(defn learnable?
  "May this graph carry courses, questions and mastery? The learning
   profile always; a custom one only when a node type says so."
  [p]
  (boolean (some :learnable? (node-types p))))

(defn ordering-relation
  "The relation the layers layout columns by — `requires` for learning."
  [p]
  (or (:id (first (filter :ordering? (relations p)))) :requires))

(defn acyclic?
  "Must this relation stay a DAG? An ordering relation always must."
  [p type]
  (let [r (relation p type)] (boolean (or (:acyclic? r) (:ordering? r)))))

(defn fits?
  "Do the endpoint node types fit the relation's from/to? Absent or empty
   sets mean any; a missing kind (an old concept) fits anything."
  [p type from-kind to-kind]
  (when-let [r (relation p type)]
    (and (or (empty? (:from r)) (nil? from-kind) (contains? (:from r) from-kind))
         (or (empty? (:to r)) (nil? to-kind) (contains? (:to r) to-kind)))))

(defn label
  "A relation's label in reading direction; `inverse?` = seen from `to`."
  ([p type] (label p type false))
  ([p type inverse?]
   (let [r (relation p type)]
     (or (when inverse? (:inverse r)) (:label r) (name type)))))

(defn kind-label [p kind]
  (or (:label (node-type-of p kind)) (some-> kind name)))

(defn reserved-ids [] (set (map :id (:relations learning))))

(def draft-schema
  "An ontology while it is written in its own graph (wiki/Ontology-Graph.md):
   it may still be empty."
  [:map
   [:id :string]
   [:title :string]
   [:brief {:optional true} :string]
   [:node-types [:vector node-type-schema]]
   [:relations [:vector relation-schema]]])

(defn- reserved-problem [p]
  (when (some #(and (contains? (reserved-ids) (:id %)) (not= :grounded-in (:id %))) (:relations p))
    {:relations ["requires, generalizes, is-confused-with, part-of and see-also are reserved for the learning engine"]}))

(defn- shape-problem [p]
  (cond
    (let [ids (set (map :id (:node-types p)))]
      (some (fn [r] (some #(not (ids %)) (concat (:from r) (:to r)))) (:relations p)))
    {:relations ["from/to name node types that do not exist"]}
    (not= (count (:node-types p)) (count (distinct (map :id (:node-types p)))))
    {:node-types ["duplicate ids"]}
    (not= (count (:relations p)) (count (distinct (map :id (:relations p)))))
    {:relations ["duplicate ids"]}
    :else nil))

(defn problems
  "nil when `p` is a sound custom profile, else a humanized explanation.
   Reserved ids keep the learning engine honest; exactly one ordering
   relation at most keeps the layout decidable; from/to must name node types."
  [p]
  (cond
    (not (m/validate schema p)) (me/humanize (m/explain schema p))
    (= "learning" (:id p)) {:id ["\"learning\" is the built-in profile"]}
    (reserved-problem p) (reserved-problem p)
    ;; at most one (2026-11-19: an ontology drawn on its map need not pick
    ;; one; without it the layers layout has nothing to column by and the
    ;; map stays free physics)
    (< 1 (count (filter :ordering? (:relations p))))
    {:relations ["at most one relation may be the ordering one (:ordering? true)"]}
    :else (shape-problem p)))

(defn draft-problems
  "The rules that hold WHILE an ontology is written in its own graph:
   reserved ids, endpoints that exist, no duplicates, at most one ordering
   relation. «At least one type» and «exactly one ordering relation» wait
   until a data graph takes it — `problems` then."
  [p]
  (cond
    (not (m/validate draft-schema p)) (me/humanize (m/explain draft-schema p))
    (= "learning" (:id p)) {:id ["\"learning\" is the built-in profile"]}
    (reserved-problem p) (reserved-problem p)
    (< 1 (count (filter :ordering? (:relations p))))
    {:relations ["at most one relation may be the ordering one (:ordering? true)"]}
    :else (shape-problem p)))

(defn normalize
  "A profile as read from the author's file: `grounded-in` added when
   absent (every profile has sources), reserved flags stripped."
  [p]
  (let [rels (vec (remove #(= :grounded-in (:id %)) (:relations p)))]
    (-> p
        (update :node-types (fn [ts] (mapv #(dissoc % :reserved?) ts)))
        (assoc :relations (conj (mapv #(dissoc % :reserved?) rels) grounded-in)))))

;; --- the prompt's view of a profile -----------------------------------------

(defn relation-lines
  "One line per relation the model may use: id, label, from → to."
  [p]
  (str/join "\n"
            (for [r (relations p) :when (not= :grounded-in (:id r))]
              (str "- " (name (:id r)) " — " (:label r)
                   (when (seq (:from r)) (str " (" (str/join "|" (map name (:from r))) " → "
                                              (str/join "|" (map name (:to r))) ")"))
                   (when (:ordering? r) " [ordering]")
                   (when (seq (:description r)) (str ": " (:description r)))))))

(defn node-type-lines [p]
  (str/join "\n" (for [t (node-types p)] (str "- " (name (:id t)) " — " (:label t)
                                              (when (seq (:description t)) (str ": " (:description t)))))))
