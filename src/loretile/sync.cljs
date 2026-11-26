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
            [clojure.string :as str]
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

;; BACKEND MODE, step 2 (wiki/Graph-Store.md § Backend mode, the clean way):
;; this browser keeps no log. A graph is a SEED — the server's rows at its
;; head — and a suffix of the events since (loretile.store). The seed comes
;; with its batch seq, so the sync entry stands exactly where it stands.
(when enabled? (reset! store/remote? true))

(defonce ^:private seeding (atom {}))   ; gid → the seed's fetch in flight

(defn- fetch-seed!
  "Graph `gid`'s seed — the server's rows — and its sync entry at exactly
   that head. A promise; one fetch per graph at a time."
  [gid]
  (or (get @seeding gid)
      (let [pr (-> (p/let [rows (fetch-text (str "/" (js/encodeURIComponent gid) "/rows"))
                           ^js d (js/JSON.parse rows)]
                     (when (store/seed! gid (gs/kg-from-dump rows) (.-head d) (.-lastId d))
                       (swap! sync-state assoc gid {:seq (.-seq d) :confirmed (.-head d)})
                       (save!))
                     true)
                   (p/finally #(swap! seeding dissoc gid)))]
        (swap! seeding assoc gid pr)
        pr)))

(defn- held-count
  "How many events of graph `gid`'s suffix `log` the server holds."
  [gid log]
  (min (count log) (max 0 (- (:confirmed (entry gid)) (store/seed-head gid)))))

(defn- fetch-log!
  "Graph `gid`'s whole log: the server's, then this browser's unconfirmed events."
  [gid]
  (p/let [{evs :events} (api-get (str "/" (js/encodeURIComponent gid) "?after=0"))]
    (let [log (store/graph-events gid)
          have (set (map :ev/id evs))]
      (into (vec evs) (remove #(have (:ev/id %)) (subvec log (held-count gid log)))))))

;; a graph folded from a FILTERED log, by the server (…/view): what a learner
;; pinned at a publication sees, the changes since — keyed by the graph's
;; newest event, so an edit or a republish asks again
(defonce ^:private view-cache (r/atom {}))   ; [gid params last-id] → {:kg :cut :newest :changes}
(defonce ^:private view-asked (atom #{}))

(defn view!
  "Promise of the server's fold of graph `gid`'s log filtered by `params`
   ({:course :pin} or {:valid :recorded}): {:kg :cut :newest :changes}."
  [gid params]
  (p/let [text (fetch-text (str "/" (js/encodeURIComponent gid) "/view?"
                                (str/join "&" (for [[k v] params :when v] (str (name k) "=" (js/encodeURIComponent v))))))
          ^js d (js/JSON.parse text)]
    {:kg (gs/kg-from-dump text) :cut (.-cut d) :newest (.-newest d) :changes (.-changes d)}))

(defn view
  "`view!`, cached: the answer, or nil while it is on its way (asked for once)."
  [gid params]
  (let [k [gid params (store/last-id-of gid)]]
    (or (get @view-cache k)
        (do (when-not (@view-asked k)
              (swap! view-asked conj k)
              (-> (view! gid params)
                  (p/then #(swap! view-cache assoc k %))
                  (p/catch (fn [e] (swap! view-asked disj k) (js/console.warn "loretile: a view failed:" (ex-message e))))))
            nil))))

(defn open-server-graph!
  "Bring a server graph onto this device and open it: its seed (the
   server's rows) — no log is fetched or kept here. Promise of the id."
  [{:keys [id] :as meta}]
  (p/let [_ (fetch-seed! id)]
    (when (store/add-server-graph! meta []) id)))

(defonce ^:private booted (atom nil))   ; how this boot built the graph: :rows | nil

(defn boot-kg!
  "Boot: the open graph's seed from the server's rows. A promise."
  []
  (if enabled?
    (-> (fetch-seed! @store/current) (p/then #(reset! booted :rows)) (p/catch (fn [_] nil)))
    (p/resolved nil)))

;; the checks read how the graph was built at boot, the log as the server holds
;; it (plus what is unconfirmed here), and whether the open graph is the server's
(set! (.-loretileBootedFrom js/window) (fn [] (some-> @booted name)))
(when enabled?
  (set! (.-loretileLog js/window) (fn [id] (p/then (fetch-log! (or id @store/current)) pr-str)))
  (set! (.-loretileAppGraphIsServers js/window)
        (fn []
          (p/let [rows (fetch-text (str "/" (js/encodeURIComponent @store/current) "/rows"))]
            (let [norm (fn [kg] (into {} (remove (fn [[k v]] (or (nil? v) (#{::fold/eidx ::fold/nbr} k)))) kg))]
              (= (norm (gs/kg-from-dump rows)) (norm (store/kg))))))))

;; --- one cycle: pull what others stored, push what is provisional ---------------

(defn- lay-in!
  "Others' events `remote` (after the server's seq this device knew): the
   log becomes confirmed ++ remote ++ provisional. Returns how many came."
  [gid remote new-seq]
  (let [log (store/graph-events gid)
        h (held-count gid log)
        have (set (map :ev/id log))
        remote (vec (remove #(have (:ev/id %)) remote))
        held (subvec log 0 h)]
    (when (seq remote)
      (store/replace-log! gid (-> held (into remote) (into (subvec log h))))
      ;; an ontology graph changed elsewhere: the graphs bound to it here take the new copy
      (when (:ontology-graph? (store/kg-of gid)) (store/sync-bound! gid))
      (when (= gid @store/current) (show-activity! (store/kg-before gid h) remote)))
    (swap! sync-state assoc gid {:seq new-seq :confirmed (+ (store/seed-head gid) h (count remote))})
    (save!)
    (count remote)))

(defn- take-back!
  "Provisional events leave the log: `ids`, or every provisional one."
  [gid ids]
  (let [log (store/graph-events gid)
        drop? (if ids (set ids) (set (map :ev/id (subvec log (held-count gid log)))))]
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
        {known :seq} (entry gid)
        tail (subvec log (held-count gid log))]
    (if (empty? tail)
      0
      (p/let [{pushed-seq :seq events :events} (api-post (str "/" (js/encodeURIComponent gid) "/events") {:events tail :after known})]
        (let [log (store/graph-events gid)          ; edits made meanwhile stay provisional, after
              sent (set (map :ev/id tail))
              have (set (map :ev/id log))
              others (vec (remove #(or (have (:ev/id %)) (sent (:ev/id %))) events))
              held (subvec log 0 (held-count gid log))
              mine (filterv #(sent (:ev/id %)) log)
              later (filterv #(not (sent (:ev/id %))) (subvec log (count held)))]
          (when (seq others)
            (store/replace-log! gid (-> held (into others) (into mine) (into later)))
            (when (= gid @store/current) (show-activity! (store/kg-before gid (count held)) others)))
          (swap! sync-state assoc gid {:seq pushed-seq :confirmed (+ (store/seed-head gid) (count held) (count others) (count mine))})
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
    ;; no seed yet: events pulled now would land before it — the seed comes first
    (nil? (get @store/seeds gid)) (do (fetch-seed! gid) (p/resolved nil))
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
                         (let [log (store/graph-events gid)
                               n (- (count log) (held-count gid log))]
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

(defn start!
  "Wire the hook and the timers; nothing when the server keeps no graphs."
  []
  (when enabled?
    (js/localStorage.removeItem "loretile-conflicts")   ; local-first's conflict cards: gone with it
    ;; the store asks here for what the server holds: a graph's seed, its whole log
    (reset! store/fetch-seed fetch-seed!)
    (reset! store/fetch-log fetch-log!)
    ;; the past view: the server folds the log as it stood (…/view)
    (add-watch store/as-of ::view
               (fn [_ _ _ at]
                 (if (nil? at)
                   (reset! store/views {})
                   (let [gid @store/current]
                     (when-not (get @store/views [gid at])
                       (-> (fetch-text (str "/" (js/encodeURIComponent gid) "/view?"
                                            (str/join "&" (for [[k v] at :when v] (str (name k) "=" v)))))
                           (p/then #(swap! store/views assoc [gid at] (gs/kg-from-dump %)))
                           (p/catch (fn [e] (js/console.warn "loretile: the past view failed:" (ex-message e))))))))))
    ;; every edit, in any graph, goes to the server now
    (reset! store/on-append (fn [gid _] (sync! gid {:push-only? true})))
    (js/setInterval #(sync! @store/current) 15000)
    (.addEventListener js/window "focus" #(sync! @store/current))
    (add-watch store/current ::open (fn [_ _ o n] (when (not= o n) (sync! n))))
    (sync! @store/current)))
