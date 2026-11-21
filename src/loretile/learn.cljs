(ns loretile.learn
  "The gap loop a learner walks (wiki/MVP.md) as a FEED — the surface the
   wiki settled on: the engine deals cards, narration wraps them, the
   composer is the tutor's door in learn mode and visibly locked in exams
   (Denis 2026-09-08: «chat-like UI where the user can ask after each
   step»). Modes are policy presets over one surface: diagnostic (no
   feedback, no composer) → plan → learn (feedback, tutor open, two-in-a-row
   check) → final (no feedback, locked) → result with debrief → round N+1.

   Attempts are an APPEND-ONLY LOG in localStorage; mastery is a projection
   over it. A RUN is one learning session: its feed, stage, round. Runs are
   kept the way the assistant's sittings are (session.cljs shape): the
   current one autosaves, «start over» shelves it, the palette reopens
   any past one."
  (:require [clojure.string :as str]
            [cljs.reader :as reader]
            [reagent.core :as r]
            [promesa.core :as p]
            [loretile.chat :as chat]
            [loretile.exercise :as ex]
            [loretile.fold :as fold]
            [loretile.i18n :as i18n :refer [t tn]]
            [loretile.icons :as icons]
            [loretile.llm :as llm]
            [loretile.store :as store]
            [loretile.tools :as tools]
            [loretile.tour :as tour]))

;; --- storage ------------------------------------------------------------------

(def ^:private attempts-key "loretile-attempts")
(def ^:private marks-key "loretile-marks")         ; "off" = the learner hid the reader marks
(def ^:private runs-key "loretile-learn-sessions")   ; {:current run :archived [run …]}
(def ^:private max-archived 30)

(defn- read-ls [k default]
  (try (or (some-> (.getItem js/localStorage k) reader/read-string) default)
       (catch :default _ default)))

(defonce attempts (r/atom (read-ls attempts-key [])))
;; :busy? is a moment, not state: saved mid-call, a reload left the run
;; «thinking» for ever with its tutor disabled (review 2026-10-16 A19)
(defonce run (r/atom (some-> (:current (read-ls runs-key {})) (dissoc :busy?))))

(defn- run-id
  "The open run's id — what late work checks before it writes (a local
   `run` shadows the atom inside the exercise card)."
  []
  (:id @run))

(defonce storage-full (r/atom false))

;; Reader marks (`==essence==`, `++example++`) are a LEARNER option: on by
;; default — signaling helps novices most — and remembered on this device.
(defonce marks-on (r/atom (not= "off" (try (.getItem js/localStorage marks-key) (catch :default _ nil)))))

(defn- toggle-marks! []
  (swap! marks-on not)
  (try (.setItem js/localStorage marks-key (if @marks-on "on" "off")) (catch :default _ nil)))

(defn- attempt-key [a] [(:run a) (:ts a) (:item a)])

(defn- merge-attempts
  "Ours plus every attempt another tab wrote that we have not seen, in time
   order — the log is append-only, so the union is the truth."
  [ours stored]
  (let [have (set (map attempt-key ours))]
    (vec (sort-by :ts (into ours (remove #(have (attempt-key %)) stored))))))

(defn- persist!
  "Through store/write! (a refused write reaches the banner — it used to
   stop the attempt log in silence). Another tab's attempts are merged in,
   never written over, and another tab's open run is shelved rather than
   dropped from the shared :current slot (review 2026-10-16 A18). An
   unguarded setItem once threw out of `answer!` mid-exam."
  []
  ;; replaced only when another tab added some: a fresh vector on every
  ;; write re-rendered every reader of the log on every save
  (let [merged (merge-attempts @attempts (read-ls attempts-key []))]
    (when (not= (count merged) (count @attempts)) (reset! attempts merged)))
  (let [stored (read-ls runs-key {})
        other (:current stored)
        archived (cond-> (vec (:archived stored))
                   (and other (not= (:id other) (:id @run))
                        (not-any? #(= (:id other) (:id %)) (:archived stored)))
                   (conj other))]
    (reset! storage-full
            (not (and (store/write! attempts-key (pr-str @attempts))
                      (store/write! runs-key (pr-str (assoc stored :current @run
                                                            :archived (vec (take-last max-archived archived))))))))))

(defn- log!
  "Append-only, so every field that can never be backfilled goes in NOW:
   which item VERSION was seen (publish-and-pin moves item text under
   learners), at which level, under which publication, and whether the
   tutor had been consulted on this topic before the answer (review D14)."
  [a]
  (swap! attempts conj
         (assoc a :ts (js/Date.now) :course (:course @run) :run (:id @run)
                :graph (:graph @run) :round (:round @run) :pin (:pin @run)
                ;; «assisted» = the learner asked the tutor about this topic since
                ;; its last check (review 2026-10-02 #6). Exams lock the tutor,
                ;; so an exam answer is never assisted.
                :assisted? (and (= :learn (:ctx a)) (boolean (:asked? @run)))))
  (persist!))

(defn- upd! [f & args] (apply swap! run f args) (swap! run assoc :at (js/Date.now)) (persist!))

(defn- say! [entry] (upd! update :feed conj entry))

;; --- sessions (kintale.session shape, for runs) --------------------------------------

(defn- goto-courses! [] (set! js/location.hash "#/courses"))

(defn- shelve!
  "The open run joins the shelf; nothing is deleted."
  []
  (let [all (read-ls runs-key {}) cur @run]
    ;; a refused write keeps the run open — it must not vanish from both
    ;; memory and storage (review 2026-10-16 A18)
    (when (store/write! runs-key
                        (pr-str {:current nil
                                 :archived (vec (take-last max-archived
                                                           (cond-> (vec (:archived all)) cur (conj cur))))}))
      (reset! run nil)
      true)))

(defn past-runs
  "Every learning session the palette can reach, the open one included."
  [kg]
  (let [all (read-ls runs-key {})
        row (fn [r current?]
              {:id (:id r) :at (:at r) :current? current?
               :title (get-in kg [:courses (:course r) :title] (:course r))
               :status (case (:stage r) :result (if (:passed? r) :passed :failed) :open)
               :round (:round r) :course (:course r)})]
    (cond-> (mapv #(row % false) (:archived all))
      @run (conj (row @run true)))))

(defn open-run!
  "Bring a shelved run back; the open one is shelved first."
  [{:keys [id current?]}]
  (when-not current?
    ;; the pick comes off the shelf BEFORE the open run goes on it: with a
    ;; full shelf, shelving first dropped the oldest — the run being opened
    (let [all (read-ls runs-key {})
          pick (first (filter #(= id (:id %)) (:archived all)))]
      (when pick
        (let [shelf (vec (take-last max-archived
                                    (cond-> (vec (remove #(= id (:id %)) (:archived all))) @run (conj @run))))]
          (when (store/write! runs-key (pr-str {:current pick :archived shelf}))
            (reset! run (dissoc pick :busy?))))))))

;; --- the graph side ------------------------------------------------------------

(defn- items-of
  "Items for a concept at the level the course demands (review A1). Dealing
   level-blind meant a professional target was examined with novice
   questions; `level` nil keeps the old behaviour for callers without one.
   A `:generated` item (nobody reviewed it) is dealt only in the learner's
   OWN graph, where their acceptance counts for their own progress (ruling
   2026-10-02); anywhere else it never gates."
  ([kg graph-id cid locale] (items-of kg graph-id cid locale nil))
  ([kg graph-id cid locale level]
   (filter #(and (or (nil? locale) (= locale (:locale %)))
                 (or (nil? level) (= level (:level %)))
                 (or (not= :generated (:origin %)) (store/graph-personal? graph-id)))
           (fold/concept-items kg cid))))

(defn objects-of
  "The material a learner of this course reads on a topic: in the course's
   language, at the topic's level or below — beginner material can help an
   apprentice, professional depth does not belong in a beginner course
   (review 2026-10-02 #9: every object was shown). If the level leaves
   nothing, everything in the language rather than an empty card."
  [kg cid locale level]
  (let [in-lang (filter #(or (nil? locale) (nil? (:locale %)) (= locale (:locale %)))
                        (fold/concept-objects kg cid))
        rank fold/level-rank
        fits (filter #(or (nil? level) (nil? (:level %)) (<= (rank (:level %) 0) (rank level 0))) in-lang)]
    (if (seq fits) fits in-lang)))

(defn- first-in-order
  "The first material in reading order — what a debrief or a course-wide
   tutor shows when it shows one (the author's links decide, 2026-10-07)."
  [kg objs]
  (first (fold/arranged-flat kg objs)))

(defn- prereq-rank
  "Longest prerequisite chain below a concept inside the closure — the
   learning order: rank 0 first."
  [kg closure]
  (let [req (group-by :from (filter #(and (= :requires (:type %)) (closure (:from %)) (closure (:to %)))
                                    (:edges kg)))
        rank (fn rank [id seen]
               (if (seen id) 0
                   (inc (apply max -1 (map #(rank (:to %) (conj seen id)) (req id))))))]
    (into {} (map (fn [id] [id (rank id #{})]) closure))))

(defn- course-scope
  "The course's reach, LEVEL-AWARE: {concept level} for every target and
   every prerequisite, at the depth that target needs it."
  [kg course]
  (let [targets (set (map first (:targets course)))
        levels (fold/requires-closure-levels kg (:targets course))
        closure (set (keys levels))
        locale (:locale course)]
    {:targets targets :closure closure :levels levels :locale locale
     :order (sort-by (juxt (prereq-rank kg closure) identity) closure)}))

;; --- mastery: a projection over the log --------------------------------------------

(defn- counts-at?
  "Evidence at a level counts toward that level and below, never above
   (wiki/Data-Model.md). An attempt logged before levels were recorded counts
   at whatever level is asked — the most generous reading the data allows."
  [level a]
  (or (nil? level) (nil? (:level a))
      (>= (fold/level-rank (:level a) 0) (fold/level-rank level 0))))

(defn- attempts-for
  "Every attempt on this concept IN THIS GRAPH, whichever course it was
   proven in — mastery lives on concepts, not courses (wiki's offering #1).
   The graph qualifies it: concept ids are slugs, unique inside a graph and
   meaningless between them, so «keys» in a database graph must never credit
   «keys» in a security one. Attempts written before graphs existed carry no
   :graph and stay scoped to their own course. With a `level`, only evidence
   that counts at it (review 2026-10-02 #4: a novice pass credited professional).
   An attempt counts for its ITEM's current topic (`fold/attempt-concept`),
   so a split topic keeps every learner's record."
  ([kg graph-id course-id cid] (attempts-for kg graph-id course-id cid nil))
  ([kg graph-id course-id cid level]
   (sort-by :ts (filter #(and (= cid (fold/attempt-concept kg %))
                              (if (:graph %)
                                (= graph-id (:graph %))
                                (= course-id (:course %)))
                              (counts-at? level %))
                        @attempts))))

(defn- mine
  "This learner's attempts in this graph (pre-graph ones: in this course)."
  [graph-id course-id]
  (filter #(if (:graph %) (= graph-id (:graph %)) (= course-id (:course %))) @attempts))

(defn- honest-items
  "The items still ASKABLE to this learner: never one the debrief walked
   through. The close rule sizes its pool from these — counting burned items
   left a topic needing «two different» when only one could be asked, so it
   never closed and never went untested (review 2026-10-02 b)."
  [kg graph-id course-id cid locale level]
  (let [burned (set (keep #(when (:debriefed? %) (:item %)) (mine graph-id course-id)))]
    (remove (comp burned :id) (items-of kg graph-id cid locale level))))

(defn- closes?
  "The learn-mode check rule: two correct in a row — or one, when the
   topic has a single question left to ask honestly."
  [learn-attempts pool]
  (let [need (if (< pool 2) 1 2)
        recent (take-last need learn-attempts)]
    (and (>= (count learn-attempts) need) (every? :correct? recent)
         ;; two DIFFERENT questions: one answered twice proves nothing new
         (= need (count (distinct (map :item recent)))))))

(defn- learn-since-exam
  "Learn attempts that count for «two in a row»: those after the last
   diagnostic or final on the topic — an exam that disproved a topic is not
   undone by one right answer plus an older one (review 2026-10-16 A13)."
  [as]
  (let [t0 (:ts (last (filter #(#{:diagnostic :final} (:ctx %)) as)))]
    (filter #(and (= :learn (:ctx %)) (or (nil? t0) (> (:ts %) t0))) as)))

(defn- known?
  "Diagnostic/final: the LAST unassisted answer decides. Learn: the check
   rule. The most recent verdict of either kind wins. Reads the graph's
   whole history, so a concept proven in another course of the same graph
   counts here."
  [kg graph-id course-id cid locale & [level]]
  (let [as (attempts-for kg graph-id course-id cid level)
        last-exam (last (filter #(#{:diagnostic :final} (:ctx %)) as))
        learn (learn-since-exam as)
        closed (closes? learn (count (honest-items kg graph-id course-id cid locale level)))
        last-learn (last learn)]
    (cond
      (and last-exam last-learn (> (:ts last-learn) (:ts last-exam))) closed
      last-exam (:correct? last-exam)
      :else closed)))

(defn- deal
  "One item for a concept: unseen first, else the one seen longest ago (the
   one-round cooldown as a preference — a strict one would starve a 3-item
   topic inside its first round). NEVER an item the debrief walked through:
   its answer was shown, and it does not come back when the rest run out
   (review 2026-10-02 #3 — the pool used to refill with them). `except` = an
   item that must not be dealt now (the one just answered). Nil when nothing
   honest is left."
  [kg graph-id course-id cid locale & [level except]]
  (let [pool (remove #(= except (:id %)) (honest-items kg graph-id course-id cid locale level))
        seen (into {} (map (fn [a] [(:item a) (:ts a)]) (mine graph-id course-id)))]
    (when (seq pool)
      (or (first (remove #(seen (:id %)) pool))
          (first (sort-by #(seen (:id %)) pool))))))

(defn- askable?
  "Is there an honest question left for this learner? A topic whose every
   question was debriefed has none — it is UNTESTED, not a gap the learner
   could close (review 2026-10-02 #3: it used to re-ask a question whose
   answer had been shown)."
  [kg graph-id course-id cid locale level]
  (some? (deal kg graph-id course-id cid locale level)))

(defn- gaps [kg graph-id course-id {:keys [order locale levels]}]
  (filterv #(and (askable? kg graph-id course-id % locale (levels %))
                 (not (known? kg graph-id course-id % locale (levels %))))
           order))

(defn course-report
  "For the assistant's learner-gaps tool: this learner's standing on a
   course, by the same rules the plan uses — {:known [cid] :gaps [cid]
   :untested [cid]} in prerequisite order. A read, never a write."
  [kg graph-id course]
  (let [scope (course-scope kg course)
        {:keys [order locale levels]} scope
        cid-known? #(known? kg graph-id (:id course) % locale (levels %))
        cid-askable? #(askable? kg graph-id (:id course) % locale (levels %))]
    {:known (filterv cid-known? order)
     :gaps (filterv #(and (cid-askable? %) (not (cid-known? %))) order)
     :untested (filterv #(and (not (cid-askable? %)) (not (cid-known? %))) order)}))

(defn course-progress
  "What the catalog shows per course, derived from the attempt log and the
   run shelf — never stored: {:state :not-started|:in-progress|:passed
   :closed n :total n :round n :pct n}. `passed?` is the best result ever,
   so a passed course stays passed after a curiosity re-run."
  [kg graph-id course]
  (let [course (assoc course :graph-id graph-id)
        cid (:id course)
        all (read-ls runs-key {})
        runs (cond-> (vec (:archived all)) (:current all) (conj (:current all)))
        mine (filter #(= cid (:course %)) runs)
        best (last (sort-by #(or (:pct %) 0) (filter :passed? mine)))
        live (last (remove :passed? mine))
        targets (set (map first (:targets course)))
        closure (fold/requires-closure kg targets)
        locale (:locale course)
        levels (fold/requires-closure-levels kg (:targets course))
        closed (count (filter #(known? kg (:graph-id course) cid % locale (levels %)) closure))]
    {:state (cond best :passed (seq mine) :in-progress :else :not-started)
     ;; `closed` on a course never taken IS the cross-course credit: those
     ;; topics were proven elsewhere in this graph
     :closed closed :total (count closure)
     :round (:round (or live (last mine)))
     :pct (:pct best)
     :runs (reverse (mapv (fn [r] {:id (:id r) :at (:at r) :round (:round r)
                                   :status (case (:stage r) :result (if (:passed? r) :passed :failed) :open)
                                   :current? (= (:id r) (:id (:current all)))})
                          mine))}))

;; --- the engine: stage transitions append to the feed --------------------------------------

(declare enter-plan! offer-final! finish! explain-miss! correct-answer)

(defn- title-of [kg cid] (get-in kg [:concepts cid :title]))

(defn- deal-next!
  "Pop the queue into a check card, or hand over to `done!` when empty."
  [ctx done!]
  (let [[cid item-id] (first (:queue @run))]
    (if cid
      (do (upd! update :queue #(vec (rest %)))
          (say! {:kind :check :ctx ctx :concept cid :item item-id}))
      (done!))))

(defn- learn-topic! [kg course]
  (let [{:keys [gaps learn-at]} @run
        cid (nth gaps learn-at nil)]
    (if (nil? cid)
      (offer-final! kg course)
      (do (upd! assoc :asked? false)   ; a new topic: questions about the last one do not carry over
          (say! {:kind :narration :text (t :learn/learn-t {"{n}" (inc learn-at) "{m}" (count gaps)})})
          (say! {:kind :topic :concept cid})
          ;; the CHECK is not dealt yet: the learner reads the material and may
          ;; ask the tutor, then asks to be checked. The card that credits
          ;; mastery must not sit under an open tutor (wiki/UX.md card rule).
          (say! {:kind :ready :concept cid})))))

(defn- deal-check!
  "Deal the check for the topic being learned; the tutor closes for its life.
   Never the question just answered. When no honest question is left, the
   topic says so and stays a gap — it used to hang without a card."
  [kg course cid]
  (let [lvl (get (:levels (course-scope kg course)) cid)
        ;; «not the one just answered» guards two-in-a-row against counting one
        ;; question twice: only between LEARN checks, and only where a second
        ;; HONEST question exists — the last one left is re-asked, and closes
        last-learn (last (filter #(= :learn (:ctx %)) (attempts-for kg (:graph-id course) (:id course) cid)))
        just (when (>= (count (honest-items kg (:graph-id course) (:id course) cid (:locale course) lvl)) 2)
               (:item last-learn))]
    (if-let [item (deal kg (:graph-id course) (:id course) cid (:locale course) lvl just)]
      (say! {:kind :check :ctx :learn :concept cid :item (:id item)})
      (do (say! {:kind :narration :text (t :learn/no-items-left {"{title}" (title-of kg cid)})})
          (upd! update :learn-at inc)
          (learn-topic! kg course)))))

(defn- enter-plan! [kg course]
  (let [scope (course-scope kg course)
        {:keys [levels locale closure]} scope
        g (gaps kg (:graph-id course) (:id course) scope)
        ;; three kinds, and the plan must not confuse them: PROVEN (the learner
        ;; showed it), GAP (they did not), and UNTESTED — a topic carrying no
        ;; question at the level the course needs. An untested topic used to
        ;; vanish into "the rest you have already shown", which told the
        ;; learner they had demonstrated something nobody ever asked.
        untested (filterv #(and (not (askable? kg (:graph-id course) (:id course) % locale (levels %)))
                                (not (known? kg (:graph-id course) (:id course) % locale (levels %))))
                          closure)
        known (filterv #(known? kg (:graph-id course) (:id course) % locale (levels %)) closure)]
    (upd! assoc :gaps g :learn-at 0)
    (if (seq g)
      (do (say! {:kind :plan :gaps g :known known :untested untested :total (count closure)})
          (upd! assoc :stage :plan))
      (offer-final! kg course))))

(defn- offer-final! [kg course]
  (upd! assoc :stage :final-intro)
  (say! {:kind :final-intro :threshold (or (:pass-threshold course) 80)}))

(defn start! [kg course]
  (let [{:keys [order locale levels]} (course-scope kg course)
        qs (keep #(some->> (deal kg (:graph-id course) (:id course) % locale (levels %)) :id (vector %)) order)]
    (when (or (nil? @run) (shelve!))
    ;; pin the publication this run starts under: the author keeps editing
    ;; at HEAD, this run keeps folding at its cut (wiki «Publish and pin»)
    (reset! run {:id (str (random-uuid)) :course (:id course) :round 1 :stage :diagnostic
                 :graph (:graph-id course) :pin (:pin course)
                 :queue (vec qs) :answered [] :feed [] :at (js/Date.now)})
    (say! {:kind :narration :text (t :learn/diagnostic-lead)})
    (deal-next! :diagnostic #(enter-plan! kg course))
    (tour/maybe-learn-tour!))))

(defn restart-from-head!
  "«Start over»: shelve the run, then start again against the NEWEST
   publication. Reusing the rendered course carried the old pin forward."
  [course-id]
  (let [found (store/find-course course-id)
        evs (store/graph-events (:id found))
        cut (fold/publication-cut evs course-id)
        kg (fold/kg (fold/events-at evs cut))]
    (when-let [course (get-in kg [:courses course-id])]
      (start! kg (assoc course :graph-id (:id found) :pin cut)))))

(defn- start-learning! [kg course]
  (upd! assoc :stage :learn)
  (learn-topic! kg course))

(defn- start-final! [kg course]
  (let [{:keys [targets locale levels]} (course-scope kg course)
        dealt (for [cid (sort targets)]
                [cid (some-> (deal kg (:graph-id course) (:id course) cid locale (levels cid)) :id)])
        qs (vec (for [[cid iid] dealt :when iid] [cid iid]))
        ;; a target with no question at its level cannot be examined. It used
        ;; to disappear, and the percentage was computed over what was left —
        ;; so a pass could certify a target nobody asked about.
        unexamined (vec (for [[cid iid] dealt :when (nil? iid)] cid))]
    (upd! assoc :stage :final :queue qs :answered [] :unexamined unexamined)
    ;; the composer slot already says the assistant is off — no narration twice
    (when (seq unexamined)
      (say! {:kind :narration :text (tn (count unexamined)
                                        [:learn/unexamined-one :learn/unexamined-few :learn/unexamined-many])}))
    (deal-next! :final #(finish! kg course))))

(defn- finish! [kg course]
  (let [{:keys [answered]} @run
        n (count answered) ok (count (filter second answered))
        pct (if (pos? n) (js/Math.round (* 100 (/ ok n))) 0)
        threshold (or (:pass-threshold course) 80)
        ;; a target nobody examined blocks the pass: it used to vanish from
        ;; the percentage, so a pass certified what was never asked
        passed? (and (>= pct threshold) (empty? (:unexamined @run)))]
    ;; every item the debrief is about to walk through is burned for this
    ;; learner: its answer is printed on screen (wiki/UX.md integrity note)
    (let [shown (set (keep (fn [[_ ok? iid]] (when (false? ok?) iid)) answered))]
      (swap! attempts (fn [as] (mapv #(if (and (= (:run %) (:id @run)) (shown (:item %)))
                                        (assoc % :debriefed? true) %) as))))
    (upd! assoc :stage :result :passed? passed? :pct pct)
    (say! {:kind :result :ok ok :n n :pct pct :threshold threshold :passed? passed?
           :unexamined (vec (:unexamined @run))
           :wrong (vec (remove second answered))})))

(defn- next-round!
  "Round N+1 starts on the NEWEST publication (wiki «Publish and pin»: on
   finishing, or a new run, the learner gets the newest cut) — it used to keep
   the old pin, so a learner stuck retaking never saw the author's fixes
   (review 2026-10-02 #11)."
  [kg course]
  (let [gid (:graph-id course)
        evs (store/graph-events gid)
        cut (fold/publication-cut evs (:id course))
        kg2 (if (seq evs) (fold/kg (fold/events-at evs cut)) kg)
        course2 (or (some-> (get-in kg2 [:courses (:id course)]) (assoc :graph-id gid :pin cut)) course)]
    (upd! update :round inc)
    (upd! assoc :pin (:pin course2))
    (say! {:kind :narration :text (t :learn/plan-lead-again)})
    (enter-plan! kg2 course2)))

(defn- answer! [kg course entry correct? choice]
  (let [{:keys [ctx concept item]} entry
        it (get-in kg [:items item])]
    (log! {:item item :concept concept :correct? correct? :ctx ctx
           :item-version (or (:version it) 1) :level (:level it)})
    ;; «assisted» covers questions asked SINCE the last check
    (upd! assoc :asked? false)
    ;; the card remembers what was chosen; only the feed's last card is live
    (upd! update :feed (fn [f] (mapv #(if (= % entry) (assoc % :answered correct? :choice choice) %) f)))
    (case ctx
      :diagnostic (do (upd! update :answered conj [concept correct?])
                      (deal-next! :diagnostic #(enter-plan! kg course)))
      :final (do (upd! update :answered conj [concept correct? item])
                 (deal-next! :final #(finish! kg course)))
      :learn (let [lvl (get (:levels (course-scope kg course)) concept)
                   pool (count (honest-items kg (:graph-id course) (:id course) concept (:locale course) lvl))
                   learn-as (learn-since-exam (attempts-for kg (:graph-id course) (:id course) concept lvl))]
               (say! {:kind :feedback :correct? correct?})
               (cond
                 (closes? learn-as pool)
                 (do (say! {:kind :narration :text (t :learn/topic-closed {"{title}" (title-of kg concept)})})
                     (upd! update :learn-at inc)
                     (learn-topic! kg course))
                 ;; right, but the topic is not closed yet: keep the rhythm,
                 ;; no needless tap
                 correct? (deal-check! kg course concept)
                 ;; wrong: stop, explain unprompted, and never re-ask the item
                 ;; whose answer was just shown
                 :else
                 (do (swap! attempts (fn [as] (mapv #(if (and (= (:run %) (:id @run)) (= item (:item %)))
                                                       (assoc % :debriefed? true) %) as)))
                     (explain-miss! kg course entry choice)))))))

;; --- the tutor: the composer in learn mode --------------------------------------------------

(defn- scope-rule
  "The tutor is a COURSE tutor, not a general assistant: anything outside
   this course's topics is refused, not answered (Denis 2026-09-11). The
   boundary is stated as data — the course's own topic titles — so the
   model is not left to guess where the subject ends."
  [kg course locale]
  (let [titles (keep #(get-in kg [:concepts (first %) :title]) (:targets course))]
    (str
     ;; i18n-ignore
     "\n\nГРАНИЦА ТЕМЫ. Ты отвечаешь ТОЛЬКО на вопросы по этому курсу и его темам: "
     (str/join "; " titles)
     ;; i18n-ignore
     ". Всё остальное — погода, код не по теме, личные советы, просьбы что-то сочинить или перевести, "
     ;; i18n-ignore
     "общие вопросы о мире — вежливо ОТКЛОНИ одной фразой на языке ученика ("
     (name (or locale (:locale course) @i18n/locale))
     ;; i18n-ignore
     ") вида «Это вне курса — спросите меня про <ближайшая тема курса>». Не отвечай на такой вопрос даже частично, "
     ;; i18n-ignore
     "не рассуждай о нём и не предлагай продолжить разговор о нём.")))

(defn- tutor-system
  "Topic-scoped when a gap topic is live; COURSE-scoped otherwise — a
   learner whose diagnostic came back clean has no learn step at all, and
   the tutor must still exist between the checks. Both are fenced to the
   course by `scope-rule`."
  [kg course cid locale]
  (if (nil? cid)
    (let [targets (map first (:targets course))
          ;; only items this course deals: its own language
          stems (mapcat (fn [tid] (map #(or (get-in % [:spec :stem]) (get-in % [:spec :text]))
                                       (items-of kg (:graph-id course) tid (:locale course)))) targets)]
      ;; i18n-ignore
      (str "Ты — репетитор курса «" (:title course) "» на платформе обучения. Темы курса:\n"
           (str/join "\n" (map (fn [tid] (str "- " (get-in kg [:concepts tid :title]) ": "
                                               (get-in kg [:concepts tid :definition])
                                               (when-let [o (first-in-order kg (objects-of kg tid (:locale course) (some (fn [[c l]] (when (= c tid) l)) (:targets course))))]
                                                 (str " // " (fold/plain-text (:text o))))))
                                targets))
           ;; i18n-ignore
           "\n\nПравила: объясняй, переформулируй, приводи примеры, отвечай коротко (до 120 слов). "
           ;; i18n-ignore
           "Проверочные вопросы ученик решает САМ — помогай понять принцип, но НИКОГДА не называй ответ. Их формулировки: "
           (str/join " | " stems)
           ;; i18n-ignore
           "\nОтвечай на языке ученика (" (name (or locale (:locale course) @i18n/locale)) ")."
           (scope-rule kg course locale)))
  (let [c (get-in kg [:concepts cid])
        objs (fold/arranged-flat kg (objects-of kg cid (:locale course) (get (:levels (course-scope kg course)) cid)))
        stems (map #(or (get-in % [:spec :stem]) (get-in % [:spec :text])) (items-of kg (:graph-id course) cid (:locale course)))]
    ;; i18n-ignore: a prompt for the model, not UI copy
    (str "Ты — репетитор на платформе обучения. Ученик изучает тему «" (:title c) "».\n"
         ;; i18n-ignore
         "Определение: " (:definition c) "\n\nУчебные материалы темы (опирайся ТОЛЬКО на них и на общеизвестное):\n"
         (str/join "\n" (map #(str "- [" (name (:kind %)) "] " (fold/plain-text (:text %))) objs))
         ;; i18n-ignore
         "\n\nПравила: объясняй, переформулируй, приводи примеры, отвечай коротко (до 120 слов). "
         ;; i18n-ignore
         "Проверочные вопросы темы ученик решает САМ — если он спрашивает про них, помоги понять принцип, но НИКОГДА не называй ответ. Их формулировки: "
         (str/join " | " stems)
         ;; i18n-ignore
         "\nОтвечай на языке ученика (" (name (or locale (:locale course) @i18n/locale)) ")."
         (scope-rule kg course locale)))))

(defn- check-live?
  "Is an UNANSWERED check card the feed's last actionable entry?"
  []
  (let [live (last (filter #(#{:check :ready :pause :plan :final-intro :result} (:kind %)) (:feed @run)))]
    (and (= :check (:kind live)) (not (contains? live :answered)))))

(defn- say-answer!
  "The right answer, as a line — or, for an exercise, the reference
   solution as code: prose around multi-line code reads as part of it."
  [item right]
  (if (= :exercise (:kind item))
    (do (say! {:kind :tutor :text (t :learn/ex-solution-was)})
        (say! {:kind :code :text right}))
    (say! {:kind :tutor :text (t :learn/why-fallback {"{a}" right})})))

(defn- explain-miss!
  "A wrong answer explains itself: the tutor says why THIS choice is wrong,
   unprompted (Denis 2026-09-11: asking the learner to ask is lame). The
   correct answer is shown too — a learn-mode check is formative, and the
   two-in-a-row rule still has to be earned on other items."
  [kg course entry chosen]
  (let [item (get-in kg [:items (:item entry)])
        cid (:concept entry)
        c (get-in kg [:concepts cid])
        right (correct-answer item)
        stem (or (get-in item [:spec :stem]) (get-in item [:spec :text]))]
    (if-not (llm/ready?)
      ;; no key: still never leave a miss unexplained
      (do (say-answer! item right)
          (say! {:kind :pause :concept cid}))
      (let [rid (:id @run)
            here? #(= rid (:id @run))]
        ;; the explanation belongs to the run it was asked in: after ↺ or
        ;; another run it landed in that one (review 2026-10-16 A8)
        (upd! assoc :busy? true)
        (-> (llm/chat!
             ;; i18n-ignore: a prompt for the model, not UI copy
             (str "Ты — репетитор. Ученик изучает тему «" (:title c) "»: " (:definition c) "\n"
                  ;; i18n-ignore
                  "Материалы темы:\n"
                  (str/join "\n" (map #(str "- " (fold/plain-text (:text %)))
                                       (fold/arranged-flat kg (objects-of kg cid (:locale course) (get (:levels (course-scope kg course)) cid)))))
                  ;; i18n-ignore
                  "\n\nУченик только что ответил на проверочный вопрос НЕВЕРНО. "
                  ;; i18n-ignore
                  "Объясни коротко (до 70 слов), ПОЧЕМУ его вариант неверен и в чём ключевая разница. "
                  ;; i18n-ignore
                  "Не хвали, не извиняйся, не задавай вопросов. Ответь на языке ученика ("
                  (name (or (keyword (:locale course)) @i18n/locale)) ").")
             ;; i18n-ignore
             (str "Вопрос: " stem "\nОтвет ученика: " chosen "\nВерный ответ: " right))
            (p/then (fn [a] (when (here?) (say! {:kind :tutor :text (str/trim (str a))}))))
            (p/catch (fn [_] (when (here?) (say-answer! item right))))
            ;; the way forward comes AFTER the explanation, never before it
            (p/finally (fn [] (when (here?) (upd! assoc :busy? false) (say! {:kind :pause :concept cid})))))))))

(defn- current-topic
  "The live gap topic, only while actually learning it."
  []
  (when (= :learn (:stage @run)) (nth (:gaps @run) (:learn-at @run) nil)))

(def ^:private tutor-tools
  ;; the tutor reads, never writes or moves: two lookups, fenced to its course
  #{"search-nodes" "open-node"})

(defn- tool-request
  "A tutor reply that is exactly {\"tools\": […]} → the calls; else nil (an answer)."
  [raw]
  (let [s (-> (str/trim (str raw)) (str/replace #"(?s)^```(json)?" "") (str/replace #"```\s*$" "") str/trim)]
    (when (str/starts-with? s "{")
      (try (let [m (js->clj (js/JSON.parse s) :keywordize-keys true)]
             (when (sequential? (:tools m)) (:tools m)))
           (catch :default _ nil)))))

(defn- tutor-chat!
  "The tutor may LOOK at its course's topics before answering (wiki/Assistant.md
   slice 3): search and open, fenced to the course's topics and their
   prerequisites; questions are counted, never shown. At most `tools/max-hops`
   rounds; every round inside the token budget. Promise of the answer text."
  [kg course system text]
  ;; the fence is {topic level}: membership AND the level open-node reads at
  (let [ctx {:kg kg :graph-id (:graph-id course) :allowed tutor-tools
             :fence (:levels (course-scope kg course)) :locale (:locale course)}
        system (str system (tools/prompt-block {:only tutor-tools})
                    ;; i18n-ignore
                    "\nЕсли смотреть не нужно — отвечай обычным текстом, не JSON.")]
    (p/let [reply (tools/converse!
                   {:system system :user text :ctx ctx
                    :parse (fn [raw] (if-let [calls (tool-request raw)] {:tools calls} {:text raw}))
                    :on-call (fn [{:keys [name args]}]
                               (say! {:kind :sys :text (t :learn/tutor-looked {"{name}" name "{args}" (js/JSON.stringify (clj->js args))})}))})]
      ;; `{"tools":[]}` asked for nothing: not the limit
      (cond (:text reply) (:text reply)
            (seq (:tools reply)) (t :learn/tutor-tool-limit)
            :else (t :learn/tutor-empty)))))

(defn- ask! [kg course text]
  (let [text (str/trim text) cid (current-topic)]
    (when (and (seq text) (not (:busy? @run)))
      (say! {:kind :user :text text})
      (upd! assoc :busy? true :asked? true)
      (let [rid (:id @run) here? #(= rid (:id @run))]
        (-> (tutor-chat! kg course (tutor-system kg course cid (or (keyword (:locale course)) @i18n/locale)) text)
            (p/then (fn [a] (when (here?) (say! {:kind :tutor :text (str/trim (str a))}))))
            (p/catch (fn [e] (when (here?) (say! {:kind :sys :text (str (t :learn/tutor-error) (ex-message e))}))))
            (p/finally #(when (here?) (upd! assoc :busy? false))))))))

;; --- cards ------------------------------------------------------------------------------------

(defn- origin-label
  "Who stands behind it — promised to learners (Ethics; review 2026-10-02 #8)."
  [x]
  (case (:origin x)
    :drafted (t :ui/origin-drafted)
    :generated (t :ui/origin-generated)
    nil))

(defn- correct-answer [item]
  (let [spec (:spec item)]
    (case (:kind item)
      :cloze (str (:key spec))
      :exercise (:solution spec)
      (nth (:options spec) (:key spec) "—"))))

(defn- result-view
  "What the learner's own code produced — rows for SQL, printed output for
   the others — never the verdict."
  [res]
  (cond
    (nil? res) nil
    (= :tx-control (:error res)) [:pre.ex-out.err (t :learn/ex-no-tx)]
    (= :timeout (:error res)) [:pre.ex-out.err.prose (t :learn/ex-timeout)]
    (and (:error res) (not (contains? res :rows)))
    [:<> (when (seq (:stdout res)) [:pre.ex-out (:stdout res)]) [:pre.ex-out.err (str (:error res))]]
    (not (contains? res :rows))
    (let [out (ex/output-text res)]
      (if (str/blank? out) [:p.ex-out.muted (t :learn/ex-no-output)] [:pre.ex-out out]))
    (empty? (:rows res)) [:p.ex-out.muted (t :learn/ex-empty)]
    :else [:div.ex-out
           [:table
            [:thead [:tr (for [[i c] (map-indexed vector (:columns res))] ^{:key i} [:th c])]]
            [:tbody (for [[i row] (map-indexed vector (take 20 (:rows res)))]
                      ^{:key i} [:tr (for [[j v] (map-indexed vector row)]
                                       ^{:key j} [:td (if (nil? v) "NULL" (str v))])])]]
           (when (> (count (:rows res)) 20)
             [:p.muted (t :learn/ex-more {"{n}" (count (:rows res))})])]))

(defn- exercise-card
  "An item whose answer is EXECUTED (wiki/Exercises.md): the learner writes
   a query, may Run it against the exercise's own data as often as they like
   (in every mode — that is the exercise, not a hint), and answers. The
   verdict compares their rows with the reference solution's."
  [kg course entry live?]
  (let [code (r/atom "") out (r/atom nil) busy (r/atom false)]
    (fn [kg course entry live?]
      (let [item (get-in kg [:items (:item entry)]) spec (:spec item)
            done? (contains? entry :answered)
            live? (and live? (not done?))
            go! (fn [f] (when-not @busy (reset! busy true) (p/finally (f) #(reset! busy false))))
            run (fn [] (go! #(-> (ex/try-run! spec @code)
                                 (p/then (fn [res] (reset! out res)))
                                 (p/catch (fn [e] (reset! out {:error (str (t :learn/ex-engine-failed) (ex-message e))}))))))
            submit (fn [] (when (and live? (seq (str/trim @code)))
                            ;; a verdict (up to a minute on a booting worker) is
                            ;; recorded only in the run it was asked in — after
                            ;; ↺ it went into the NEW run's append-only attempts
                            ;; (review 2026-10-16 A8)
                            (let [rid (run-id)]
                            (go! #(-> (ex/grade! spec @code)
                                      (p/then (fn [{:keys [correct? result]}]
                                                (reset! out result)
                                                (when (= rid (run-id))
                                                  (answer! kg course entry correct? @code))))
                                      ;; an engine failure records nothing: no verdict was earned
                                      (p/catch (fn [e] (reset! out {:error (str (t :learn/ex-engine-failed) (ex-message e))}))))))))]
        [:div.qcard.exercise {:class (when-not live? "done")}
         [:p.qtopic (title-of kg (:concept entry)) (origin-label item)]
         [:p.stem (:stem spec)]
         (when (:schema spec) [:pre.ex-schema (:schema spec)])
         (if done?
           [:pre.ex-code.chosen (:choice entry)]
           [:<>
            [:textarea.ex-code {:default-value "" :spell-check false :rows 4
                                :placeholder (if (= :python (ex/runtime spec)) "print(…)" "SELECT …")
                                :disabled (not live?)
                                :on-change #(reset! code (.. % -target -value))
                                :on-key-down #(when (and (= "Enter" (.-key %)) (or (.-ctrlKey %) (.-metaKey %)))
                                                (.preventDefault %) (run))}]
            [:div.row
             [:button.ex-run {:disabled (or (not live?) @busy) :title (t :learn/ex-run-hint) :on-click run}
              (if @busy "…" (t :learn/ex-run))]
             [:button.primary.ex-submit {:disabled (or (not live?) @busy) :on-click submit} (t :learn/answer)]]])
         [result-view @out]]))))

(defn- check-card
  "The engine's card: live only while it is the feed's last card; afterwards
   it shows what was chosen. Feedback is a separate line, so exams stay silent."
  [kg course entry live?]
  (let [typed (r/atom "")]
    (fn [kg course entry live?]
      (let [item (get-in kg [:items (:item entry)]) spec (:spec item)
            done? (contains? entry :answered)
            live? (and live? (not done?))
            submit! (fn [ok? choice] (when live? (answer! kg course entry ok? choice)))]
        [:div.qcard {:class (when-not live? "done")}
         [:p.qtopic (title-of kg (:concept entry)) (origin-label item)]
         [:p.stem (or (:stem spec) (:text spec))]
         (if (= :cloze (:kind item))
           (if done?
             [:p.chosen (:choice entry)]
             [:div.row
              [:input {:default-value "" :placeholder (t :learn/cloze-placeholder) :disabled (not live?)
                       :on-change #(reset! typed (.. % -target -value))
                       :on-key-down #(when (= "Enter" (.-key %))
                                       (submit! (= (str/lower-case (str/trim @typed)) (str/lower-case (str (:key spec)))) @typed))}]
              [:button.primary {:disabled (not live?)
                                :on-click #(submit! (= (str/lower-case (str/trim @typed)) (str/lower-case (str (:key spec)))) @typed)}
               (t :learn/answer)]])
           [:div.options
            (for [[i o] (map-indexed vector (:options spec))]
              ^{:key i} [:button.opt {:class (when (= o (:choice entry)) "chosen") :disabled (not live?)
                                      :on-click #(submit! (= i (:key spec)) o)} o])])]))))

(defn- topic-material
  "A topic's material in reading order (fold/arrange-objects, 2026-10-07):
   each example right under what it illustrates, simpler versions behind
   «Объяснить проще» — offered, never forced on the learner."
  [_]
  (let [shown (r/atom #{})]
    (fn [entries]
      ;; deref HERE, not inside the lazy `for` below: a deref in a lazy seq is
      ;; not tracked, and the button never re-rendered the card
      (let [sh @shown mk @marks-on]
      [:<>
       (for [{:keys [object examples simpler]} entries]
         ^{:key (:id object)}
         [:div.lo-group
          [:div.lo [:span.lo-kind (i18n/enum-label "okind" (:kind object)) (origin-label object)]
           [:p (fold/marks->hiccup (:text object) mk)]
           (when (seq simpler)
             (let [on? (contains? sh (:id object))]
               [:<>
                [:button.lo-simpler {:on-click #(swap! shown (if on? disj conj) (:id object))}
                 (t (if on? :learn/simpler-hide :learn/simpler))]
                (when on?
                  (for [x simpler]
                    ^{:key (:id x)} [:div.lo.lo-simple [:p (fold/marks->hiccup (:text x) mk)]]))]))]
          (for [x examples]
            ^{:key (:id x)}
            [:div.lo.lo-example [:span.lo-kind (i18n/enum-label "okind" (:kind x)) " · " (t :learn/example-tag) (origin-label x)]
             [:p (fold/marks->hiccup (:text x) mk)]])])]))))

(defn- entry-view [kg course entry live?]
  (case (:kind entry)
    :narration [:div.msg.msg-llm.narr (:text entry)]
    :feedback [:div.msg.msg-sys {:class (if (:correct? entry) "ok" "bad")}
               (if (:correct? entry) (t :learn/right) (t :learn/wrong))]
    :user [:div.msg.msg-user (:text entry)]
    :tutor [:div.msg.msg-llm (:text entry)]
    :code [:pre.msg.ex-code (:text entry)]
    :sys [:div.msg.msg-sys (:text entry)]
    :topic (let [c (get-in kg [:concepts (:concept entry)])]
             [:div.topic-card
              [:h2.qtopic (:title c)] [:p.def (:definition c)]
              (let [os (objects-of kg (:concept entry) (:locale course) (get (:levels (course-scope kg course)) (:concept entry)))]
                [:<>
                 ;; reading order from the author's links (2026-10-07)
                 [topic-material (fold/arrange-objects kg os)]
                 (when (and @marks-on (some #(fold/marked? (:text %)) os))
                   [:p.marks-legend [:mark.mk-essence (t :learn/marks-essence)] " " [:mark.mk-example (t :learn/marks-example)]])])])
    :plan [:div.card.plan-card
           [:h3 (t :learn/plan-t {"{n}" (count (:gaps entry)) "{m}" (:total entry)})]
           [:p.muted (t :learn/plan-lead)]
           (when (seq (:known entry))
             [:p.muted (t :learn/known-list {"{list}" (str/join ", " (map #(title-of kg %) (:known entry)))})])
           ;; never let an untested topic pass for a proven one
           (when (seq (:untested entry))
             [:p.muted.untested (t :learn/untested-list
                                   {"{list}" (str/join ", " (map #(title-of kg %) (:untested entry)))})])
           [:ol.plan (for [cid (:gaps entry)] ^{:key cid} [:li (title-of kg cid)])]
           (when live? [:button.primary {:on-click #(start-learning! kg course)} (t :learn/start-learning)])]
    :final-intro [:div.card
                  [:h3 (t :learn/final-t)]
                  [:p.muted (t :learn/final-lead {"{n}" (:threshold entry)})]
                  (when live? [:button.primary {:on-click #(start-final! kg course)} (t :learn/start-final)])]
    :ready [:div.card.step-card
            [:p.muted (t :learn/check-lead)]
            (when live?
              [:button.primary.check-me {:on-click #(deal-check! kg course (:concept entry))}
               (t :learn/check-me)])]
    :pause [:div.card.step-card
            (when live?
              [:button.primary.continue {:on-click #(deal-check! kg course (:concept entry))}
               (t :learn/next)])]
    :check (if (= :exercise (get-in kg [:items (:item entry) :kind]))
             [exercise-card kg course entry live?]
             [check-card kg course entry live?])
    :result (let [{:keys [ok n pct threshold passed? wrong]} entry]
              [:div.card.result-card
               [:h3 (if passed? (t :learn/passed) (t :learn/failed))]
               [:p.score (t :learn/score {"{ok}" ok "{n}" n "{pct}" pct "{t}" threshold})]
               ;; a recap, not just a number: what the learner can now do
               (when passed?
                 [:p.recap (t :learn/recap {"{list}" (str/join ", " (map #(title-of kg (first %)) (:targets course)))})])
               ;; the learner's own graph: their acceptance counts for them only
               (when (store/graph-personal? (:graph-id course))
                 [:p.muted.self-checked (t :learn/self-checked)])
               ;; the verdict is never shown without its caveat
               (when (seq (:unexamined entry))
                 [:p.untested (t :learn/unexamined-result
                                 {"{n}" (count (:unexamined entry))
                                  "{list}" (str/join ", " (map #(title-of kg %) (:unexamined entry)))})])
               (when (seq wrong)
                 [:<> [:h4 (t :learn/debrief-t)]
                  (for [[cid _ item-id] wrong
                        :let [item (get-in kg [:items item-id])
                              obj (first-in-order kg (objects-of kg cid (:locale course) (get (:levels (course-scope kg course)) cid)))]]
                    ^{:key item-id}
                    [:div.lo [:span.lo-kind (title-of kg cid)]
                     [:p [:b (or (get-in item [:spec :stem]) (get-in item [:spec :text]))]]
                     (if (= :exercise (:kind item))
                       [:<> [:p (t :learn/ex-solution-was)] [:pre.ex-code (correct-answer item)]]
                       [:p (t :learn/correct-was {"{a}" (correct-answer item)})])
                     (when obj [:p.muted (fold/marks->hiccup (:text obj) @marks-on)])])])
               (when live?
                 (if passed?
                   [:a.btn.primary {:href "#/courses"} (t :learn/done)]
                   [:button.primary {:on-click #(next-round! kg course)} (t :learn/next-round {"{n}" (inc (:round @run))})]))])
    nil))

;; --- the feed ---------------------------------------------------------------------------------

;; FOLDED FEED (learning review 2026-11-20 D1: after two rounds a run was one
;; ~15 000 px feed). The feed is drawn as SEGMENTS — the diagnostic, the
;; plan, each topic, each final with its result — and every segment but the
;; live one folds into a line that opens on tap. Nothing in the run changes:
;; segments are read off the feed when it is drawn.

(defn- segments
  "The feed's segments: [{:kind :diagnostic|:plan|:topic|:final :from i :to j}]
   (`:to` exclusive)."
  [feed]
  ;; a segment starts with the narration lines right before it — «Тема
  ;; закрыта», «вопросов не осталось», «Тема 2 из 5», a round's lead — so the
  ;; line that ends one part stays in view as the next one opens (folding it
  ;; with the finished part hid it the moment it was written)
  (let [lead (fn [i] (loop [j i] (if (and (pos? j) (= :narration (:kind (nth feed (dec j))))) (recur (dec j)) j)))
        starts (keep-indexed
                (fn [i e]
                  (case (:kind e)
                    (:plan :topic) [(lead i) (:kind e)]
                    :final-intro [(lead i) :final]
                    nil))
                feed)
        starts (cons [0 :diagnostic] (remove #(zero? (first %)) starts))
        bounds (concat (map first (rest starts)) [(count feed)])]
    (vec (keep (fn [[[from kind] to]] (when (< from to) {:kind kind :from from :to to}))
               (map vector starts bounds)))))

(defn- answered-checks [entries ctx]
  (filter #(and (= :check (:kind %)) (= ctx (:ctx %)) (contains? % :answered)) entries))

(defn- segment-line
  "What a folded segment says in one line."
  [kg {:keys [kind]} entries]
  (case kind
    :diagnostic (let [cs (answered-checks entries :diagnostic)]
                  (t :learn/fold-diagnostic {"{ok}" (count (filter :answered cs)) "{n}" (count cs)}))
    :plan (let [g (count (:gaps (first (filter #(= :plan (:kind %)) entries))))]
            (tn g [:learn/fold-plan-1 :learn/fold-plan-2 :learn/fold-plan-5]))
    :topic (let [cid (:concept (first (filter #(= :topic (:kind %)) entries)))
                 cs (answered-checks entries :learn)]
             (if (seq cs)
               (t :learn/fold-topic {"{title}" (title-of kg cid) "{ok}" (count (filter :answered cs)) "{n}" (count cs)})
               (title-of kg cid)))
    :final (if-let [r (first (filter #(= :result (:kind %)) entries))]
             (t (if (:passed? r) :learn/fold-final-passed :learn/fold-final-failed)
                {"{ok}" (:ok r) "{n}" (:n r) "{pct}" (:pct r)})
             (t :learn/fold-final-open))))

(defonce ^:private opened (r/atom #{}))   ; [run-id from] — folded segments opened by hand

(defonce ^:private confirm-restart (r/atom false))

(defn- head-progress
  "Where the learner is, for the header: stage (and topic n of m) · round."
  [r]
  (str (case (:stage r)
         :diagnostic (t :learn/head-diagnostic)
         :plan (t :learn/head-plan)
         :learn (t :learn/head-learn {"{n}" (min (inc (:learn-at r 0)) (count (:gaps r))) "{m}" (count (:gaps r))})
         (:final-intro :final) (t :learn/head-final)
         :result (t :learn/head-result)
         "")
       " · " (t :learn/round {"{n}" (:round r)})))

(defonce ^:private scrolled-at (atom nil))

(defn- scroll-end!
  "The composer sits AFTER the feed; scroll to it so the last card is never
   under it — only when the feed GREW (or the tutor started thinking), never
   on any redraw: opening a folded part of the run jumped straight back to
   the end, and a tap landed on whatever slid under it (D1, 2026-11-21)."
  [mark]
  (when (not= mark @scrolled-at)
    (reset! scrolled-at mark)
    (r/after-render #(when-let [el (js/document.querySelector ".learn > :last-child")] (.scrollIntoView el #js {:block "end"})))))

(defn- tutor-open?
  "The tutor and the scratchpad share one rule: gone in the two exams and
   while a graded check is live (wiki/UX.md card rule, wiki/Exercises.md)."
  []
  (and (not (#{:diagnostic :final} (:stage @run))) (not (check-live?))))

;; --- the scratchpad: the course's runtime, no check ---------------------------------------
;; wiki/Exercises.md «Scratchpads». NEVER evidence: nothing run here is an
;; attempt. The learner's code stays on this device, per course.

(def ^:private scratch-key "loretile-scratch")   ; {course-id code}
(defonce ^:private scratch-open (r/atom #{}))   ; course ids — closed by default in each course

(defn- scratch-spec
  "The runtime and setup to try things in: the current topic's exercise,
   else any exercise of the course. No exercises — no scratchpad."
  [kg course]
  (let [{:keys [closure locale]} (course-scope kg course)
        exs (fn [cid] (filter #(= :exercise (:kind %)) (items-of kg (:graph-id course) cid locale)))]
    (some-> (or (first (exs (current-topic))) (first (mapcat exs closure)))
            :spec (select-keys [:runtime :setup :schema]))))

(defn- save-scratch! [course-id code]
  (try (.setItem js/localStorage scratch-key (pr-str (assoc (read-ls scratch-key {}) course-id code)))
       (catch :default _ nil)))

(defn- scratchpad [kg course]
  (let [code (r/atom (get (read-ls scratch-key {}) (:id course) "")) out (r/atom nil) busy (r/atom false)]
    (fn [kg course]
      (when-let [spec (scratch-spec kg course)]
        (let [label (if (= :python (ex/runtime spec)) "Python" "SQL")
              run (fn [] (when-not @busy
                           (reset! busy true)
                           (-> (ex/try-run! spec @code)
                               (p/then #(reset! out %))
                               (p/catch #(reset! out {:error (str (t :learn/ex-engine-failed) (ex-message %))}))
                               (p/finally #(reset! busy false)))))]
          (if-not (@scratch-open (:id course))
            [:button.scratch-toggle {:on-click #(swap! scratch-open conj (:id course))}
             [icons/icon :rows] (t :learn/scratch-open {"{rt}" label})]
            [:div.scratch
             [:div.scratch-head
              [:b (t :learn/scratch-t {"{rt}" label})] [:span.muted " · " (t :learn/scratch-lead)]
              [:button.rbtn.sm.scratch-close {:title (t :learn/scratch-close) :aria-label (t :learn/scratch-close)
                                              :on-click #(swap! scratch-open disj (:id course))}
               [icons/icon :close]]]
             (when (:schema spec) [:pre.ex-schema (:schema spec)])
             [:textarea.ex-code {:default-value @code :spell-check false :rows 4
                                 :placeholder (if (= :python (ex/runtime spec)) "print(…)" "SELECT …")
                                 :on-change #(let [v (.. % -target -value)] (reset! code v) (save-scratch! (:id course) v))
                                 :on-key-down #(when (and (= "Enter" (.-key %)) (or (.-ctrlKey %) (.-metaKey %)))
                                                 (.preventDefault %) (run))}]
             [:div.row
              [:button.ex-run {:disabled @busy :on-click run} (if @busy "…" (t :learn/ex-run))]
              ;; the tutor looks only when asked, under its usual fence
              (when (and (:error @out) (llm/ready?) (not (:busy? @run)))
                [:button.scratch-ask {:on-click #(ask! kg course (t :learn/scratch-ask-text
                                                                     {"{code}" @code
                                                                      "{out}" (str (:stdout @out) (:error @out))}))}
                 (t :learn/scratch-ask)])]
             [result-view @out]]))))))

(defn- composer [kg course]
  (let [draft (r/atom "")]
    (fn [kg course]
      (case (:stage @run)
        ;; locked in the two exams, and while a learn-mode CHECK is live: that
        ;; card credits mastery, so it must not be answered with the tutor open
        ;; (wiki/UX.md card rule; review finding A3). It reopens the moment the
        ;; answer is in, which is the hypercorrection moment.
        (:diagnostic :final) [:p.muted.locked (t :learn/assistant-locked)]
        (if (check-live?)
          [:p.muted.locked (t :learn/tutor-during-check)]
          (if-not (llm/ready?)
                 [:p.muted.locked (t :learn/tutor-nokey) " " [:a {:href "#/settings"} (t :ui/settings)]]
                 [:div.composer.tutor-composer
                  [:div.tutor-label [icons/icon :chat] [:b (t :learn/tutor-t)] [:span.mode-badge.tutor (t :learn/mode-tutor)]
                   [:span.muted (t :learn/tutor-lead)]]
                  [:div.chat-field
                   [chat/grow-textarea {:value @draft :placeholder (t :learn/tutor-placeholder)
                                        :on-change #(reset! draft %)
                                        :on-submit (fn [] (ask! kg course @draft) (reset! draft ""))}]
                   [:button.primary.chat-sendbtn {:disabled (:busy? @run) :title (t :learn/tutor-send)
                                                  :on-click (fn [_] (ask! kg course @draft) (reset! draft ""))}
                    (if (:busy? @run) "…" [icons/icon :send])]]]))))))

(defn- warm-exercises!
  "Boot the engine when the course opens, not when the learner presses Run:
   the boot is seconds on a tablet, reading the material takes longer.
   Idempotent — `ex/warm!` caches per setup."
  [kg course]
  (let [{:keys [closure locale]} (course-scope kg course)]
    (doseq [spec (distinct (for [cid closure it (items-of kg (:graph-id course) cid locale)
                                 :when (= :exercise (:kind it))]
                             (select-keys (:spec it) [:runtime :setup])))]
      (ex/warm! spec))))

(defn view
  "The learner's route carries only the course id; the graph that owns it
   is resolved, because the catalog spans every graph."
  [course-id]
  (let [found (store/find-course course-id)
        ;; a run in flight sees its PIN, not HEAD; a fresh start sees the
        ;; newest publication. A run written before graphs/pins existed has
        ;; neither — fall back to the resolved graph rather than folding an
        ;; empty log, which dead-ended the learner with no way out.
        pinned? (= course-id (:course @run))
        gid (or (when pinned? (:graph @run)) (:id found))
        evs (store/graph-events gid)
        cut (if pinned? (:pin @run) (fold/publication-cut evs course-id))
        ;; a pin that is missing from the log → the newest publication, never HEAD
        cut (if (some #(= cut (:ev/id %)) evs) cut (fold/publication-cut evs course-id))
        kg (if (seq evs) (fold/kg (fold/events-at evs cut)) (:kg found))
        course (some-> (get-in kg [:courses course-id])
                       (assoc :graph-id gid :pin cut))
        ;; a run keeps teaching what the author has since deprecated: say
        ;; so, with the titles the learner saw — whether the topic was
        ;; deprecated before or after this run's publication (a course whose
        ;; PREREQUISITE was deprecated republished, and the note vanished in
        ;; the next round — review 2026-10-16 A14). `found` already folded HEAD.
        head (when (and course (= gid (:id found))) (:kg found))
        retired (when head
                  (filter #(= :deprecated (get-in head [:concepts % :status]))
                          (:order (course-scope kg course))))
        ;; «a new version runs on» only when the newest one no longer has them
        newer? (and (seq retired) (not= cut (fold/publication-cut evs course-id))
                    (let [now (some-> (get-in head [:courses course-id]) (#(course-scope head %)) :closure)]
                      (not-any? (set now) retired)))]
    (when course (warm-exercises! kg course))
    (cond
      (nil? course)
      [:div.learn
       [:p (t :ui/topic-not-found)]
       [:div.row
        ;; never strand a learner: leaving the run behind is always possible
        [:button.primary {:on-click #(do (shelve!) (goto-courses!))} (t :learn/abandon)]
        [:a.btn {:href "#/"} (t :ui/back-to-map)]]]
      (not= course-id (:course @run))
      [:div.learn
       [:a.back {:href "#/courses"} "← " (t :ui/courses)]
       [:h2 (:title course)]
       [:p.muted (t :learn/intro {"{n}" (count (:targets course))})]
       ;; what it covers, before it starts (learning review 2026-11-20 D5)
       [:div.start-targets
        [:h3 (t :learn/intro-targets)]
        [:ul (for [[cid lvl] (:targets course)]
               ^{:key cid} [:li [:span (title-of kg cid)] [:span.muted.lvl (i18n/enum-label "level" lvl)]])]]
       ;; how long it takes, before it starts: one question per topic
       [:p.muted.start-length (t :learn/intro-length {"{n}" (count (:closure (course-scope kg course)))})]
       [:button.primary.start {:on-click #(start! kg course)} (t :learn/start)]]
      :else
      (let [feed (:feed @run)
            last-live (last (keep-indexed (fn [i e] (when (#{:check :plan :final-intro :result :ready :pause} (:kind e)) i)) feed))]
        (scroll-end! [(:id @run) (count feed) (:busy? @run)])
        [:div.learn {:data-stage (name (:stage @run))}
         [:div.learn-head
          [:a.back {:href "#/courses"} "← " (:title course)]
          [:span.muted.learn-progress (head-progress @run)]
          ;; restart from HEAD: reusing the rendered course re-pinned the new
          ;; run to the OLD cut, so «start over» could never reach a newer
          ;; publication (found in review, 2026-09-11)
          [:button.rbtn.sm.marks-toggle {:class (when @marks-on "on") :title (t :learn/marks-toggle)
                                         :aria-label (t :learn/marks-toggle) :aria-pressed (boolean @marks-on)
                                         :on-click toggle-marks!}
           "✦"]
          [:button.rbtn.sm.restart {:title (t :learn/restart) :aria-label (t :learn/restart)
                                    :class (when @confirm-restart "on")
                                    :on-click #(swap! confirm-restart not)}
           "↺"]]
         ;; restart asks IN PLACE — the browser's confirm was the only native
         ;; dialog on the learner's path (learning review 2026-11-20 D7)
         (when @confirm-restart
           [:div.confirm-new.restart-confirm
            [:span (t :learn/restart-confirm)]
            [:div.row
             [:button.primary.restart-yes {:on-click #(do (reset! confirm-restart false) (restart-from-head! course-id))}
              (t :learn/restart-yes)]
             [:button.restart-no {:on-click #(reset! confirm-restart false)} (t :ui/cancel)]]])
         (when (seq retired)
           [:p.muted.retired-note (t (if newer? :learn/retired-newer :learn/retired-note)
                                     {"{list}" (str/join ", " (map #(title-of kg %) retired))})])
         [:div.feed
          ;; keyed by RUN and index: an index alone reused a card's state
          ;; (typed code, an answer) across a restart or a reopened run
          ;; @opened deref'd HERE, never inside the lazy `for` below (CLAUDE.md
          ;; gotcha: the render would not re-run, and a tap opened nothing)
          (let [segs (segments feed)
                last-seg (peek segs)
                op @opened]
            (for [{:keys [from to] :as sg} segs
                  :let [k [(:id @run) from]
                        entries (subvec feed from to)
                        open? (or (= sg last-seg) (contains? op k))]]
              ^{:key (str (:id @run) "/seg/" from)}
              [:div.feed-seg {:class (name (:kind sg))}
               (when (not= sg last-seg)
                 [:button.feed-fold {:class (when open? "open") :aria-expanded (boolean open?)
                                     :on-click #(swap! opened (fn [o] (if (contains? o k) (disj o k) (conj o k))))}
                  [:span.chev (if open? "▾" "▸")] [:span (segment-line kg sg entries)]])
               (when open?
                 (for [[j e] (map-indexed vector entries)
                       :let [i (+ from j)]]
                   ^{:key (str (:id @run) "/" i)} [entry-view kg course e (= i last-live)]))]))
          (when (:busy? @run) [:div.msg.msg-sys (t :learn/tutor-thinking)])
          [:div.feed-end]]
         ;; keyed by course and topic: its code is saved under the course it
         ;; was mounted for, so a reused instance wrote into the wrong one
         (when (and (tutor-open?) (not= :result (:stage @run))) ^{:key (str (:id course) "/" (current-topic))} [scratchpad kg course])
         [composer kg course]]))))
