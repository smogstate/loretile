(ns loretile.sync
  "Graphs kept by the server, SERVER-FIRST (wiki/Graph-Store.md, decided
   2026-11-23: persistence is either local or backend). Every edit goes to
   the server at once; here it is provisional until the server confirms it.
   The server's guard judges it on the graph as it stands there — a refusal
   takes the edit back at once and the banner says why; a server out of
   reach takes back every provisional edit (no offline). Others' edits are
   pulled and laid BEFORE the provisional ones; nothing is rebased, nothing
   waits for a person. Only in backend mode with server-kept graphs
   (`:graphs? true` in the server's meta).

   Per graph, in localStorage `loretile-sync`: {gid {:seq n :confirmed k}} —
   the server's batch seq, and how many events of the local log the server
   holds (a prefix). A provisional edit never survives a reload: at boot a
   log longer than its confirmed prefix is cut back. A graph with no entry
   has never synced: its whole log is provisional, and its first push
   uploads it."
  (:require [cljs.reader :as reader]
            [loretile.fold :as fold]
            [loretile.llm :as llm]
            [loretile.store :as store]
            [loretile.sync-merge :as m]
            [promesa.core :as p]
            [reagent.core :as r]))

(def enabled? (boolean (:graphs? llm/backend)))

(def ^:private state-key "loretile-sync")

(defn- read-ls [k d]
  (try (or (some-> (js/localStorage.getItem k) reader/read-string) d) (catch :default _ d)))

(defonce sync-state (r/atom (read-ls state-key {})))
(defonce status (r/atom {}))   ; gid -> :syncing | :ok | :offline — a view, never saved
(defonce ^:private busy (atom #{}))
(defonce ^:private again (atom #{}))   ; graphs edited while a cycle ran: one more cycle
;; what OTHERS did, from the last sync that brought anything (phase C):
;; {:key :items [{:kind :n}]} — vintry's peer pills, gone after 8 s
(defonce activity (r/atom nil))

(defn- show-activity!
  "Others' events `remote` arrived on graph `kg` (as it stood before them)."
  [kg remote]
  (let [items (m/summary kg remote)
        k (random-uuid)]
    (when (seq items)
      (reset! activity {:key k :items (vec (take 5 items))})
      (js/setTimeout #(when (= k (:key @activity)) (reset! activity nil)) 8000))))

(defn- save! [] (store/write! state-key (pr-str @sync-state)))

(defn- entry
  "A graph's sync state. One written by local-first sync ({:seq :pending})
   reads as confirmed up to its first pending edit — the pending ones are
   provisional now, and the next push sends them (the migration)."
  [gid]
  (let [e (get @sync-state gid)]
    (cond
      (nil? e) {:seq 0 :confirmed 0}
      (contains? e :confirmed) e
      :else (let [pend (set (:pending e))]
              {:seq (:seq e) :confirmed (count (take-while #(not (pend (:ev/id %))) (store/graph-events gid)))}))))

;; --- talking to the server ---------------------------------------------------

(defn- api-get [path]
  (-> (js/fetch (str "api/graphs" path))
      (p/then (fn [^js r] (if (.-ok r) (.text r) (p/rejected (ex-info (str "HTTP " (.-status r)) {:status (.-status r)})))))
      (p/then reader/read-string)))

(defn- api-post [path body]
  (-> (js/fetch (str "api/graphs" path) #js {:method "POST" :headers #js {"content-type" "application/edn"}
                                              :body (pr-str body)})
      ;; 422: the server's guard refused the batch — its body names the event and why
      (p/then (fn [^js r] (cond (.-ok r) (.text r)
                                (= 422 (.-status r)) (p/then (.text r) #(p/rejected (ex-info "refused" {:status 422 :body (reader/read-string %)})))
                                :else (p/rejected (ex-info (str "HTTP " (.-status r)) {:status (.-status r)})))))
      (p/then reader/read-string)))

(defn server-graphs
  "Promise of every graph the server keeps: [{:id :title :ontology …}]."
  []
  (api-get ""))

(defn search
  "The server's search over graph `gid` in `mode` (:hybrid by default,
   :semantic, :verbatim — see graph-local/search!): [{:id :class :title :short
   :parent :score}] best first."
  [gid text limit & [mode]]
  (-> (js/fetch (str "api/graphs/" (js/encodeURIComponent gid) "/search?q=" (js/encodeURIComponent text) "&limit=" limit
                     "&mode=" (name (or mode :hybrid))))
      (p/then (fn [^js r] (if (.-ok r) (.json r) (p/rejected (ex-info (str "HTTP " (.-status r)) {})))))
      (p/then (fn [rows] (vec (for [[id class title short parent score] (js->clj rows)]
                                {:id id :class class :title title :short short :parent parent :score score}))))))

(defn open-server-graph!
  "Bring a server graph onto this device and open it: its whole log,
   confirmed up to the server's seq. Promise of the id."
  [{:keys [id] :as meta}]
  (p/let [{server-seq :seq events :events} (api-get (str "/" (js/encodeURIComponent id) "?after=0"))]
    (when (store/add-server-graph! meta events)
      (swap! sync-state assoc id {:seq server-seq :confirmed (count events)})
      (save!)
      id)))

;; --- one cycle: pull what others stored, push what is provisional ---------------

(defn- lay-in!
  "Others' events `remote` (after the server's seq this device knew): the
   log becomes confirmed ++ remote ++ provisional. Returns how many came."
  [gid remote new-seq]
  (let [log (store/graph-events gid)
        {:keys [confirmed]} (entry gid)
        have (set (map :ev/id log))
        remote (vec (remove #(have (:ev/id %)) remote))
        held (subvec log 0 (min confirmed (count log)))]
    (when (seq remote)
      (store/replace-log! gid (-> held (into remote) (into (subvec log (count held)))))
      ;; an ontology graph changed elsewhere: the graphs bound to it here take the new copy
      (when (:ontology-graph? (fold/kg (store/graph-events gid))) (store/sync-bound! gid))
      (when (= gid @store/current) (show-activity! (fold/kg held) remote)))
    (swap! sync-state assoc gid {:seq new-seq :confirmed (+ (count held) (count remote))})
    (save!)
    (count remote)))

(defn- take-back!
  "Provisional events leave the log: `ids`, or every provisional one."
  [gid ids]
  (let [log (store/graph-events gid)
        {:keys [confirmed]} (entry gid)
        drop? (if ids (set ids) (set (map :ev/id (subvec log (min confirmed (count log))))))]
    (when (some #(drop? (:ev/id %)) log)
      (store/replace-log! gid (vec (remove #(drop? (:ev/id %)) log))))))

(defn- push!
  "Send the provisional tail; on success it is confirmed, with others'
   events stored just before it laid in front. Resolves to how many went."
  [gid]
  (let [log (store/graph-events gid)
        {:keys [seq confirmed]} (entry gid)
        tail (subvec log (min confirmed (count log)))]
    (if (empty? tail)
      0
      (p/let [{pushed-seq :seq events :events} (api-post (str "/" (js/encodeURIComponent gid) "/events") {:events tail :after seq})]
        (let [log (store/graph-events gid)          ; edits made meanwhile stay provisional, after
              sent (set (map :ev/id tail))
              have (set (map :ev/id log))
              others (vec (remove #(or (have (:ev/id %)) (sent (:ev/id %))) events))
              held (subvec log 0 (min confirmed (count log)))
              mine (filterv #(sent (:ev/id %)) log)
              later (filterv #(not (sent (:ev/id %))) (subvec log (count held)))]
          (when (seq others)
            (store/replace-log! gid (-> held (into others) (into mine) (into later)))
            (when (= gid @store/current) (show-activity! (fold/kg held) others)))
          (swap! sync-state assoc gid {:seq pushed-seq :confirmed (+ (count held) (count others) (count mine))})
          (save!)
          (count mine))))))

(defn sync!
  "Pull, then push the provisional edits. Promise of {:pulled n :pushed n};
   one cycle per graph at a time (an edit during one asks for another)."
  [gid]
  (cond
    (not enabled?) (p/resolved nil)
    (@busy gid) (do (swap! again conj gid) (p/resolved nil))
    :else
    (do
      (swap! busy conj gid)
      (swap! status assoc gid :syncing)
      (-> (p/let [{server-seq :seq events :events} (api-get (str "/" (js/encodeURIComponent gid) "?after=" (:seq (entry gid))))
                  pulled (lay-in! gid events server-seq)
                  pushed (push! gid)]
            (swap! status assoc gid :ok)
            {:pulled pulled :pushed pushed})
          (p/catch (fn [e]
                     (let [{code :status body :body} (ex-data e)]
                       (if (= 422 code)
                         ;; refused on the graph as it stands there: that edit goes, now;
                         ;; the rest of the batch was not judged — it goes again
                         (do (take-back! gid [(:event body)])
                             (reset! store/last-error {:error :server-refused :detail (:error body)})
                             (swap! again conj gid)
                             (swap! status assoc gid :ok))
                         ;; out of reach: nothing provisional may stay (no offline)
                         (let [n (- (count (store/graph-events gid)) (:confirmed (entry gid)))]
                           (js/console.warn "loretile sync" gid (ex-message e))
                           (take-back! gid nil)
                           (when (pos? n) (reset! store/last-error {:error :server-unreachable :detail n}))
                           (swap! status assoc gid :offline)))
                       nil)))
          (p/finally (fn []
                       (swap! busy disj gid)
                       (when (@again gid) (swap! again disj gid) (sync! gid))))))))

;; --- the loop (vintry's rhythm) ---------------------------------------------------

(defn- cut-back!
  "Boot: a log longer than its confirmed prefix held edits the server never
   confirmed — they go (server-first: unconfirmed is unsaved)."
  []
  ;; only entries this sync wrote: local-first's pending edits are pushed instead
  (doseq [[gid {:keys [confirmed]}] @sync-state
          :let [log (store/graph-events gid)]
          :when (and confirmed (> (count log) confirmed))]
    (store/replace-log! gid (subvec log 0 confirmed))))

(defn start!
  "Wire the hook and the timers; nothing when the server keeps no graphs."
  []
  (when enabled?
    (js/localStorage.removeItem "loretile-conflicts")   ; local-first's conflict cards: gone with it
    (cut-back!)
    ;; every edit, in any graph, goes to the server now
    (reset! store/on-append (fn [gid _] (sync! gid)))
    (js/setInterval #(sync! @store/current) 15000)
    (.addEventListener js/window "focus" #(sync! @store/current))
    (add-watch store/current ::open (fn [_ _ o n] (when (not= o n) (sync! n))))
    (sync! @store/current)))
