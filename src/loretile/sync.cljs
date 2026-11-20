(ns loretile.sync
  "Graphs kept by the server, local-first (wiki/Graph-Sync.md, phase B).
   Every edit is stored in the browser first and noted as PENDING; a sync
   cycle pulls what others stored, lays the pending edits on top
   (`sync-merge/rebase`), holds back what clashes as a CONFLICT for a
   person, and pushes the rest. Only in backend mode with server-kept
   graphs (`:graphs? true` in the server's meta).

   Per graph, in localStorage `loretile-sync`: {gid {:seq n :pending [ev-id…]}}
   — a graph with no entry has never synced: all its events are pending
   (its first push uploads it). Conflicts in `loretile-conflicts`: {gid [c…]}."
  (:require [cljs.reader :as reader]
            [loretile.fold :as fold]
            [loretile.llm :as llm]
            [loretile.store :as store]
            [loretile.sync-merge :as m]
            [promesa.core :as p]
            [reagent.core :as r]))

(def enabled? (boolean (:graphs? llm/backend)))

(def ^:private state-key "loretile-sync")
(def ^:private conflicts-key "loretile-conflicts")

(defn- read-ls [k d]
  (try (or (some-> (js/localStorage.getItem k) reader/read-string) d) (catch :default _ d)))

(defonce sync-state (r/atom (read-ls state-key {})))
(defonce conflicts (r/atom (read-ls conflicts-key {})))
(defonce status (r/atom {}))   ; gid -> :syncing | :ok | :offline — a view, never saved
(defonce ^:private busy (atom #{}))
;; what OTHERS did, from the last sync that brought anything (phase C):
;; {:key :items [{:kind :n :hot?}]} — vintry's peer pills, gone after 8 s
(defonce activity (r/atom nil))

(defn- show-activity!
  "Others' events `remote` arrived on graph `kg` (as it stood before them);
   `waiting` new conflicts came with them."
  [kg remote waiting]
  (let [items (cond->> (m/summary kg remote)
                (pos? waiting) (cons {:kind :conflicts :n waiting :hot? true}))
        k (random-uuid)]
    (when (seq items)
      (reset! activity {:key k :items (vec (take 5 items))})
      (js/setTimeout #(when (= k (:key @activity)) (reset! activity nil)) 8000))))

(defn- save! []
  (store/write! state-key (pr-str @sync-state))
  (store/write! conflicts-key (pr-str @conflicts)))

(defn- entry
  "A graph's sync state; never synced = every event pending."
  [gid]
  (or (get @sync-state gid)
      {:seq 0 :pending (mapv :ev/id (store/graph-events gid))}))

(defn note-local!
  "An event stored in this browser: pending until a push confirms it."
  [gid e]
  (when enabled?
    (swap! sync-state assoc gid (update (entry gid) :pending #(conj (vec %) (:ev/id e))))
    (save!)))

(defn graph-conflicts [gid] (get @conflicts gid))

;; --- talking to the server ---------------------------------------------------

(defn- api-get [path]
  (-> (js/fetch (str "api/graphs" path))
      (p/then (fn [^js r] (if (.-ok r) (.text r) (p/rejected (ex-info (str "HTTP " (.-status r)) {:status (.-status r)})))))
      (p/then reader/read-string)))

(defn- api-post [path body]
  (-> (js/fetch (str "api/graphs" path) #js {:method "POST" :headers #js {"content-type" "application/edn"}
                                              :body (pr-str body)})
      (p/then (fn [^js r] (if (.-ok r) (.text r) (p/rejected (ex-info (str "HTTP " (.-status r)) {:status (.-status r)})))))
      (p/then reader/read-string)))

(defn server-graphs
  "Promise of every graph the server keeps: [{:id :title :ontology …}]."
  []
  (api-get ""))

;; --- one cycle -----------------------------------------------------------------

(defn open-server-graph!
  "Bring a server graph onto this device and open it: its whole log, synced
   up to the server's seq. Promise of the id."
  [{:keys [id] :as meta}]
  (p/let [{server-seq :seq events :events} (api-get (str "/" (js/encodeURIComponent id) "?after=0"))]
    (when (store/add-server-graph! meta events)
      (swap! sync-state assoc id {:seq server-seq :pending []})
      (save!)
      id)))

(defn- lay-in!
  "Others' events `remote` arrived: the log becomes confirmed ++ remote ++
   what still goes; clashing pending edits become conflicts."
  [gid remote new-seq]
  (let [log (store/graph-events gid)
        have (set (map :ev/id log))
        remote (vec (remove #(have (:ev/id %)) remote))
        {:keys [pending]} (entry gid)
        pend (set pending)
        confirmed (vec (remove #(pend (:ev/id %)) log))
        mine (vec (filter #(pend (:ev/id %)) log))]
    (if (empty? remote)
      (swap! sync-state assoc gid {:seq new-seq :pending (vec pending)})
      (let [theirs (into confirmed remote)
            {:keys [keep] :as r} (m/rebase (fold/kg theirs) remote mine)
            before (fold/kg confirmed)
            ;; a removal: what bringing the thing back would write is computed NOW,
            ;; from the graph before the others' events — a card cannot carry a graph
            found (mapv (fn [c] (cond-> (assoc c :id (str (random-uuid)) :at (js/Date.now))
                                  (= :removed (:kind c))
                                  (assoc :restore (vec (mapcat (fn [[kind id]] (when (= :node kind) (fold/restore-node-ops before id)))
                                                               (:gone c))))))
                        (:conflicts r))]
        (store/replace-log! gid (into theirs keep))
        ;; an ontology graph changed elsewhere: the graphs bound to it here take the new copy
        (when (:ontology-graph? (fold/kg (store/graph-events gid))) (store/sync-bound! gid))
        (when (= gid @store/current) (show-activity! before remote (count found)))
        (swap! sync-state assoc gid {:seq new-seq :pending (mapv :ev/id keep)})
        (when (seq found) (swap! conflicts update gid (fnil into []) found))))
    (save!)
    (count remote)))

(defn sync!
  "Pull, lay pending edits on top, push what goes. Promise of
   {:pulled n :pushed n}; one cycle per graph at a time."
  [gid]
  (if (or (not enabled?) (@busy gid))
    (p/resolved nil)
    (do
      (swap! busy conj gid)
      (swap! status assoc gid :syncing)
      ;; `server-seq`, never `seq`: a local named seq shadowed clojure.core/seq
      ;; below and every push failed (sync-check, 2026-11-20)
      (-> (p/let [{server-seq :seq events :events} (api-get (str "/" (js/encodeURIComponent gid) "?after=" (:seq (entry gid))))
                  pulled (lay-in! gid events server-seq)
                  {:keys [pending] :as en} (entry gid)
                  pend (set pending)
                  mine (vec (filter #(pend (:ev/id %)) (store/graph-events gid)))
                  pushed (if (empty? mine)
                           0
                           (p/let [{pushed-seq :seq events :events} (api-post (str "/" (js/encodeURIComponent gid) "/events")
                                                                              {:events mine :after (:seq en)})]
                             ;; others stored just before mine (a race): theirs first in the
                             ;; server's order, mine after — and a card where mine overrode theirs
                             (let [log (store/graph-events gid)
                                   have (set (map :ev/id log))
                                   others (vec (remove #(have (:ev/id %)) events))
                                   sent (set (map :ev/id mine))
                                   confirmed (vec (remove #(sent (:ev/id %)) log))
                                   over (m/overridden others mine)]
                               (when (seq others)
                                 (store/replace-log! gid (-> confirmed (into others) (into mine)))
                                 (when (= gid @store/current) (show-activity! (fold/kg confirmed) others (count over))))
                               (when (seq over)
                                 (swap! conflicts update gid (fnil into [])
                                        (mapv #(assoc % :id (str (random-uuid)) :at (js/Date.now)) over)))
                               (swap! sync-state assoc gid {:seq pushed-seq :pending (vec (remove sent (:pending (entry gid))))})
                               (save!)
                               (count mine))))]
            (swap! status assoc gid :ok)
            {:pulled pulled :pushed pushed})
          (p/catch (fn [e] (js/console.warn "loretile sync" gid (ex-message e) e) (swap! status assoc gid :offline) nil))
          (p/finally #(swap! busy disj gid))))))

;; --- a person decides ----------------------------------------------------------

(defn- drop-conflict! [gid id]
  (swap! conflicts update gid (fn [cs] (vec (remove #(= id (:id %)) cs))))
  (save!))

(defn resolve!
  "`choice` for conflict `c` of the open graph: :mine — my edit is sent
   after theirs (for a removal: the thing is brought back first); :theirs —
   mine is dropped (for `:overridden`, theirs is written back)."
  [c choice]
  (let [gid @store/current
        mine (:mine c)]
    (case [(:kind c) choice]
      [:same-property :mine] (store/append! (dissoc mine :ev/id :ev/ts :ev/recorded :ev/ns))
      [:removed :mine] (do (when (seq (:restore c)) (store/append! {:ev/type :change :ops (:restore c)}))
                           (store/append! (dissoc mine :ev/id :ev/ts :ev/recorded :ev/ns)))
      [:overridden :theirs] (store/append! {:ev/type :change
                                             :ops (m/ops-for (into {} (for [[ek {:keys [theirs]}] (:clash c)] [ek theirs])))})
      nil)
    (drop-conflict! gid (:id c))
    (sync! gid)))

;; --- the loop (vintry's rhythm) ---------------------------------------------------

(defonce ^:private push-timer (atom nil))

(defn- soon!
  "A push a couple of seconds after an edit."
  []
  (some-> @push-timer js/clearTimeout)
  (reset! push-timer (js/setTimeout #(sync! @store/current) 2000)))

(defn start!
  "Wire the hook and the timers; nothing when the server keeps no graphs."
  []
  (when enabled?
    (reset! store/on-append (fn [gid e] (note-local! gid e) (when (= gid @store/current) (soon!))))
    (js/setInterval #(sync! @store/current) 15000)
    (.addEventListener js/window "focus" #(sync! @store/current))
    (add-watch store/current ::open (fn [_ _ o n] (when (not= o n) (sync! n))))
    (sync! @store/current)))
