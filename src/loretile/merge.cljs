(ns loretile.merge
  "MERGE FILES (2026-10-31). Someone — a person, a workflow, a model, it does
   not matter — drops a log of events for a graph on S3 under
   merges/{graph-id}/. Loretile's part is mechanical: list the files, let the
   author pick one, and walk through its events as the SAME cards the intake
   chat deals, each change visible on the map before it is accepted.

   Picking a file registers it as a SOURCE in the graph's own log, so «already
   merged» travels with exports and needs no second bookkeeping store:
   pending = listed on the bucket − sources in the fold.

   Proposals in the file (concept-/edge-proposed) are appended on pick — the
   guard drops duplicates and re-proposals exactly as for the chat — so new
   topics and links are ghosts on the map at once. Everything else waits on
   its card and is appended VERBATIM on accept: the file's ids, actor and
   timestamps survive (provenance), and merging the same file twice is a
   no-op by :ev/id."
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            [reagent.core :as r]
            [loretile.chat :as chat]
            [loretile.fold :as fold]
            [loretile.i18n :as i18n :refer [t]]
            [loretile.llm :as llm]
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

(def ^:private entity-path {:concept :concepts :object :objects :item :items})

(defn- clip [s] (let [s (str s)] (if (> (count s) 120) (str (subs s 0 120) "…") s)))

(defn- describe
  "A change card's words: what it touches (`:concept` lights the node on the
   map) and how. kind :edit | :remove | :other."
  [kg e]
  (let [{:keys [id]} e
        ctitle #(get-in kg [:concepts % :title] %)]
    (case (:ev/type e)
      :version-created
      (let [{:keys [entity payload change]} e
            cur (get-in kg [(entity-path entity) id])]
        {:kind :edit :change change
         :concept (case entity :concept id :object (:concept-id cur) :item (:primary-concept-id cur))
         :title (case entity
                  :concept (ctitle id)
                  :object (clip (fold/plain-text (str (:text cur))))
                  :item (get-in cur [:spec :stem] id))
         :lines (vec (for [[k v] payload] [(name k) (clip (get cur k)) (clip v)]))})
      (:concept-discarded :concept-deprecated) {:kind :remove :concept id :title (ctitle id)}
      :edge-discarded
      (let [{:keys [from to type]} (:edge e)]
        {:kind :remove :concept from
         :title (let [p (store/profile)]
                  (str (ctitle from) " —" (if (profile/learning? p) (i18n/enum-label "ename" type) (profile/label p type))
                       "→ " (ctitle to)))})
      :object-demoted
      (let [o (get-in kg [:objects id])]
        {:kind :remove :concept (:concept-id o) :title (clip (fold/plain-text (str (:text o))))})
      :item-retired
      (let [i (get-in kg [:items id])]
        {:kind :remove :concept (:primary-concept-id i) :title (get-in i [:spec :stem] id)})
      {:kind :other :title (name (:ev/type e))
       :lines [["" "" (pr-str (dissoc e :ev/id :ev/ns :ev/actor :ev/ts :ev/type))]]})))

(def ^:private carded #{:concept-proposed :edge-proposed :object-added :question-raised :item-added :source-added})

(defn cards-of
  "The file's events as the chat's cards. A proposed topic carries the
   file's material and questions for it (ticked); every card holds `:ev`,
   the event accept appends verbatim."
  [kg key evs]
  (let [by (group-by :ev/type evs)
        new-ids (set (map #(get-in % [:concept :id]) (:concept-proposed by)))
        objs (fn [cid] (vec (for [e (:object-added by) :let [o (:object e)] :when (= cid (:concept-id o))]
                              (assoc o :concept cid :on? true :ev e))))
        qs (fn [cid] (vec (for [e (:question-raised by) :when (= cid (:concept-id e))]
                            {:concept cid :question (:question e) :on? true :ev e})))]
    (vec
     (concat
      (for [e (:concept-proposed by)
            ;; the merge file is where the topic came from, unless it says otherwise
            :let [c (update (:concept e) :source-id #(or % key))]]
        {:card :concept :data c :objects (objs (:id c)) :questions (qs (:id c))
         :ev {:ev/type :concept-accepted :concept c}})
      (for [e (:edge-proposed by) :let [{:keys [from to type justification]} (:edge e)]]
        {:card :edge :data {:from from :to to :type (name type) :why justification}
         :ev {:ev/type :edge-accepted :edge (:edge e)}})
      (for [e (:object-added by) :let [o (:object e)] :when (not (new-ids (:concept-id o)))]
        {:card :object :data (assoc o :concept (:concept-id o)) :ev e})
      (for [e (:question-raised by) :when (not (new-ids (:concept-id e)))]
        {:card :question :data {:concept (:concept-id e) :question (:question e)} :ev e})
      (for [e (:item-added by)] {:card :item :data (:item e) :ev e})
      (for [e evs :when (not (carded (:ev/type e)))]
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
                           _ (store/append! {:ev/type :source-added :source {:id key :title title :ref key}})
                           _ (doseq [e (filter #(= :source-added (:ev/type %)) evs)] (store/append! e))
                           ;; topics first, so a link between two new topics finds its ends
                           proposals (concat (filter #(= :concept-proposed (:ev/type %)) evs)
                                             (filter #(= :edge-proposed (:ev/type %)) evs))
                           dropped (set (keep #(when (store/append! %) (:ev/id %)) proposals))
                           cards (cards-of (store/kg) key (remove #(dropped (:ev/id %)) evs))]
                       (reset! store/last-error nil)
                       (reset! chat/cards cards)
                       (swap! chat/msgs conj
                              {:who :sys :text (str (t :chat/merge-summary {"{name}" title "{n}" (count cards)})
                                                    (when (seq dropped) (str " " (t :chat/dropped-prefix) (count dropped))))}))))))
        (.catch #(reset! store/last-error {:error :merge-unavailable :detail (str (.-message %))})))))
