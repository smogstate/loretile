(ns loretile.server.genesis
  "A GENESIS LOG from a projection (wiki/Graph-Store.md § Genesis): a graph
   that exists only as a projection — the SQLite tables, no events — gets
   the events that, folded, give exactly it. Read-only on the database;
   proven offline (the events replayed through the guard must fold to the
   database's graph) before anything reaches a server; then posted to a
   running server's events endpoint in batches, where its guard judges them
   again and it builds its own projection — the server stays the one writer.

     clojure -M:genesis --db projection.sqlite3 [--from <graph in the db>]
                        [--graph <id on the server>] [--out log.edn]
                        [--post https://server] [--no-authorship]

   What the fold computes itself and no op may set is reproduced by the
   events' metadata: who added a topic, who changed a topic or an edge last
   and when (each group of writes carries its author and time), versions
   (patch edits), staleness (a source's material update). `--no-authorship`
   writes everything as `genesis`, at import time, in a few hundred events."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [loretile.fold :as fold]
            [loretile.graph-store :as gs])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse HttpResponse$BodyHandlers)
           (java.sql DriverManager)
           (java.time Duration)))

;; --- reading a projection: read-only, the file is never changed ------------------

(defn- with-db [path f]
  (with-open [conn (DriverManager/getConnection (str "jdbc:sqlite:file:" path "?mode=ro"))]
    (f conn)))

(defn- query [conn sql & params]
  (with-open [st (.prepareStatement conn sql)]
    (doseq [[i p] (map-indexed vector params)] (.setObject st (inc i) p))
    (with-open [rs (.executeQuery st)]
      (let [n (.getColumnCount (.getMetaData rs))]
        (loop [out []]
          (if (.next rs) (recur (conj out (mapv #(.getObject rs (int %)) (range 1 (inc n))))) out))))))

(defn graphs-in "The graphs a projection holds." [path]
  (with-db path #(mapv first (query % "SELECT graph FROM heads ORDER BY graph"))))

(defn read-graph
  "Graph `graph` of the projection at `path`, as a folded kg (its current rows)."
  [path graph]
  (with-db path
    (fn [c]
      (gs/rows->kg
       {:nodes (query c "SELECT id, class, data FROM nodes WHERE graph=? AND to_seq IS NULL" graph)
        :edges (query c "SELECT status, data FROM edges WHERE graph=? AND to_seq IS NULL" graph)
        :discarded (map first (query c "SELECT key FROM discarded WHERE graph=?" graph))
        :heads (some-> (ffirst (query c "SELECT props FROM heads WHERE graph=?" graph)) edn/read-string)}))))

;; --- the graph as events ------------------------------------------------------------

(def ^:private managed #{:id :version :changed-by :changed-at :added-by :stale?})
(def ^:private class-is {:sources :source :objects :object :items :item :questions :question :courses :course :cqs :cq})

(defn- plain-edit-evs [edit ids ev batch]
  (map #(ev nil nil (map (fn [id] [:node-set id {}]) %) edit) (partition-all batch ids)))

(defn kg->events
  "The events that fold to `kg`, for graph id `graph`, in an order the guard
   accepts: graph properties and authors; discards (proposed, then removed —
   what records one); sources, topics, proposals, material, courses, own
   competency questions; edges, edge proposals, object links (each with the
   author and time it carries); versions (patch edits); staleness (a
   source's material update, then patch edits for what it must not stale);
   last, each topic's own author and time. → {:events [...] :warnings [...]}."
  [kg {:keys [graph authorship? now batch] :or {authorship? true batch 500}}]
  (let [now (or now (System/currentTimeMillis))
        warnings (atom [])
        warn! #(swap! warnings conj %)
        ev (fn [actor ts ops & [edit]]
             (cond-> {:ev/id (str (random-uuid)) :ev/ns graph :ev/actor (or actor "genesis") :ev/ts (or ts now)
                      :ev/type :change :ops (vec ops)}
               edit (assoc :edit edit)))
        by-stamp (fn [items actor-k ts-k op-of]
                   ;; one group per [author time]: what the fold stamps comes from the event
                   (for [[[a t] xs] (sort-by (comp str key) (group-by (fn [x] (if authorship? [(actor-k x) (ts-k x)] [nil nil])) items))
                         part (partition-all batch xs)]
                     (ev a t (map op-of part))))
        plain (fn [ops] (map #(ev nil now %) (partition-all batch ops)))
        g (:graph kg)
        nodes-of (fn [k] (sort-by key (get kg k)))
        node-ids (set (concat (mapcat #(keys (get kg %)) (keys class-is)) (keys (:concepts kg))
                              (filter string? (keys (:proposals kg)))))
        edge-keys (set (map (juxt :from :to :type) (:edges kg)))
        props-of (fn [n] (apply dissoc n managed))
        ;; 1. the graph and its authors (each author names only themself)
        graph-evs (concat (when (seq (dissoc g :authors)) [(ev nil now [[:graph-set (dissoc g :authors)]])])
                          (for [[id nm] (sort-by key (:authors g))] (ev id now [[:graph-set {:authors {id nm}}]])))
        ;; 2. node discards: a proposal, removed
        discard-ids (sort (filter string? (:discarded kg)))
        discard-evs (plain (mapcat (fn [k] [[:node-set k {:status :proposed :title k}] [:node-remove k]]) discard-ids))
        ;; 3. nodes: sources, topics (by who added them), proposals, material, courses, own cqs
        node-op (fn [cls [id n]] [:node-set id (cond-> (props-of n) cls (assoc :is cls))])
        source-evs (plain (map (partial node-op :source) (nodes-of :sources)))
        concept-evs (by-stamp (nodes-of :concepts) (comp :added-by second) (constantly nil) (partial node-op nil))
        proposal-evs (plain (for [[id p] (sort-by (comp str key) (:proposals kg)) :when (string? id)]
                              [:node-set id (assoc (dissoc p :of :id) :status :proposed)]))
        material-evs (plain (concat (map (partial node-op :object) (nodes-of :objects))
                                    (map (partial node-op :item) (nodes-of :items))
                                    (map (partial node-op :question) (nodes-of :questions))
                                    (map (partial node-op :course) (nodes-of :courses))
                                    (for [[id q] (nodes-of :cqs) :when (not (:inherited? q))] (node-op :cq [id q]))))
        ;; 4. edges (stamped by their event), edge proposals, object links, edge discards
        edge-op (fn [e status] [:edge-set (:from e) (:type e) (:to e)
                                (cond-> (dissoc e :from :to :type :changed-by :changed-at :status) status (assoc :status status))])
        ;; the grounded-in edges the fold derives itself from a node's source
        ;; (a topic's :source-id, an object's :grounded-in) come with the nodes
        ;; topics come before their material, so a topic's derivation is the one that stands
        derived (merge (into {} (for [[_ o] (:objects kg) :when (and (:grounded-in o) (:concept-id o))]
                                  [[(:concept-id o) (:grounded-in o) :grounded-in] {:from (:concept-id o) :to (:grounded-in o) :type :grounded-in :provenance :authored}]))
                       (into {} (for [[id c] (:concepts kg) :when (:source-id c)]
                                  [[id (:source-id c) :grounded-in] {:from id :to (:source-id c) :type :grounded-in :provenance :llm-drafted}])))
        own-edges (remove (fn [e] (let [k [(:from e) (:to e) (:type e)]]
                                    (when-let [d (derived k)]
                                      (or (= d e) (do (warn! {:edge k :why "its node derives this edge differently: the derived one stands"}) true)))))
                          (:edges kg))
        edge-evs (by-stamp (sort-by (juxt :from (comp str :type) :to) own-edges) :changed-by :changed-at #(edge-op % nil))
        prop-evs (by-stamp (sort-by (juxt :from (comp str :type) :to) (filter map? (map val (filter (comp vector? key) (:proposals kg)))))
                           :changed-by :changed-at #(edge-op % :proposed))
        link-evs (plain (for [l (sort-by (juxt :from (comp str :type) :to) (:object-links kg))]
                          [:edge-set (:from l) (:type l) (:to l) (dissoc l :from :to :type)]))
        edge-discards (for [[from to type :as k] (sort-by str (filter vector? (:discarded kg)))
                            :let [ok (and (node-ids from) (node-ids to) (not (edge-keys k)))]
                            :when (or ok (do (warn! {:discarded k :why "its ends are gone, or the edge is back: not reproducible"}) false))]
                        [[:edge-set from type to {:status :proposed}] [:edge-remove from type to]])
        edge-discard-evs (plain (apply concat edge-discards))
        ;; 5–6. versions and staleness
        stale? #(true? (:stale? %))
        scope (into {} (for [[sid _] (:sources kg)
                             :let [cs (concat (for [[cid c] (:concepts kg) :when (and (= sid (:source-id c)) (stale? c))] cid)
                                              (for [[_ o] (:objects kg) :when (and (= sid (:grounded-in o)) (stale? o))] (:concept-id o)))]
                             :when (seq cs)]
                         [sid (set cs)]))
        ;; what a material update of these sources stales beyond what is stale: «unstaled» by a patch edit
        over-staled (set (concat (for [[cid c] (:concepts kg) :when (and (get-in scope [(:source-id c) cid]) (not (stale? c)))] cid)
                                 (for [[oid o] (:objects kg) :when (and (get-in scope [(:grounded-in o) (:concept-id o)]) (not (stale? o)))] oid)))
        bumps (into {} (for [k [:concepts :objects :items] [id n] (get kg k)
                             :let [v (dec (or (:version n) 1))
                                   v (if (over-staled id) (dec v) v)]
                             :when (pos? v)]
                         [id v]))
        _ (doseq [id over-staled :when (< (dec (or (:version (some #(get-in kg [% id]) [:concepts :objects])) 1)) 1)]
            (warn! {:node id :why "staled by its source's material update with no version left to clear it: it stays stale"}))
        version-evs (for [r (range 1 (inc (reduce max 0 (vals bumps))))
                          part (partition-all batch (sort (for [[id n] bumps :when (>= n r)] id)))]
                      (ev nil now (map (fn [id] [:node-set id {}]) part) :patch))
        source-updates (for [[sid s] (nodes-of :sources)
                             :let [v (dec (or (:version s) 1)) material (get scope sid)]
                             :when (or (pos? v) (do (when material (warn! {:source sid :why "stale material but no update on record: left as is"})) false))
                             i (range v)
                             :let [last? (= i (dec v))]]
                         [:node-set sid {:updated (cond-> {:change (if (and last? material) :material :patch)}
                                                    (and last? material) (assoc :concepts (vec (sort material))))}])
        source-evs2 (map #(ev nil now [%]) source-updates)
        unstale-evs (plain-edit-evs :patch (sort over-staled) ev batch)
        ;; 7. each topic's own author and time, last
        stamp-evs (when authorship?
                    (by-stamp (nodes-of :concepts) (comp :changed-by second) (comp :changed-at second) (fn [[id _]] [:node-set id {}])))]
    {:events (vec (concat graph-evs discard-evs source-evs concept-evs proposal-evs material-evs
                          edge-evs prop-evs link-evs edge-discard-evs version-evs source-evs2 unstale-evs stamp-evs))
     :warnings @warnings}))

;; --- the proof: replayed through the guard, the events fold to the database's graph -----

(defn- comparable [kg authorship?]
  (let [strip (fn [m] (if authorship? m (into {} (map (fn [[id n]] [id (dissoc n :changed-by :changed-at :added-by)])) m)))]
    (cond-> (into {} (remove (fn [[k v]] (or (nil? v) (#{::fold/eidx ::fold/nbr} k)))) kg)
      (not authorship?) (-> (update :concepts strip)
                            (update :edges #(set (map (fn [e] (dissoc e :changed-by :changed-at)) %)))
                            (update :proposals (fn [ps] (into {} (map (fn [[k p]] [k (if (map? p) (dissoc p :changed-by :changed-at) p)])) ps)))))))

(defn verify
  "Replay `events` through the guard: {:ok? true} when they fold to `kg`, else
   {:refused {event error}} or {:differs [[key in-db from-events] …]}."
  [kg events {:keys [authorship?] :or {authorship? true}}]
  (let [{folded :kg bad :bad err :error} (fold/replay fold/empty-kg events)]
    (if bad
      {:refused {:event (:ev/id bad) :ops (take 3 (:ops bad)) :error err}}
      (let [a (comparable kg authorship?) b (comparable folded authorship?)]
        (if (= a b)
          {:ok? true}
          {:differs (vec (take 20 (for [k (sort-by str (set (concat (keys a) (keys b))))
                                         :when (not= (get a k) (get b k))
                                         :let [x (get a k) y (get b k)]]
                                     [k (if (map? x)
                                          (vec (take 5 (for [id (set (concat (keys x) (keys y))) :when (not= (get x id) (get y id))]
                                                         [id (get x id) (get y id)])))
                                          [x y])])))})))))

;; --- posting to a running server -------------------------------------------------------

(def ^:private http (delay (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofSeconds 20)) .build)))

(defn- send-edn [method url body]
  (let [b (-> (HttpRequest/newBuilder (URI. url)) (.timeout (Duration/ofMinutes 10)))
        b (if body (-> b (.header "Content-Type" "application/edn") (.method method (HttpRequest$BodyPublishers/ofString (pr-str body))))
              (.GET b))
        ^HttpResponse r (.send ^HttpClient @http (.build b) (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode r) :body (.body r)}))

(defn post!
  "The events to graph `graph` on the server at `base`, in batches (its events
   endpoint: it guards, folds and stores each). Refuses a graph id the server
   already holds. Returns how many were stored, or throws with the refusal."
  [base graph events {:keys [per-post] :or {per-post 200}}]
  (let [base (str/replace base #"/+$" "")
        listed (edn/read-string (:body (send-edn "GET" (str base "/api/graphs") nil)))]
    (when (some #(= graph (:id %)) listed)
      (throw (ex-info (str "the server already holds a graph " graph " — choose another --graph") {})))
    (loop [after 0 stored 0 [part & more] (partition-all per-post events)]
      (if-not part
        stored
        (let [{:keys [status body]} (send-edn "POST" (str base "/api/graphs/" graph "/events") {:events (vec part) :after after})]
          (when (not= 200 status) (throw (ex-info (str "the server refused a batch (" status "): " (subs body 0 (min 400 (count body)))) {})))
          (let [{s :seq n :stored} (edn/read-string body)]
            (println (format "  posted %d / %d events" (+ stored (count part)) (count events)))
            (recur s (+ stored n) more)))))))

;; --- the command line -------------------------------------------------------------------

(defn- args->opts [args]
  (loop [[a b & more :as as] args opts {:authorship? true}]
    (cond
      (empty? as) opts
      (= a "--no-authorship") (recur (rest as) (assoc opts :authorship? false))
      (#{"--db" "--from" "--graph" "--out" "--post"} a) (recur more (assoc opts (keyword (subs a 2)) b))
      :else (throw (ex-info (str "unknown argument: " a) {})))))

(defn -main [& args]
  (try
    (let [{:keys [db from graph out post authorship?]} (args->opts args)
          _ (when-not db (throw (ex-info "--db <projection.sqlite3> is required" {})))
          held (graphs-in db)
          from (or from (if (= 1 (count held)) (first held)
                            (throw (ex-info (str "the projection holds " (count held) " graphs: name one with --from " (pr-str held)) {}))))
          graph (or graph from)
          kg (read-graph db from)
          {:keys [events warnings]} (kg->events kg {:graph graph :authorship? authorship?})
          _ (println (format "graph %s: %d topics, %d edges → %d events" from (count (:concepts kg)) (count (:edges kg)) (count events)))
          _ (doseq [w warnings] (println "  warning:" (pr-str w)))
          proof (verify kg events {:authorship? authorship?})]
      (if-not (:ok? proof)
        (do (println "NOT PROVEN — nothing written, nothing posted:")
            (pprint/pprint (dissoc proof :ok?))
            (System/exit 1))
        (do (println "proven: replayed through the guard, the events fold to the database's graph")
            (when out
              (with-open [w (io/writer out)] (doseq [e events] (.write w (str (pr-str e) "\n"))))
              (println "written:" out))
            (when post
              (println "stored on the server:" (post! post graph events {})))
            (System/exit 0))))
    (catch Exception e
      (println "genesis:" (ex-message e))
      (System/exit 1))))
