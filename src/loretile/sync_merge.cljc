(ns loretile.sync-merge
  "How this browser's pending edits are laid on top of what others stored
   (wiki/Graph-Sync.md) — pure, so it is unit-tested. Nothing is resolved
   silently where two people meant different things: those become
   CONFLICTS for a person to decide (Denis 2026-11-20).

   A pending edit conflicts when
   - it sets a property someone else set to a different value since this
     browser last synced (`:same-property`);
   - it touches a node or link someone else removed (`:removed`);
   - the graph as it now stands refuses it (`:refused`, the guard's reason).
   Anything else composes: changes touch individual properties."
  (:require [clojure.set :as set]
            [loretile.fold :as f]))

(defn writes
  "{[entity key] value} an event sets — entity = [:node id] or
   [:edge from type to]. Covers the property-graph ops and the editor's
   own edit of a node's words."
  [e]
  (case (:ev/type e)
    :change (into {} (mapcat (fn [[op a b c d]]
                               (case op
                                 :node-set (for [[k v] b] [[[:node a] k] v])
                                 :edge-set (for [[k v] d] [[[:edge a b c] k] v])
                                 :graph-set (for [[k v] a] [[[:graph] k] v])
                                 nil))
                             (:ops e)))
    {}))

(defn removes
  "The entities an event removes: #{[:node id] [:edge from type to] …}."
  [e]
  (case (:ev/type e)
    :change (set (keep (fn [[op a b c]]
                         (case op :node-remove [:node a] :edge-remove [:edge a b c] nil))
                       (:ops e)))
    #{}))

(defn- touches
  "The entities an event refers to — what it writes, removes or links."
  [e]
  (into (set (map ffirst (writes e)))
        (concat (removes e)
                (case (:ev/type e)
                  :change (mapcat (fn [[op a b c]] (when (= :edge-set op) [[:node a] [:node c]])) (:ops e))
                  nil))))

(defn rebase
  "Lay `mine` (pending, in order) on the graph `theirs-kg` (confirmed +
   what others stored, `remote`). {:keep [events still to send]
   :conflicts [{:kind :mine :their …}]}."
  [theirs-kg remote mine]
  (let [their-writes (apply merge (map writes remote))
        their-removed (apply set/union #{} (map removes remote))]
    (loop [k theirs-kg [e & more] mine keep [] conflicts []]
      (if-not e
        {:keep keep :conflicts conflicts}
        (let [clash (into {} (for [[ek v] (writes e)
                                   :let [tv (get their-writes ek ::none)]
                                   :when (and (not= ::none tv) (not= tv v))]
                               [ek {:theirs tv :mine v}]))
              gone (seq (filter their-removed (touches e)))
              err (when-not (or (seq clash) gone) (f/guard k e))]
          (cond
            (seq clash) (recur k more keep (conj conflicts {:kind :same-property :mine e :clash clash}))
            gone (recur k more keep (conj conflicts {:kind :removed :mine e :gone (vec gone)}))
            err (recur k more keep (conj conflicts {:kind :refused :mine e :error err}))
            :else (recur (f/apply-event k e) more (conj keep e) conflicts)))))))

(defn overridden
  "After a push: events others stored just BEFORE mine (`others`) whose
   writes mine overrode — the server's order made mine win; a person may
   still want theirs. [{:kind :overridden :mine e :clash {…}} …]"
  [others mine]
  (let [their-writes (apply merge (map writes others))]
    (vec (for [e mine
               :let [clash (into {} (for [[ek v] (writes e)
                                          :let [tv (get their-writes ek ::none)]
                                          :when (and (not= ::none tv) (not= tv v))]
                                      [ek {:theirs tv :mine v}]))]
               :when (seq clash)]
           {:kind :overridden :mine e :clash clash}))))

(defn ops-for
  "The `change` ops that set the given {[entity key] value} writes."
  [ws]
  (vec (for [[[ent k] v] ws]
         (case (first ent)
           :node [:node-set (second ent) {k v}]
           :edge (let [[_ a b c] ent] [:edge-set a b c {k v}])
           :graph [:graph-set {k v}]))))

(defn summary
  "What others did, for the collaborators' pills (wiki/Graph-Sync.md phase
   C): `remote` (their events, in order) read against `kg`, the graph before
   them. [{:kind :nodes|:links|:props|:removed :n}], most telling first,
   only kinds that happened."
  [kg remote]
  (let [edge-key (fn [{:keys [from type to]}] [from type to])
        tally (fn [[nodes edges counts] e]
                (let [bump (fn [c k n] (update c k (fnil + 0) n))]
                  (case (:ev/type e)
                    :change
                    (reduce (fn [[nodes edges counts] [op a b c d]]
                              (case op
                                :node-set (if (nodes a)
                                            [nodes edges (bump counts :props (count b))]
                                            [(conj nodes a) edges (bump counts :nodes 1)])
                                :edge-set (if (edges [a b c])
                                            [nodes edges (bump counts :props (count d))]
                                            [nodes (conj edges [a b c]) (bump counts :links 1)])
                                :graph-set [nodes edges (bump counts :props (count a))]
                                (:node-remove :edge-remove) [nodes edges (bump counts :removed 1)]
                                [nodes edges counts]))
                            [nodes edges counts] (:ops e))
                    [nodes edges counts])))
        [_ _ counts] (reduce tally [(set (keys (:concepts kg))) (set (map edge-key (:edges kg))) {}] remote)]
    (vec (for [k [:nodes :links :props :removed] :let [n (get counts k)] :when (pos? (or n 0))]
           {:kind k :n n}))))
