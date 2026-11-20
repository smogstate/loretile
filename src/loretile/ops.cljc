(ns loretile.ops
  "Every write is one `change` event (wiki/Property-Graph.md, the learning
   slice 2026-11-20): these build them, so the UI, the intake, merge files
   and the tests say WHAT they do and the ops stay in one place. A node's
   `:is` names what the fold files it as — absent = an ordinary node (a
   topic in learning); `:object` `:item` `:source` `:question` `:course`
   `:cq`. An edit's level rides on the event: `:edit :patch | :material`
   (publish and pin reads it)."
  (:require [clojure.string :as str]))

(defn change
  "A `change` event of `ops`; `edit` = :patch or :material for an edit."
  ([ops] (change ops nil))
  ([ops edit] (cond-> {:ev/type :change :ops (vec ops)} edit (assoc :edit edit))))

(defn- props [m] (dissoc m :id))
(defn- edge-op [op {:keys [from type to]} & [p]] (cond-> [op from type to] p (conj p)))

;; --- nodes and links ------------------------------------------------------------

(defn propose-node
  "A node waiting for a person: filed among the proposals."
  [c provenance]
  (change [[:node-set (:id c) (assoc (props c) :status :proposed :provenance provenance)]]))

(defn accept-node
  "A node in the graph — a proposal accepted, or one written by hand."
  [c]
  (change [[:node-set (:id c) (assoc (props c) :status :accepted)]]))

(defn discard-node
  "A proposal rejected or a node removed; its id is never proposed again.
   `material` = the ids of the node's objects and items, removed with it."
  ([id] (discard-node id nil))
  ([id material] (change (concat (for [m material] [:node-remove m]) [[:node-remove id]]))))

(defn deprecate-node [id] (change [[:node-set id {:status :deprecated}]]))

(defn propose-edge [e] (change [(edge-op :edge-set e (assoc (dissoc e :from :to :type) :status :proposed))]))
(defn accept-edge [e] (change [(edge-op :edge-set e (assoc (dissoc e :from :to :type) :status :accepted))]))
(defn discard-edge [e] (change [(edge-op :edge-remove e)]))

(defn edit
  "New words for a node, an object or an item: `level` :patch (reaches a
   learner pinned to a published course) or :material (waits for the next
   publication)."
  [id payload level]
  (change [[:node-set id payload]] level))

(defn split
  "One topic becomes several: `parts` (concept maps), every item and object
   of the old topic re-pointed by the remaps; the old node removed first
   (one of the parts may reuse its id)."
  [id parts item-remap object-remap]
  (change (concat [[:node-remove id]]
                  (for [c parts] [:node-set (:id c) (assoc (props c) :status :accepted)])
                  (for [[iid cid] item-remap] [:node-set iid {:primary-concept-id cid}])
                  (for [[oid cid] object-remap] [:node-set oid {:concept-id cid}]))))

;; --- learning material ---------------------------------------------------------

(defn add-object [o] (change [[:node-set (:id o) (assoc (props o) :is :object)]]))
(defn object-status [id status] (change [[:node-set id {:status status}]]))   ; :active | :demoted
(defn link-objects [{:keys [from type to]}] (change [[:edge-set from type to {}]]))
(defn unlink-objects [{:keys [from type to]}] (change [[:edge-remove from type to]]))

(defn add-item
  "An item — a draft until accepted, unless `status` says otherwise."
  ([i] (add-item i nil))
  ([i status] (change [[:node-set (:id i) (cond-> (assoc (props i) :is :item) status (assoc :status status))]])))
(defn item-status [id status] (change [[:node-set id {:status status}]]))     ; :draft | :active | :retired

(defn add-source [s] (change [[:node-set (:id s) (assoc (props s) :is :source)]]))

(defn update-source
  "A source changed: `level` :material stales what was written from it
   (only in `concepts`, when given), :patch does not."
  [id level concepts]
  (change [[:node-set id {:updated (cond-> {:change level} (seq concepts) (assoc :concepts (vec concepts)))}]]))

(defn raise-question [id concept-id question]
  (change [[:node-set id {:is :question :concept-id concept-id :question question}]]))
(defn answer-question [id answer] (change [[:node-set id {:answer answer}]]))

(defn publish-course
  "A publication: the course node as published. The event IS the cut a
   learner is pinned to."
  [course]
  (change [[:node-set (:id course) (assoc (props course) :is :course)]]))

;; --- the graph's own properties -----------------------------------------------

(defn raise-cq [cq] (change [[:node-set (:id cq) (assoc (props cq) :is :cq)]]))
(defn retire-cq [id] (change [[:node-remove id]]))

(defn name-author
  "The name behind author id `id` — only an author names themself (the
   graph's `:authors` merges)."
  [id nm]
  (change [[:graph-set {:authors {id nm}}]]))

(defn set-owner
  "Who answers for the graph: a team and how to reach it — trimmed, blanks dropped."
  [owner]
  (change [[:graph-set {:owner (into {} (keep (fn [[k v]] (let [v (str/trim (str v))] (when (seq v) [k v])))) owner)}]]))
