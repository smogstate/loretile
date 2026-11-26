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
   has never synced: its whole log goes up with its first push — and stays
   on the device when that push fails (it is local work, not a provisional
   edit: taking it back emptied a demo or an imported graph on a blip)."
  (:require [cljs.reader :as reader]
            [loretile.fold :as fold]
            [loretile.graph-store :as gs]
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

(defn- fetch-text [path]
  (-> (js/fetch (str "api/graphs" path))
      (p/then (fn [^js r] (if (.-ok r) (.text r) (p/rejected (ex-info (str "HTTP " (.-status r)) {:status (.-status r)})))))))

(defn- seed-from-rows!
  "The open graph built from the server's rows (`rows`: one JSON string, the
   projection's current rows) when they hold exactly its log `evs` — no
   replay of the log in this browser (wiki/Graph-Store.md). true when seeded."
  [evs rows]
  (let [^js d (js/JSON.parse rows)]
    (and (= (.-head d) (count evs)) (pos? (count evs)) (= (.-lastId d) (:ev/id (peek evs)))
         (store/seed-kg! evs (gs/kg-from-dump rows)))))

(defn open-server-graph!
  "Bring a server graph onto this device and open it: its whole log (as
   JSON — parsed natively, ~2.5× faster than EDN), confirmed up to the
   server's seq, and its graph built from the server's rows, fetched at the
   same time, instead of replaying the log here. Promise of the id."
  [{:keys [id] :as meta}]
  (let [path (str "/" (js/encodeURIComponent id))]
    (p/let [[text rows] (p/all [(fetch-text (str path "?after=0&format=json"))
                                (p/catch (fetch-text (str path "/rows")) (constantly nil))])
            ^js d (js/JSON.parse text)
            events (persistent! (reduce (fn [v e] (conj! v (gs/decode-js e))) (transient []) (.-events d)))]
      ;; the entry FIRST: opening the graph fires the open-graph watch, whose
      ;; sync with no entry fetched the whole log again (as EDN, 2.5× slower)
      (let [prev (get @sync-state id)]
        (swap! sync-state assoc id {:seq (.-seq d) :confirmed (count events)})
        (if (store/add-server-graph! meta events)
          (do (save!)
              (when rows (try (seed-from-rows! (store/graph-events id) rows)
                              (catch :default e (js/console.warn "loretile: graph from the server's rows failed:" (ex-message e)))))
              id)
          (do (swap! sync-state (fn [st] (if prev (assoc st id prev) (dissoc st id)))) nil))))))

(defonce ^:private booted (atom nil))   ; how this boot built the graph: :rows | nil (folded here)

(defn boot-kg!
  "Boot: the open graph built from the server's rows — ANY size (wiki/Graph-Store.md
   § Backend mode: the page's graph is the server's projection, not a refold)
   — when everything here is confirmed and the rows hold exactly it. Else
   (a never-synced graph, a log behind the server, no answer) the page folds
   it as before and sync catches up. A promise."
  []
  (let [gid @store/current
        evs @store/events
        {:keys [confirmed]} (entry gid)]
    (if (and enabled? (pos? (count evs)) (= confirmed (count evs)))
      (-> (p/let [rows (fetch-text (str "/" (js/encodeURIComponent gid) "/rows"))]
            (when (seed-from-rows! evs rows) (reset! booted :rows)))
          (p/catch (fn [_] nil)))
      (p/resolved nil))))

;; the checks read how the graph was built at boot
(set! (.-loretileBootedFrom js/window) (fn [] (some-> @booted name)))

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
        ;; `known`, never `seq`: a local named seq shadowed clojure.core/seq and
        ;; `(seq others)` below threw after EVERY stored push — the edits were
        ;; taken back as «unreachable» until the next pull restored them
        ;; (2026-11-24 → 2026-11-26)
        {known :seq confirmed :confirmed} (entry gid)
        tail (subvec log (min confirmed (count log)))]
    (if (empty? tail)
      0
      (p/let [{pushed-seq :seq events :events} (api-post (str "/" (js/encodeURIComponent gid) "/events") {:events tail :after known})]
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
   one cycle per graph at a time (an edit during one asks for another).
   `push-only?` (an edit): no pull first — ONE request, the POST's answer
   already brings what others stored (wiki/Graph-Store.md § Backend mode)."
  [gid & [{:keys [push-only?]}]]
  (cond
    (not enabled?) (p/resolved nil)
    (@busy gid) (do (swap! again conj gid) (p/resolved nil))
    :else
    (do
      (swap! busy conj gid)
      (swap! status assoc gid :syncing)
      (-> (p/let [pulled (if push-only?
                           0
                           (p/let [{server-seq :seq events :events} (api-get (str "/" (js/encodeURIComponent gid) "?after=" (:seq (entry gid))))]
                             (lay-in! gid events server-seq)))
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
                         ;; out of reach: nothing provisional may stay (no offline) —
                         ;; but a graph never synced keeps its log for the next cycle
                         (let [n (- (count (store/graph-events gid)) (:confirmed (entry gid)))]
                           (js/console.warn "loretile sync" gid (ex-message e))
                           (when (contains? @sync-state gid)
                             (take-back! gid nil)
                             (when (pos? n) (reset! store/last-error {:error :server-unreachable :detail n})))
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
    (reset! store/on-append (fn [gid _] (sync! gid {:push-only? true})))
    (js/setInterval #(sync! @store/current) 15000)
    (.addEventListener js/window "focus" #(sync! @store/current))
    (add-watch store/current ::open (fn [_ _ o n] (when (not= o n) (sync! n))))
    (sync! @store/current)))
