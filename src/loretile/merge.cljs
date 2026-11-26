(ns loretile.merge
  "MERGE FILES (2026-10-31). Someone — a person, a workflow, a model, it does
   not matter — drops a log of events for a graph on S3 under
   merges/{graph-id}/. Loretile's part is mechanical: list the files, let the
   author pick one, and walk through its events as the SAME cards the intake
   chat deals, each change visible on the map before it is accepted.

   Picking a file registers it as a SOURCE in the graph's own log, so «already
   merged» travels with exports and needs no second bookkeeping store:
   pending = listed on the bucket − sources in the fold.

   The file is a log of `change` events. Proposals in it (a node-set or an
   edge-set with `:status :proposed`) are appended on pick — the guard drops
   duplicates and re-proposals exactly as for the chat — so new topics and
   links are ghosts on the map at once. Everything else waits on its card and
   is appended VERBATIM on accept: the file's ids, actor and timestamps
   survive (provenance), and merging the same file twice is a no-op by :ev/id."
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            [reagent.core :as r]
            [loretile.chat :as chat]
            [loretile.fold :as fold]
            [loretile.i18n :as i18n :refer [t]]
            [loretile.llm :as llm]
            [loretile.ops :as ops]
            [loretile.profile :as profile]
            [loretile.schema :as schema]
            [loretile.store :as store]))

(def ^:private url-key "loretile-merge-url")
(defonce listed (r/atom []))      ; keys under merges/{open graph}/ on the bucket
(defonce error (r/atom nil))
(declare refresh!)

(defn url
  "The bucket's address (path- or host-style), or nil when merging is not
   set up. Behind the Loretile server it is the server's api/merges, which
   reads the bucket of its own config."
  []
  (if llm/backend
    (when (:merges? llm/backend) "api/merges")
    (some-> (.getItem js/localStorage url-key) str/trim not-empty)))

(defn set-url! [u]
  (store/write! url-key (str/trim (str u)))
  (refresh!))

(defn- prefix [] (str "merges/" @store/current "/"))

(defn title-of [key] (subs key (count (prefix))))

(defn pending
  "Files listed for the open graph that are not yet a source in its log."
  []
  (let [kg (store/kg)]
    (vec (remove #(get-in kg [:sources %]) @listed))))

(defn- parse-listing
  "S3 ListObjectsV2 XML → the .edn keys in it."
  [xml]
  (let [doc (.parseFromString (js/DOMParser.) xml "text/xml")]
    (->> (array-seq (.getElementsByTagName doc "Key"))
         (map #(.-textContent %))
         (filter #(str/ends-with? % ".edn"))
         vec)))

(defn- fetch-text [u]
  (-> (js/fetch u)
      (.then (fn [^js r] (if (.-ok r) (.text r) (js/Promise.reject (js/Error. (str "HTTP " (.-status r)))))))))

(defn refresh!
  "Re-list the open graph's merge files. Silent without a bucket address."
  []
  (reset! listed []) (reset! error nil)
  (when-let [u (url)]
    (-> (fetch-text (str u "/?list-type=2&prefix=" (js/encodeURIComponent (prefix))))
        (.then #(reset! listed (parse-listing %)))
        (.catch #(reset! error (str (.-message %)))))))

;; re-list whenever another graph is opened
(defonce ^:private watcher
  (add-watch store/current ::merges (fn [_ _ a b] (when (not= a b) (refresh!)))))

;; --- events → cards ----------------------------------------------------------

(defn- clip [s] (let [s (str s)] (if (> (count s) 120) (str (subs s 0 120) "…") s)))

(defn- what
  "What a file's `change` is, read off its one op: [kind op] — :node, :edge
   (proposals), :object :question :item :source (new material), else :change."
  [kg e]
  (let [[[op a b] :as os] (:ops e)]
    (cond
      (not= 1 (count os)) [:change nil]
      (and (= :node-set op) (= :proposed (:status b))) [:node (first os)]
      (and (= :edge-set op) (= :proposed (:status (nth (first os) 4)))) [:edge (first os)]
      (and (= :node-set op) (nil? (fold/node-at kg a)) (#{:object :question :item :source} (:is b))) [(:is b) (first os)]
      :else [:change (first os)])))

(defn- describe
  "A change card's words: what it touches (`:concept` lights the node on the
   map) and how. kind :edit | :remove | :other."
  [kg e]
  (let [[[op a b c] :as os] (:ops e)
        ctitle #(get-in kg [:concepts % :title] %)
        [at n] (when (#{:node-set :node-remove} op) (fold/node-at kg a))
        cid (case at :concepts a :objects (:concept-id n) :items (:primary-concept-id n) nil)
        title (case at :objects (clip (fold/plain-text (str (:text n)))) :items (get-in n [:spec :stem] a) (ctitle a))]
    (cond
      (not= 1 (count os)) {:kind :other :title (str (count os))
                           :lines [["" "" (pr-str os)]]}
      (and (= :node-set op) n (#{:deprecated :demoted :retired} (:status b))) {:kind :remove :concept cid :title title}
      (and (= :node-set op) n) {:kind :edit :change (:edit e) :concept cid :title title
                                :lines (vec (for [[k v] b] [(name k) (clip (get n k)) (clip v)]))}
      (and (= :node-remove op) n) {:kind :remove :concept cid :title title}
      (= :edge-remove op)
      {:kind :remove :concept a
       :title (let [p (store/profile)]
                (str (ctitle a) " —" (if (profile/learning? p) (i18n/enum-label "ename" b) (profile/label p b))
                     "→ " (ctitle c)))}
      :else {:kind :other :title (name op) :lines [["" "" (pr-str os)]]})))

(defn cards-of
  "The file's events as the chat's cards. A proposed topic carries the
   file's material and questions for it (ticked); every card holds `:ev`,
   the event accept appends."
  [kg key evs]
  (let [by (group-by #(first (what kg %)) evs)
        op-of #(second (what kg %))
        new-ids (set (map #(second (op-of %)) (:node by)))
        about (fn [k f cid] (filter #(= cid (f (nth (op-of %) 2))) (get by k)))
        objs (fn [cid] (vec (for [e (about :object :concept-id cid) :let [[_ id b] (op-of e)]]
                              (assoc (dissoc b :is) :id id :concept cid :on? true :ev e))))
        qs (fn [cid] (vec (for [e (about :question :concept-id cid) :let [[_ _ b] (op-of e)]]
                            {:concept cid :question (:question b) :on? true :ev e})))]
    (vec
     (concat
      (for [e (:node by) :let [[_ id b] (op-of e)
                               ;; the merge file is where the topic came from, unless it says otherwise
                               c (-> (dissoc b :status :provenance) (assoc :id id) (update :source-id #(or % key)))]]
        {:card :concept :data c :objects (objs id) :questions (qs id) :ev (ops/accept-node c)})
      (for [e (:edge by) :let [[_ from type to d] (op-of e)
                               edge (assoc (dissoc d :status) :from from :to to :type type)]]
        {:card :edge :data {:from from :to to :type (name type) :why (:justification d)}
         :ev (ops/accept-edge edge)})
      (for [e (:object by) :let [[_ id b] (op-of e)] :when (not (new-ids (:concept-id b)))]
        {:card :object :data (assoc (dissoc b :is) :id id :concept (:concept-id b)) :ev e})
      (for [e (:question by) :let [[_ _ b] (op-of e)] :when (not (new-ids (:concept-id b)))]
        {:card :question :data {:concept (:concept-id b) :question (:question b)} :ev e})
      (for [e (:item by) :let [[_ id b] (op-of e)]] {:card :item :data (assoc (dissoc b :is) :id id) :ev e})
      (for [e (:change by)]
        {:card :change :data (assoc (describe kg e) :id (:ev/id e)) :ev e})))))

(defn pick!
  "Open a sitting over the file: its proposals go on the map now, the rest
   become cards. The file becomes a source of the graph — picked up."
  [key]
  (let [graph @store/current]
    (-> (fetch-text (str (url) "/" key))
        (.then (fn [text]
                 (let [evs (vec (reader/read-string (str "[" text "]")))
                       bad (first (remove schema/valid? evs))]
                   (cond
                     (not (store/append-to? graph)) nil
                     bad (reset! store/last-error {:error :bad-import :detail (schema/explain bad)})
                     :else
                     (let [title (title-of key)
                           _ (chat/new-conversation!)
                           _ (reset! chat/graph graph)
                           _ (store/append! (ops/add-source {:id key :title title :ref key}))
                           kind #(first (what (store/kg) %))
                           _ (doseq [e (filter #(= :source (kind %)) evs)] (store/append! e))
                           ;; topics first, so a link between two new topics finds its ends
                           proposals (vec (concat (filter #(= :node (kind %)) evs)
                                                  (filter #(= :edge (kind %)) evs)))]
                       ;; each through the guard (the server's, in backend mode)
                       (store/after-all!
                        (map store/append! proposals)
                        (fn [rs]
                          (let [dropped (set (keep-indexed (fn [i e] (when (nth rs i) (:ev/id e))) proposals))
                                cards (cards-of (store/kg) key (remove #(dropped (:ev/id %)) evs))]
                            (reset! store/last-error nil)
                            (reset! chat/cards cards)
                            (swap! chat/msgs conj
                                   {:who :sys :text (str (t :chat/merge-summary {"{name}" title "{n}" (count cards)})
                                                         (when (seq dropped) (str " " (t :chat/dropped-prefix) (count dropped))))})))))))))
        (.catch #(reset! store/last-error {:error :merge-unavailable :detail (str (.-message %))})))))
