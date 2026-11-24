(ns loretile.sync-merge
  "What others did, read off their events — pure, so it is unit-tested. (It
   once also laid this browser's pending edits on others' and found the
   conflicts; server-first edits, wiki/Graph-Store.md, need neither.)")

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
