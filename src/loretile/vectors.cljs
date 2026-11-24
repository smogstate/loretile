(ns loretile.vectors
  "Concept vectors — a CACHE, never a fact: derived from title+definition,
   provider-specific, regenerable. Keyed by CONTENT HASH + embedding model —
   never by topic id: ids are unique inside a graph only, so two graphs with
   a `keys` topic used to overwrite each other and re-embed on every switch.
   An edited definition re-embeds lazily; a model switch drops all.
   Consumers: course intake candidates, intake-chat dedup, (later) the lake."
  (:require [promesa.core :as p]
            [loretile.llm :as llm]))

(defonce cache (atom {:model nil :vecs {}}))   ; text-hash -> js-array

;; ponytail: localStorage JSON (~6 KB/concept); IndexedDB when quota bites
(def ^:private store-key "loretile-vectors")

(defn- text-hash [s]
  (loop [i 0 h 5381]
    (if (< i (count s))
      (recur (inc i) (bit-and (+ (* h 33) (.charCodeAt s i)) 0xffffffff))
      (.toString h 16))))

(defn concept-text [c] (str (:title c) ". " (:definition c)))

(defn- persist! []
  (try (js/localStorage.setItem store-key (js/JSON.stringify (clj->js @cache)))
       (catch :default _ nil)))

(defn load! []
  (try (when-let [s (js/localStorage.getItem store-key)]
         (let [o (js/JSON.parse s)
               vecs (.-vecs o)]
           (reset! cache {:model (.-model o)
                          ;; an entry written before 2026-10-02 is {hash, vec} under a topic id
                          :vecs (into {} (map (fn [k] (let [e (aget vecs k)]
                                                        (if (and (some? e) (aget e "vec"))
                                                          [(aget e "hash") (aget e "vec")]
                                                          [k e])))
                                              (js/Object.keys vecs)))})))
       (catch :default _ nil)))

(defn vectors
  "{topic-id {:vec v}} for this graph's topics that have a vector."
  [kg]
  (into {} (for [[id c] (:concepts kg)
                 :let [v (get-in @cache [:vecs (text-hash (concept-text c))])]
                 :when v]
             [id {:vec v}])))

;; the graph store's embedder, when this tab holds it (graph_local sets it):
;; (fn [kg model] → promise of {topic id → vector}) — the topics are then
;; embedded ONCE, by the store, not here as well
(defonce from-store (atom nil))

(defn ensure!
  "Embed concepts whose text changed since last time (all, when the model
   changed). Resolves to the number embedded; 0 when embeddings aren't configured."
  [kg]
  (if-not (llm/embeddings?)
    (p/resolved 0)
    (let [model (:embed-model (llm/config))
          _ (when (not= model (:model @cache)) (reset! cache {:model model :vecs {}}))
          stale (vec (distinct (for [[_ c] (:concepts kg)
                                     :let [t (concept-text c) h (text-hash t)]
                                     :when (not (contains? (:vecs @cache) h))]
                                 [h t])))]
      (if (empty? stale)
        (p/resolved 0)
        (p/let [;; the store's vectors first; what it lacks is embedded here
                by-id (if-let [f @from-store] (p/catch (f kg model) (constantly {})) {})
                found (into {} (keep (fn [[id c]] (when-let [v (get by-id id)] [(text-hash (concept-text c)) v]))) (:concepts kg))
                _ (swap! cache update :vecs into found)
                rest (vec (remove (fn [[h _]] (contains? found h)) stale))
                vs (if (seq rest) (llm/embed! (mapv second rest)) [])]
          (swap! cache update :vecs into (map (fn [[h _] v] [h v]) rest vs))
          (persist!)
          (count rest))))))

(defn query-vec [text]
  (p/let [[v] (llm/embed! [text])] v))
