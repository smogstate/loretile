(ns loretile.graph-rows
  "Row changes back into a fold delta (wiki/Graph-Store.md § Backend mode,
   step 3): what the server's projection changed after an event
   (`projection/delta-since`) as the delta `fold/apply-delta` writes into a
   folded graph. A browser in backend mode applies the server's rows this
   way and never runs the fold itself. Not in graph_store.cljc on purpose:
   that file is part of the fold's hash, and this one is not the fold."
  (:require [loretile.fold :as fold]
            [loretile.graph-store :as gs]
            #?(:cljs [cljs.reader :as edn] :clj [clojure.edn :as edn])))

(defn- type-key [t] (keyword t))

(defn rows->delta
  "Rows {:nodes [[id class data]] :removed [id] :edges [[status data]]
   :removed-edges [[from type to status]] :discarded [edn] :heads edn} → a
   delta for `fold/apply-delta` onto `kg` (the graph they were taken after).
   Data is the stored JSON (a string)."
  [kg {:keys [nodes removed edges removed-edges discarded heads]}]
  (let [link-of (fn [from type to] (some #(when (and (= from (:from %)) (= to (:to %)) (= type (:type %))) %) (:object-links kg)))
        props (some-> heads edn/read-string)]
    {:nodes (merge (into {} (keep (fn [id] (when-let [[at] (fold/node-at kg id)] [id [at nil]]))) removed)
                   (into {} (map (fn [[id class data]] [id [(keyword class) (gs/<-json data)]])) nodes))
     :edges (merge (into {} (keep (fn [[from t to st]] (when (= "accepted" st) [[from to (type-key t)] nil]))) removed-edges)
                   (into {} (keep (fn [[st data]] (when (= "accepted" st) (let [e (gs/<-json data)] [[(:from e) (:to e) (:type e)] e])))) edges))
     :props (merge (into {} (keep (fn [[from t to st]] (when (= "proposed" st) [[from to (type-key t)] nil]))) removed-edges)
                   (into {} (keep (fn [[st data]] (when (= "proposed" st) (let [p (gs/<-json data)] [[(:from p) (:to p) (:type p)] p])))) edges))
     :links (merge (into {} (keep (fn [[from t to st]] (when (= "link" st) (when-let [l (link-of from (type-key t) to)] [l false])))) removed-edges)
                   (into {} (keep (fn [[st data]] (when (= "link" st) [(gs/<-json data) true]))) edges))
     :discarded (mapv edn/read-string discarded)
     ;; the heads come whole: every head key set, or cleared
     :heads (when props (into {} (map (fn [k] [k (get props k)])) gs/head-keys))}))
