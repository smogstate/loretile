(ns loretile.server.graphs
  "Graphs kept by the server (wiki/Graph-Sync.md): browsers work local-first
   and sync their event logs here. A graph is its log, stored as BATCHES —
   one object per accepted POST, `{graph-id}/{seq:012d}.edn`, one event per
   line — in a directory (file://) or a bucket and prefix (s3://), from
   [:graphs :url]. S3 cannot append, so a batch is its own object; the seq
   is the order every browser agrees on.

   The server STORES, it does not judge (Denis 2026-11-20: «store
   everything»): an event whose :ev/id it already has is skipped (a resend
   is harmless), everything else is appended as sent. Conflicts are
   resolved by a person, in the browser.

   ponytail: one server process — the per-graph lock and the cache of known
   ids live in memory. Several instances need a conditional write (S3
   If-None-Match on the batch key) before they can share a store."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [loretile.server.config :as config]
            [loretile.server.s3 :as s3])
  (:import (java.io File)
           (java.nio.charset StandardCharsets)))

(def ^:private id-re #"[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

(defn valid-id?
  "A graph id the store accepts — no path, no traversal."
  [id]
  (boolean (and (string? id) (re-matches id-re id))))

(defn- store []
  (let [u (config/get-in-config [:graphs :url])]
    (cond
      (str/blank? u) nil
      (str/starts-with? u "file://") {:kind :file :dir (subs u (count "file://"))}
      (str/starts-with? u "s3://") (assoc (s3/parse-url u) :kind :s3 :aws (config/get-in-config [:aws]))
      :else (throw (ex-info (str "graphs url must be file:// or s3://, got " u) {})))))

(defn configured? [] (some? (store)))

(defn- batch-name [n] (format "%012d.edn" n))
(defn- batch-seq [name] (some-> (re-matches #"(\d{12})\.edn" name) second parse-long))

;; --- the two backends -------------------------------------------------------------

(defn- list-batches
  "The seqs of a graph's batches, ascending."
  [{:keys [kind dir bucket key aws]} gid]
  (sort (keep batch-seq
              (case kind
                :file (some->> (.listFiles (File. (str dir "/" gid))) (map #(.getName ^File %)))
                :s3 (map #(last (str/split % #"/")) (s3/list-keys aws bucket (s3/join-key key (str gid "/"))))))))

(defn- read-batch [{:keys [kind dir bucket key aws]} gid n]
  (let [text (case kind
               :file (slurp (str dir "/" gid "/" (batch-name n)) :encoding "UTF-8")
               :s3 (String. ^bytes (:bytes (s3/get-object aws bucket (s3/join-key key (str gid "/" (batch-name n))) nil))
                            StandardCharsets/UTF_8))]
    (into [] (comp (remove str/blank?) (map edn/read-string)) (str/split-lines text))))

(defn- write-batch! [{:keys [kind dir bucket key aws]} gid n events]
  (let [text (str (str/join "\n" (map pr-str events)) "\n")]
    (case kind
      :file (let [f (io/file dir gid (batch-name n))
                  tmp (io/file dir gid (str "." (batch-name n) ".tmp"))]
              (io/make-parents f)
              ;; written aside, then renamed: a half-written batch is never read
              (spit tmp text :encoding "UTF-8")
              (.renameTo tmp f))
      :s3 (s3/put-object! aws bucket (s3/join-key key (str gid "/" (batch-name n)))
                          (.getBytes text StandardCharsets/UTF_8) "application/edn; charset=utf-8"))))

(defn- graph-ids [{:keys [kind dir bucket key aws]}]
  (sort (filter valid-id?
                (case kind
                  :file (some->> (.listFiles (File. ^String dir)) (filter #(.isDirectory ^File %)) (map #(.getName ^File %)))
                  :s3 (distinct (keep #(let [rel (subs % (count (s3/join-key key "")))
                                             [g f] (str/split rel #"/" 2)]
                                         (when (and g f (batch-seq f)) g))
                                      (s3/list-keys aws bucket (s3/join-key key ""))))))))

;; --- what the server knows of a graph -----------------------------------------------

(defn- meta-of
  "What the list shows, read off the events: the title and ontology a
   graph-set last gave, how many events, the last one's time."
  [m events]
  (reduce (fn [m e]
            (let [sets (for [[op a] (:ops e) :when (= :graph-set op)] a)
                  last-set (fn [k] (some #(when (contains? % k) [k (get % k)]) (reverse sets)))]
              (cond-> (-> m (update :events (fnil inc 0)) (update :updated #(max (or % 0) (or (:ev/ts e) 0))))
                (last-set :title) (assoc :title (second (last-set :title)))
                (last-set :ontology) (assoc :ontology (second (last-set :ontology))))))
          m events))

(defonce ^:private graphs (atom {}))   ; gid -> {:seq :ids #{} :meta {} :batches [[seq events] …]}
(defonce ^:private locks (atom {}))

(defn- lock-of [gid] (or (get @locks gid) (get (swap! locks update gid #(or % (Object.))) gid)))

(defn- loaded
  "The graph as stored, loaded once per process."
  [st gid]
  (or (get @graphs gid)
      (let [batches (vec (for [n (list-batches st gid)] [n (read-batch st gid n)]))
            all (mapcat second batches)
            g {:seq (or (first (last batches)) 0)
               :ids (set (keep :ev/id all))
               :meta (meta-of {:id gid} all)
               :batches batches}]
        (swap! graphs assoc gid g)
        g)))

;; --- the API -----------------------------------------------------------------------

(defn list-graphs
  "Every stored graph: {:id :title :ontology :events :updated :seq}."
  []
  (let [st (store)]
    (vec (for [gid (graph-ids st)
               :let [g (locking (lock-of gid) (loaded st gid))]]
           (assoc (:meta g) :seq (:seq g))))))

(defn events-after
  "{:seq :events} — every event stored after batch `after` (0: the whole graph)."
  [gid after]
  (let [st (store)
        g (locking (lock-of gid) (loaded st gid))]
    {:seq (:seq g)
     :events (vec (mapcat second (filter #(> (first %) (or after 0)) (:batches g))))}))

(defn append!
  "Store the events of a POST as one batch (those already known skipped) and
   answer {:seq :stored :events}: the new seq, how many were stored, and
   every OTHER event after the client's `after` — what it has not seen."
  [gid events after]
  (let [st (store)]
    (locking (lock-of gid)
      (let [g (loaded st gid)
            fresh (vec (distinct (filter #(and (map? %) (string? (:ev/id %)) (not ((:ids g) (:ev/id %)))) events)))
            g (if (seq fresh)
                (let [n (inc (:seq g))]
                  (write-batch! st gid n fresh)
                  (-> g
                      (assoc :seq n)
                      (update :ids into (map :ev/id fresh))
                      (update :meta meta-of fresh)
                      (update :batches conj [n fresh])))
                g)
            sent (set (keep :ev/id events))]
        (swap! graphs assoc gid g)
        {:seq (:seq g)
         :stored (count fresh)
         :events (vec (remove #(sent (:ev/id %)) (mapcat second (filter #(> (first %) (or after 0)) (:batches g)))))}))))
