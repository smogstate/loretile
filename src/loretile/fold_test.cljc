(ns loretile.fold-test
  "The paper simulations (wiki, 2026-09-01) as fixtures.
   Sim A: clean authoring of «SQL для аналитиков».
   Sim B: prerequisite closure reaches outside the course targets.
   Sim C: regulation update — material source change → stale concepts.
   Plus the guards: cycle rejection, discard dedup, schema at the door."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            #?(:cljs [cljs.reader :as reader])
            [loretile.agent-md :as export]
            [loretile.fold :as f]
            [loretile.graph-tools :as gt]
            [loretile.profile :as profile]
            [loretile.rdf :as rdf]
            [loretile.schema :as schema]
            [loretile.turtle :as turtle]))

(defn e [type & kvs]
  (merge {:ev/id (str (gensym "ev")) :ev/type type :ev/ns "demo"
          :ev/actor "denis" :ev/ts 1}
         (apply hash-map kvs)))

(defn concept [id title] {:id id :title title :definition (str title " — определение")})

;; «SQL для аналитиков»: select ← where ← group-by; join requires keys (outside course)
(def sql-events
  [(e :source-added :source {:id "src-sql-book" :title "Учебник SQL"})
   (e :concept-accepted :concept (concept "select" "SELECT: выборка"))
   (e :concept-accepted :concept (concept "where" "Фильтры WHERE"))
   (e :concept-accepted :concept (concept "group-by" "GROUP BY"))
   (e :concept-accepted :concept (concept "join" "JOIN"))
   (e :concept-accepted :concept (concept "agg" "Агрегатные функции"))
   (e :concept-accepted :concept (concept "keys" "Ключи и связи таблиц"))
   (e :edge-accepted :edge {:from "where" :to "select" :type :requires :provenance :authored})
   (e :edge-accepted :edge {:from "group-by" :to "where" :type :requires :provenance :authored})
   (e :edge-accepted :edge {:from "agg" :to "group-by" :type :requires :provenance :authored})
   (e :edge-accepted :edge {:from "join" :to "keys" :type :requires :provenance :authored})
   (e :object-added :object {:id "o-gb-1" :concept-id "group-by" :kind :explanation
                             :level :apprentice :locale "ru" :origin :authored
                             :text "GROUP BY складывает строки в корзины…"
                             :grounded-in "src-sql-book"})
   (e :item-added :item {:id "i-gb-1" :primary-concept-id "group-by" :level :apprentice
                         :kind :single-choice :locale "ru" :spec {:stem "Что вернёт запрос…" :options ["A" "B"] :key 1}})
   (e :item-added :item {:id "i-gb-2" :primary-concept-id "group-by" :level :apprentice
                         :kind :cloze :locale "ru" :spec {:text "GROUP BY идёт после ___" :key "WHERE"}})
   (e :item-added :item {:id "i-gb-3" :primary-concept-id "group-by" :level :apprentice
                         :kind :single-choice :locale "ru" :spec {:stem "HAVING нужен когда…" :options ["A" "B"] :key 0}})
   (e :item-accepted :id "i-gb-1")
   (e :item-accepted :id "i-gb-2")
   (e :item-accepted :id "i-gb-3")
   (e :course-published :course {:id "sql-analyst" :title "SQL для аналитиков"
                                 :targets [["select" :apprentice] ["where" :apprentice]
                                           ["group-by" :apprentice] ["join" :apprentice]
                                           ["agg" :apprentice]]})])

(deftest sim-a-clean-authoring
  (let [kg (f/kg sql-events)]
    (testing "every event passed the schema guard"
      (is (every? schema/valid? sql-events)))
    (testing "graph folded"
      (is (= 6 (count (:concepts kg))))
      (is (= 1 (count (:courses kg)))))
    (testing "GROUP BY reached minimum-viable (1 object + 3 items)"
      (is (f/publishable? kg "group-by"))
      (is (= :minimum-viable (f/concept-status kg "group-by"))))
    (testing "SELECT is accepted but not publishable yet"
      (is (= :accepted (f/concept-status kg "select"))))))

(deftest intake-provenance-grounds-concepts
  (let [kg (f/kg (into sql-events
                       [(e :source-added :source {:id "src-reg" :title "Регламент v1"})
                        (e :concept-accepted :concept (assoc (concept "kyc" "KYC") :source-id "src-reg"))]))]
    (testing "accepting a concept with a source-id adds the grounded-in edge"
      (is (some #(and (= "kyc" (:from %)) (= "src-reg" (:to %)) (= :grounded-in (:type %)))
                (:edges kg))))
    (testing "…so a material source update flags it stale (the compliance payoff)"
      (is (= :stale (f/concept-status
                     (f/apply-event kg (e :source-updated :id "src-reg" :change :material))
                     "kyc"))))))

(deftest concept-search-not-dump
  (let [kg (f/kg sql-events)]
    (testing "small namespace: everything is a candidate"
      (is (= 6 (count (f/concept-candidates kg "что угодно" 60)))))
    (testing "large namespace (cap below size): lexical hits plus 1-hop neighbours, capped"
      (let [c (f/concept-candidates kg "GROUP BY и агрегатные функции" 3)]
        (is (<= (count c) 3))
        (is (contains? c "group-by"))))
    (testing "vector search: nearest concept wins, its neighbours follow, unvectored ignored"
      (let [vectors {"agg" {:vec [1.0 0.0]} "join" {:vec [0.0 1.0]} "keys" {:vec [0.1 0.9]}}
            c (f/concept-candidates-by-vector kg [0.9 0.1] vectors 2)]
        (is (= 1.0 (f/cosine [1 0] [2 0])))
        (is (= 0.0 (f/cosine [1 0] [0 1])))
        (is (contains? c "agg"))
        (is (contains? c "group-by"))          ; agg's requires-neighbour, via 1-hop
        (is (not (contains? c "join")))))))

(deftest prerequisite-signals
  (let [gb {:id "group-by" :title "GROUP BY" :definition "складывает строки в корзины"
            :evidence "[стр. 4] GROUP BY groups rows"}
        hv {:id "having" :title "HAVING" :definition "фильтр по корзинам после GROUP BY"
            :evidence "[стр. 9] HAVING filters groups"}
        wf {:id "window" :title "Оконные функции" :definition "вычисления по окну строк"
            :evidence "[стр. 9] OVER (PARTITION BY …)"}]
    (testing "earlier mention + definition mention → dependent requires prerequisite, with reasons"
      (let [s (f/pair-signals hv gb)]
        (is (= "having" (:from s)))
        (is (= "group-by" (:to s)))
        (is (= 2 (count (:why s))))))
    (testing "argument order doesn't matter"
      (is (= (dissoc (f/pair-signals gb hv) :why) (dissoc (f/pair-signals hv gb) :why))))
    (testing "same page, no mention → no signal"
      (is (nil? (f/pair-signals hv wf))))
    (testing "candidates: signalled pairs first, unsignalled in-batch pairs still offered, capped"
      (let [c (f/edge-candidates f/empty-kg [gb hv wf] 10)]
        (is (= 3 (count c)))
        (is (:signalled? (first c)))
        (is (= ["having" "group-by"] [(:from (first c)) (:to (first c))]))
        (is (not (:signalled? (last c))))))
    (testing "a batch concept mentioning an existing concept yields a cross pair"
      (let [kg (f/kg sql-events)                      ; has "select" titled «SELECT: выборка»
            nc {:id "cte" :title "CTE" :definition "именованный подзапрос перед SELECT: выборка" :evidence ""}
            c (f/edge-candidates kg [nc] 10)]
        (is (= [{:from "cte" :to "select"}] (map #(select-keys % [:from :to]) c)))))))

(deftest course-readiness-is-level-aware
  (let [kg (f/kg sql-events)
        course (get-in kg [:courses "sql-analyst"])]
    (testing "GROUP BY is publishable at apprentice (its items' level), not at novice"
      (is (f/publishable-at? kg "group-by" :apprentice))
      (is (not (f/publishable-at? kg "group-by" :novice))))
    (testing "per-target readiness: only GROUP BY ready"
      (let [r (f/course-readiness kg course)]
        (is (= 5 (count r)))
        (is (= #{"group-by"} (set (map :concept (filter :ready? r)))))))
    (testing "locale narrows readiness: ru items don't serve a de course"
      (is (f/publishable-at? kg "group-by" :apprentice "ru"))
      (is (not (f/publishable-at? kg "group-by" :apprentice "de"))))
    (testing "plan readiness covers the prerequisite outside the course, targets first"
      (let [r (f/plan-readiness kg course)]
        (is (= 6 (count r)))
        (is (every? :target? (take 5 r)))
        (is (= {:concept "keys" :level :apprentice :target? false :ready? false :stale? false :deprecated? false} (last r)))))
    (testing "an object can point into its source: grounded-in + anchor"
      (let [kg2 (f/kg (conj sql-events
                            (e :object-added :object {:id "o-x" :concept-id "select" :kind :reading
                                                      :level :novice :locale "ru" :origin :authored
                                                      :grounded-in "src-sql-book" :anchor "p12" :text "…"})))]
        (is (= "p12" (get-in kg2 [:objects "o-x" :anchor])))
        (is (some #(and (= "select" (:from %)) (= "src-sql-book" (:to %)) (= :grounded-in (:type %)))
                  (:edges kg2)))))
    (testing "audience is an open tag set on objects, orthogonal to level"
      (is (schema/valid? (e :object-added
                            :object {:id "o-t" :concept-id "group-by" :kind :explanation
                                     :level :apprentice :locale "ru" :origin :authored
                                     :audience #{"trader" "teller"} :text "…"})))
      (is (not (schema/valid? (e :object-added
                                 :object {:id "o-t" :concept-id "group-by" :kind :explanation
                                          :level :apprentice :locale "ru" :origin :authored
                                          :audience "trader" :text "…"})))))
    (testing "course config fields pass the schema"
      (is (schema/valid? (e :course-published
                            :course {:id "x" :title "X" :targets [["select" :novice]]
                                     :pass-threshold 80 :diagnostic-items 10 :final-items 20}))))))

(deftest sim-b-closure-reaches-outside-course
  (let [kg (f/kg sql-events)
        targets (map first (get-in kg [:courses "sql-analyst" :targets]))
        closure (f/requires-closure kg targets)]
    (testing "Pavel's foreign keys: closure pulls in a concept no target names"
      (is (contains? closure "keys")))
    (testing "certificate scope stays targets-only"
      (is (not (some #{"keys"} targets))))
    (testing "level-aware closure: prerequisite inherits the dependent's level…"
      (let [need (f/requires-closure-levels kg (get-in kg [:courses "sql-analyst" :targets]))]
        (is (= :apprentice (need "keys")))
        (is (= :apprentice (need "select")))))
    (testing "…unless the edge sets :min-level; the higher demand wins across paths"
      (let [kg2 (f/kg (conj sql-events
                            (e :edge-accepted :edge {:from "agg" :to "keys" :type :requires
                                                     :provenance :authored :min-level :novice})))
            need (f/requires-closure-levels kg2 [["agg" :professional]])]
        (is (= :novice (need "keys")))                      ; via the min-level edge only
        (is (= :professional (need "group-by")))            ; inherited
        (let [need2 (f/requires-closure-levels kg2 [["agg" :professional] ["join" :apprentice]])]
          (is (= :apprentice (need2 "keys"))))))            ; join@apprentice outranks novice
    (testing "topo puts prerequisites before dependents"
      (let [order (f/topo-order kg closure)
            pos (zipmap order (range))]
        (is (< (pos "select") (pos "where")))
        (is (< (pos "where") (pos "group-by")))
        (is (< (pos "keys") (pos "join")))))))

(deftest sim-c-regulation-update
  (let [kg (f/kg (conj sql-events (e :source-updated :id "src-sql-book" :change :material)))]
    (testing "material source change marks grounded concepts stale"
      (is (= :stale (f/concept-status kg "group-by"))))
    (testing "ungrounded concepts untouched"
      (is (= :accepted (f/concept-status kg "select"))))
    (testing "patch change would not"
      (let [kg2 (f/kg (conj sql-events (e :source-updated :id "src-sql-book" :change :patch)))]
        (is (= :minimum-viable (f/concept-status kg2 "group-by")))))))

(deftest guards
  (let [kg (f/kg sql-events)]
    (testing "requires cycle rejected at the door"
      (is (some? (f/guard kg (e :edge-accepted
                                :edge {:from "select" :to "group-by"
                                       :type :requires :provenance :authored})))))
    (testing "non-cycle accepted"
      (is (nil? (f/guard kg (e :edge-accepted
                               :edge {:from "join" :to "select"
                                      :type :requires :provenance :authored})))))
    (testing "schema violation rejected"
      (is (some? (f/guard kg (e :concept-accepted :concept {:id "x"})))))
    (testing "discarded concept never re-proposed"
      (let [kg2 (f/kg (conj sql-events (e :concept-discarded :id "cte")))]
        (is (some? (f/guard kg2 (e :concept-proposed
                                   :concept (concept "cte" "CTE")
                                   :provenance :llm-drafted))))))
    (testing "re-ingesting the same material: existing concept not re-proposed"
      (is (= :duplicate (:error (f/guard kg (e :concept-proposed
                                              :concept (concept "select" "SELECT снова")
                                              :provenance :llm-drafted))))))
    (testing "pending proposal not proposed twice"
      (let [kg2 (f/kg (conj sql-events (e :concept-proposed
                                          :concept (concept "cte" "CTE")
                                          :provenance :llm-drafted)))]
        (is (= :duplicate (:error (f/guard kg2 (e :concept-proposed
                                                  :concept (concept "cte" "CTE ещё раз")
                                                  :provenance :llm-drafted)))))))
    (testing "existing edge not re-proposed"
      (is (= :duplicate (:error (f/guard kg (e :edge-proposed
                                              :edge {:from "where" :to "select" :type :requires
                                                     :provenance :llm-drafted}))))))
    (testing "discarded edge never re-proposed"
      (let [kg2 (f/kg (conj sql-events
                            (e :edge-discarded :edge {:from "agg" :to "join" :type :requires
                                                      :provenance :llm-drafted})))]
        (is (= :duplicate (:error (f/guard kg2 (e :edge-proposed
                                                  :edge {:from "agg" :to "join" :type :requires
                                                         :provenance :llm-drafted})))))))
    (testing "no dangling edges: unknown endpoint rejected, source endpoint ok for grounded-in"
      (is (= :missing-endpoint
             (:error (f/guard kg (e :edge-accepted
                                   :edge {:from "select" :to "nope" :type :requires
                                          :provenance :authored})))))
      (is (nil? (f/guard kg (e :edge-accepted
                               :edge {:from "select" :to "src-sql-book" :type :grounded-in
                                      :provenance :authored})))))
    (testing "re-accepting an existing concept is rejected (edit = version-created)"
      (is (= :exists (:error (f/guard kg (e :concept-accepted
                                           :concept (concept "select" "SELECT again")))))))
    (testing "a genuinely new proposal still passes"
      (is (nil? (f/guard kg (e :concept-proposed
                              :concept (assoc (concept "cte" "CTE") :evidence "[стр. 3] WITH …")
                              :provenance :llm-drafted)))))))

(deftest text-chunks-cut-between-pages
  (let [pages (str/join "\n" (for [i (range 1 11)] (str "[стр. " i "] " (apply str (repeat 90 "x")))))
        parts (f/text-chunks pages 300)]
    (is (every? #(<= (count (:text %)) 300) parts))
    (is (= [1 3] ((juxt :from :to) (first parts))) "a part never splits a page")
    (is (= 10 (:to (peek parts))))
    (is (= pages (str/join "\n" (map :text parts))) "nothing lost, nothing reordered"))
  (testing "unmarked text cuts at paragraphs; an oversized paragraph is cut hard"
    (let [parts (f/text-chunks (str "aaa\n\nbbb\n\n" (apply str (repeat 25 "c"))) 10)]
      (is (= ["aaa\n\nbbb" "cccccccccc" "cccccccccc" "ccccc"] (map :text parts)))
      (is (nil? (:from (first parts))))))
  (is (= 1 (count (f/text-chunks "short" 100)))))

(deftest manual-removal
  (let [kg (f/kg sql-events)
        rm (fn [kg ev] (is (nil? (f/guard kg ev))) (f/apply-event kg ev))]
    (testing "a link removed by hand leaves the map"
      (let [g (rm kg (e :edge-discarded :edge {:from "join" :to "keys" :type :requires :provenance :authored}))]
        (is (not-any? #(= ["join" "keys"] [(:from %) (:to %)]) (:edges g)))))
    (testing "a topic outside every course goes, and its edges with it"
      (let [g (-> kg
                  (f/apply-event (e :edge-accepted :edge {:from "keys" :to "select" :type :see-also :provenance :authored}))
                  (as-> g (rm g (e :concept-discarded :id "keys"))))]
        (is (nil? (get-in g [:concepts "keys"])))
        (is (not-any? #(or (= "keys" (:from %)) (= "keys" (:to %))) (:edges g)))
        (is (nil? (f/guard g (e :concept-accepted :concept (concept "keys" "Ключи")))) "re-adding by hand is allowed")))
    (testing "a course target cannot be removed"
      (is (= :in-course (:error (f/guard kg (e :concept-discarded :id "select"))))))))

(deftest split-remaps-history
  (let [kg (f/kg (conj sql-events
                       (e :concept-split :id "group-by"
                          :into [(concept "group-basics" "GROUP BY: основы")
                                 (concept "having" "HAVING")]
                          :item-remap {"i-gb-1" "group-basics" "i-gb-2" "group-basics" "i-gb-3" "having"}
                          :object-remap {"o-gb-1" "group-basics"})))]
    (testing "items re-point to the new concepts, history preserved"
      (is (= "group-basics" (get-in kg [:items "i-gb-1" :primary-concept-id])))
      (is (= "having" (get-in kg [:items "i-gb-3" :primary-concept-id])))
      (is (nil? (get-in kg [:concepts "group-by"]))))))

;; The shipped demo graph (kg/demo/log.edn, generated by build.py): every
;; event passes the schema AND the guard in order, and the graph shows
;; what the demo promises — ready topics, the course, the open question.
#?(:cljs
   (deftest demo-log-is-sound
     (doseq [file (into ["kg/demo/log.edn"]
                        (for [slug ["sql" "infosec" "vineyard"] loc ["ru" "de" "en"]]
                          (str "kg/demo/" slug "." loc ".edn")))]
      (let [text (.readFileSync (js/require "fs") file "utf8")
           evs (reader/read-string (str "[" text "]"))
           kg (reduce (fn [kg e]
                        (is (schema/valid? e) (str file " schema: " (:ev/id e) " " (schema/explain e)))
                        (is (nil? (f/guard kg e)) (str file " guard: " (:ev/id e) " " (f/guard kg e)))
                        (f/apply-event kg e))
                      (f/kg []) evs)]
       (testing "folded"
         (is (>= (count (:concepts kg)) 14))
         (is (pos? (count (:courses kg)))))
       (testing "EVERY course's targets are ready to publish at their level"
         (doseq [course (vals (:courses kg))
                 [cid level] (:targets course)]
           (is (f/publishable-at? kg cid level (:locale course))
               (str file " " (:id course) " target " cid " @" level))))
       (testing "every concept is grounded in a source"
         (is (every? (fn [id] (some #(and (= id (:from %)) (= :grounded-in (:type %))) (:edges kg)))
                     (keys (:concepts kg)))))))))

;; --- publish and pin (wiki/Data-Model.md) ------------------------------------
;; A published version is a CUT in the log, not a copy: a learner pinned at
;; the cut is blind to later material edits but still receives patch-level
;; corrections — otherwise they are graded by a question the author has
;; already fixed.
#?(:cljs
   (deftest publish-and-pin
     (let [published (e :course-published :course {:id "c1" :title "Курс"
                                                   :targets [["group-by" :apprentice]]})
           after-material (e :version-created :entity :concept :id "group-by"
                             :change :material :payload {:title "ПЕРЕПИСАНО"})
           after-patch (e :version-created :entity :item :id "i-gb-1"
                          :change :patch :payload {:spec {:stem "исправленная формулировка"}})
           log (concat sql-events [published after-material after-patch])
           cut (:ev/id published)
           head (f/kg log)
           pinned (f/kg (f/events-at (vec log) cut))]
       (testing "the cut is the newest publication of that course"
         (is (= cut (f/publication-cut (vec log) "c1"))))
       (testing "the author at HEAD sees the material rewrite"
         (is (= "ПЕРЕПИСАНО" (get-in head [:concepts "group-by" :title]))))
       (testing "a pinned learner does NOT — the ground must not move mid-course"
         (is (= "GROUP BY" (get-in pinned [:concepts "group-by" :title]))))
       (testing "but a pinned learner DOES get the patch-level correction"
         (is (= "исправленная формулировка" (get-in pinned [:items "i-gb-1" :spec :stem])))
         (is (= (get-in head [:items "i-gb-1" :spec :stem])
                (get-in pinned [:items "i-gb-1" :spec :stem]))))
       (testing "the course itself is present at the cut"
         (is (some? (get-in pinned [:courses "c1"]))))
       (testing "no cut means HEAD"
         (is (= "ПЕРЕПИСАНО" (get-in (f/kg (f/events-at (vec log) nil))
                                     [:concepts "group-by" :title])))))))

#?(:cljs
   ;; review 2026-10-16 A4: the edit forms send the WHOLE entity, so a patch
   ;; after a material rewrite carried the rewrite to pinned learners, and a
   ;; patch on an entity born after the cut built it from nothing
   (deftest a-patch-never-carries-a-rewrite
     (let [published (e :course-published :course {:id "c1" :title "Курс"
                                                   :targets [["group-by" :apprentice]]})
           rewrite (e :version-created :entity :concept :id "group-by" :change :material
                      :payload {:title "GROUP BY" :definition "ПЕРЕПИСАННОЕ ОПРЕДЕЛЕНИЕ"})
           typo (e :version-created :entity :concept :id "group-by" :change :patch
                   :payload {:title "GROUP BY!" :definition "ПЕРЕПИСАННОЕ ОПРЕДЕЛЕНИЕ"})
           born (e :concept-accepted :concept (concept "late" "Поздняя тема"))
           late-patch (e :version-created :entity :concept :id "late" :change :patch :payload {:title "ПОЗДНЯЯ"})
           key-fix (e :version-created :entity :item :id "i-gb-1" :change :patch
                      :payload {:spec {:stem "исправленная формулировка"}})
           log (vec (concat sql-events [published rewrite typo born late-patch key-fix]))
           pinned (f/kg (f/events-at log (:ev/id published)))]
       (is (not= "ПЕРЕПИСАННОЕ ОПРЕДЕЛЕНИЕ" (get-in pinned [:concepts "group-by" :definition]))
           "the patch after a material rewrite stays with the rewrite")
       (is (nil? (get-in pinned [:concepts "late"])) "no ghost of a topic born after the cut")
       (is (= "исправленная формулировка" (get-in pinned [:items "i-gb-1" :spec :stem]))
           "a patch on what the learner has still reaches them"))))

(deftest guards-need-their-subject-and-one-edge-per-key
  ;; review 2026-10-16 A16 A17
  (let [kg (f/kg sql-events)
        ed (first (filter #(= :requires (:type %)) (:edges kg)))]
    (doseq [ev [(e :item-accepted :id "nope") (e :item-retired :id "nope") (e :object-demoted :id "nope")
                (e :version-created :entity :concept :id "nope" :change :patch :payload {:title "x"})
                (e :source-updated :id "nope" :change :patch)]]
      (is (= :missing (:error (f/guard kg ev))) (str (:ev/type ev))))
    (is (= :duplicate (:error (f/guard kg (e :edge-accepted :edge (assoc ed :provenance :authored :justification "ещё раз"))))))))

(deftest the-cycle-guard-is-linear
  ;; review 2026-10-16 A12: 40 diamonds were 2^40 paths for the per-path walk
  (let [n 40
        node #(str "n" %)
        evs (concat (for [i (range (inc n))] (e :concept-accepted :concept (concept (node i) (node i))))
                    (for [i (range n) side ["a" "b"]] (e :concept-accepted :concept (concept (str (node i) side) side)))
                    (for [i (range n) side ["a" "b"]
                          ed [{:from (str (node i) side) :to (node i)} {:from (node (inc i)) :to (str (node i) side)}]]
                      (e :edge-accepted :edge (assoc ed :type :requires :provenance :authored))))
        kg (f/kg evs)]
    (is (false? (f/cycle? kg :requires "elsewhere" (node n))) "no cycle: answered without walking every path down from the top")
    (is (true? (f/cycle? kg :requires (node 0) (node n))) "a real cycle is still found")))

(deftest an-import-replays-the-guard
  ;; security review 2026-10-03 #3, review 2026-10-16 A3
  (let [bad-profile {:id "x" :title "X" :node-types [{:id :thing :label "thing"}]
                     :relations [{:id :requires :label "requires" :ordering? true}]}
        r (f/replay f/empty-kg [(e :profile-set :profile bad-profile)])]
    (is (= :bad-profile (get-in r [:error :error])) "a custom profile cannot take a learning id"))
  (let [r (f/replay f/empty-kg (conj (vec sql-events) (e :edge-accepted :edge {:from "group-by" :to "nowhere" :type :requires :provenance :authored})))]
    (is (= :missing-endpoint (get-in r [:error :error]))))
  (is (some? (:kg (f/replay f/empty-kg sql-events))) "a sound log folds"))

(deftest content-locale-is-the-graphs-language
  ;; new content is stamped with it: a course deals only items in its language
  (is (nil? (f/content-locale f/empty-kg)) "an empty graph has no language of its own")
  (let [kg {:items {"a" {:locale "en"} "b" {:locale "en"} "c" {:locale "ru"}}
            :objects {"o" {:locale "en"}}
            :courses {"k" {:locale "en"}}}]
    (is (= "en" (f/content-locale kg)) "the most common locale wins"))
  (is (= "de" (f/content-locale {:objects {"o" {:locale "de"}}})) "objects alone are enough"))

(deftest staleness-lives-on-material
  ;; decided 2026-10-02 (review B3): a source change stales what was written
  ;; from it; a topic shows stale while any ACTIVE object on it is
  (let [base (into sql-events
                   [(e :source-added :source {:id "src-team-b" :title "Team B handbook"})
                    (e :object-added :object {:id "o-gb-b" :concept-id "group-by" :kind :worked-example
                                              :level :apprentice :locale "ru" :origin :authored
                                              :text "Team B's example" :grounded-in "src-team-b"})])
        book (f/kg (conj base (e :source-updated :id "src-sql-book" :change :material)))
        team-b (f/kg (conj base (e :source-updated :id "src-team-b" :change :material)))]
    (testing "the changed source's object is stale, the other team's is not"
      (is (get-in book [:objects "o-gb-1" :stale?]))
      (is (not (get-in book [:objects "o-gb-b" :stale?]))))
    (testing "the topic shows stale through its object — the concept itself is not marked"
      (is (f/stale? book "group-by"))
      (is (= :stale (f/concept-status book "group-by")))
      (is (nil? (get-in book [:concepts "group-by" :stale?]))))
    (testing "team B's update stales only team B's object"
      (is (get-in team-b [:objects "o-gb-b" :stale?]))
      (is (not (get-in team-b [:objects "o-gb-1" :stale?]))))
    (testing "a demoted object no longer speaks for its topic"
      (is (not (f/stale? (f/apply-event book (e :object-demoted :id "o-gb-1")) "group-by"))))
    (testing "re-versioning the stale object clears it: the author reviewed it"
      (let [fixed (f/apply-event book (e :version-created :entity :object :id "o-gb-1" :change :patch
                                         :payload {:text "GROUP BY, rewritten for the new edition"}))]
        (is (not (get-in fixed [:objects "o-gb-1" :stale?])))
        (is (not (f/stale? fixed "group-by")))))
    (testing "untouched topics stay untouched"
      (is (not (f/stale? book "select"))))))

(deftest reader-marks
  (testing "essence and example marks split into segments, plain text around them"
    (is (= [[:plain "Ripeness is a balance: "] [:essence "sugar rises, acid falls"] [:plain ". E.g. "]
            [:example "22 °Bx at 6 g/L"] [:plain "."]]
           (f/mark-segments "Ripeness is a balance: ==sugar rises, acid falls==. E.g. ++22 °Bx at 6 g/L++."))))
  (testing "plain text drops the marks, keeps the words"
    (is (= "a b c" (f/plain-text "a ==b== c")))
    (is (= "no marks here" (f/plain-text "no marks here"))))
  (testing "an unpaired marker is just text"
    (is (= [[:plain "1 == 2 or a++"]] (f/mark-segments "1 == 2 or a++")))
    (is (not (f/marked? "1 == 2 or a++"))))
  (testing "code is not a mark: delimiters must hug their text (review 2026-10-02)"
    (doseq [code ["x == 1 and y == 2" "i++; j++" "=====" "a==b==c" "+++" "if (a == b) { i++ }"]]
      (is (not (f/marked? code)) code)
      (is (= code (f/plain-text code)) code)
      (is (= [:span.marked code] (f/marks->hiccup code true)) code)))
  (testing "a real mark next to code"
    (is (= [[:essence "Ключ уникален"] [:plain ": x == 1 and i++"]]
           (f/mark-segments "==Ключ уникален==: x == 1 and i++")))
    (is (= "while i == 0: i++ then sum" (f/plain-text "while i == 0: i++ then ++sum++")))
    (is (= [[:plain "y==z and "] [:essence "w"]] (f/mark-segments "y==z and ==w=="))))
  (testing "hiccup: marks when on, plain text when off"
    (is (= [:span.marked "x " [:mark.mk-essence "y"]] (f/marks->hiccup "x ==y==" true)))
    (is (= "x y" (f/marks->hiccup "x ==y==" false)))))

(deftest source-update-scoped-to-topics
  ;; the re-upload card asks per topic: «material» for one topic stales only
  ;; that topic's material from the source (decided 2026-10-02, #7)
  (let [base (into sql-events
                   [(e :object-added :object {:id "o-where-1" :concept-id "where" :kind :explanation
                                              :level :apprentice :locale "ru" :origin :authored
                                              :text "WHERE фильтрует строки" :grounded-in "src-sql-book"})])
        kg (f/kg (conj base (e :source-updated :id "src-sql-book" :change :material :concepts ["group-by"])))]
    (testing "the answered topic's material is stale"
      (is (get-in kg [:objects "o-gb-1" :stale?]))
      (is (f/stale? kg "group-by")))
    (testing "another topic written from the same source is not"
      (is (not (get-in kg [:objects "o-where-1" :stale?])))
      (is (not (f/stale? kg "where"))))
    (testing "the scoped event passes the schema"
      (is (schema/valid? (assoc (e :source-updated :id "src-sql-book" :change :patch :concepts ["where"]) :ev/ts 1))))))

(deftest a-topic-re-versioned-clears-its-own-stale-flag
  ;; review 2026-10-02: the author acting on a topic clears ITS flag.
  ;; A topic's own rewrite stales nothing else: staleness lives on
  ;; material (Decisions #5) — a material edit only waits for the next cut
  (let [ver (fn [id change] (e :version-created :entity :concept :id id :change change :payload {:definition "new"}))
        kg (f/kg (into sql-events [(e :source-added :source {:id "src-reg" :title "Reg"})
                                   (e :concept-accepted :concept (assoc (concept "kyc" "KYC") :source-id "src-reg"))
                                   (e :source-updated :id "src-reg" :change :material)]))]
    (is (f/stale? kg "kyc") "stale by its source")
    (is (not (f/stale? (f/apply-event kg (ver "kyc" :patch)) "kyc")) "re-versioned → own flag clears")
    (let [kg2 (f/apply-event (f/kg sql-events) (ver "where" :material))]
      (is (not-any? #(f/stale? kg2 %) ["where" "group-by" "select"]) "a topic's material edit stales no topic"))))

(deftest stale-target-is-not-ready
  (let [course (get-in (f/kg sql-events) [:courses "sql-analyst"])
        stale (f/kg (conj sql-events (e :source-updated :id "src-sql-book" :change :material)))
        row (fn [kg] (first (filter #(= "group-by" (:concept %)) (f/course-readiness kg course))))]
    (is (:ready? (row (f/kg sql-events))))
    (is (not (:ready? (row stale))))
    (is (:stale? (row stale)))
    (is (not (:ready? (first (filter #(= "group-by" (:concept %)) (f/plan-readiness stale course))))))))

(deftest material-order-is-stable
  (let [objs (for [i (range 12)]
               (e :object-added :object {:id (str "o-" (- 20 i)) :concept-id "where" :kind :explanation
                                         :level :apprentice :locale "ru" :origin :authored :text "t"}))]
    (is (= (sort (map #(get-in % [:object :id]) objs))
           (map :id (f/concept-objects (f/kg (into sql-events objs)) "where"))))))

(deftest tokenizer-keeps-every-letter
  (let [c (fn [id t] (e :concept-accepted :concept {:id id :title t :definition t}))
        kg (f/kg [(c "a" "Säuregehalt") (c "b" "Zuckergrad") (c "c" "Ώριμος καρπός") (c "d" "Reife")])]
    (is (= #{"a"} (f/concept-candidates kg "säuregehalt" 1)))
    (is (= #{"c"} (f/concept-candidates kg "Ώριμος" 1)))))

(deftest objects-arrange-by-their-links
  ;; 2026-10-07: an example sits under what it illustrates, a simpler version
  ;; under what it restates, «read after» orders the rest; links stay in a topic
  (let [o (fn [id cid kind] (e :object-added :object {:id id :concept-id cid :kind kind :level :novice
                                                      :locale "ru" :origin :authored :text id}))
        link (fn [from to type] (e :object-linked :link {:from from :to to :type type}))
        base [(e :concept-accepted :concept (concept "c" "C"))
              (e :concept-accepted :concept (concept "d" "D"))
              (o "a-expl" "c" :explanation) (o "b-ex" "c" :worked-example)
              (o "c-easy" "c" :explanation) (o "d-read" "c" :reading) (o "z-other" "d" :explanation)]
        kg (f/kg (into base [(link "b-ex" "a-expl" :example-of)
                             (link "c-easy" "a-expl" :simpler-of)
                             (link "a-expl" "d-read" :follows)]))
        arr (f/arrange-objects kg (f/concept-objects kg "c"))]
    (is (= ["d-read" "a-expl"] (map (comp :id :object) arr)) "«read after» puts a-expl behind d-read; children are not main entries")
    (is (= ["b-ex"] (map :id (:examples (second arr)))) "the example sits under what it illustrates")
    (is (= ["c-easy"] (map :id (:simpler (second arr)))) "the simpler version sits under what it restates")
    (is (= ["d-read" "a-expl" "c-easy" "b-ex"] (map :id (f/arranged-flat kg (f/concept-objects kg "c")))) "one list for prompts")
    (testing "a child whose target the reader can't see stands on its own"
      (is (= ["b-ex" "c-easy" "d-read"] (map (comp :id :object) (f/arrange-objects kg (remove #(= "a-expl" (:id %)) (f/concept-objects kg "c")))))))
    (testing "unlinking restores the plain order"
      (let [kg2 (f/apply-event kg (e :object-unlinked :link {:from "b-ex" :to "a-expl" :type :example-of}))]
        (is (some #(= "b-ex" (:id (:object %))) (f/arrange-objects kg2 (f/concept-objects kg2 "c"))))))
    (testing "the guard: two existing objects of one topic, never itself"
      (let [kg0 (f/kg base)]
        (is (nil? (f/guard kg0 (link "b-ex" "a-expl" :example-of))))
        (is (= :bad-object-link (:error (f/guard kg0 (link "b-ex" "z-other" :example-of)))) "across topics")
        (is (= :bad-object-link (:error (f/guard kg0 (link "a-expl" "a-expl" :follows)))) "itself")
        (is (= :bad-object-link (:error (f/guard kg0 (link "b-ex" "nope" :simpler-of)))) "a missing object")))))

;; --- profiles: one graph tool, several ontologies (wiki/Profiles.md) --------

(def shop-profile
  {:id "shop" :title "How the shop works"
   :node-types [{:id :process :label "процесс"} {:id :role :label "роль"} {:id :rule :label "правило"}]
;; the ordering relation reads from the LATER node to the EARLIER one, like
   ;; `requires`: invoicing comes after checkout, so invoice → checkout reads
   ;; «запускается после»; the inverse is «запускает»
   :relations [{:id :triggers :label "запускается после" :inverse "запускает" :from #{:process} :to #{:process} :ordering? true}
               {:id :performs :label "выполняет" :inverse "выполняется" :from #{:role} :to #{:process}}
               {:id :governed-by :label "регулируется" :inverse "регулирует" :from #{:process} :to #{:rule}}]})

(defn- typed [id title kind] (assoc (concept id title) :kind kind))

(def shop-events
  [(e :profile-set :profile shop-profile)
   (e :concept-accepted :concept (typed "checkout" "Оформление заказа" :process))
   (e :concept-accepted :concept (typed "invoice" "Выставление счёта" :process))
   (e :concept-accepted :concept (typed "accountant" "Бухгалтер" :role))
   (e :concept-accepted :concept (typed "vat" "НДС на цифровые товары" :rule))
   (e :edge-accepted :edge {:from "invoice" :to "checkout" :type :triggers :provenance :authored})
   (e :edge-accepted :edge {:from "accountant" :to "invoice" :type :performs :provenance :authored})
   (e :edge-accepted :edge {:from "invoice" :to "vat" :type :governed-by :provenance :authored})])

;; business dogfood 2026-10-15: on a real process document a quarter of the
;; candidate pairs joined types no relation connects, and every hint named
;; the ordering relation (process → process) for pairs like process · system
(deftest pairs-in-a-custom-ontology-fit-its-types
  (let [kg (f/kg [(e :profile-set :profile shop-profile)])
        cashier {:id "cashier" :title "Кассир" :kind :role :definition "сотрудник на кассе"}
        refund {:id "refund" :title "Возврат" :kind :process :definition "возврат денег покупателю; ведёт Кассир"}
        rule {:id "refund-rule" :title "Правило возврата" :kind :rule :definition "Кассир принимает возврат только с чеком"}
        cs (f/edge-candidates kg [cashier refund rule] 10)
        by (into {} (map (juxt (juxt :from :to) :fits) cs))]
    (testing "the relations a pair of types allows, by direction"
      (is (= [:performs] (f/fitting-relations shop-profile :role :process)))
      (is (= [] (f/fitting-relations shop-profile :process :role)))
      (is (= [] (f/fitting-relations shop-profile :role :rule))))
    (testing "a pair no relation can join is never asked (role · rule)"
      (is (= 2 (count cs)))
      (is (not-any? #(= #{"cashier" "refund-rule"} #{(:from %) (:to %)}) cs)))
    (testing "the signal's direction turns to one a relation fits, and the hint names it"
      (is (= [:performs] (by ["cashier" "refund"])) "refund mentions the cashier; only role → process fits")
      (is (= [:governed-by] (by ["refund" "refund-rule"])) "the rule mentions the refund; only process → rule fits"))
    (testing "a learning graph is untouched: no :fits"
      (is (every? #(nil? (:fits %)) (f/edge-candidates f/empty-kg [cashier refund rule] 10))))))

(deftest the-same-question-is-asked-once
  (let [cq (fn [id q] (e :cq-raised :cq {:id id :question q :pattern {:relation :performs :from-type :process :inverse? true}}))
        kg (f/kg [(e :profile-set :profile shop-profile) (cq "q1" "Who performs this process?")])]
    (is (= :duplicate (:error (f/guard kg (cq "q2" "  who performs this   process? ")))))
    (is (nil? (f/guard kg (cq "q3" "Which rule governs this process?"))))))

(deftest numbered-headings-give-step-order
  (let [text (str "## Procurement\n### Vendor Lifecycle\n#### 1. RFP and Vendor Selection\nAll new spend follows the RFP.\n"
                  "#### 2. Negotiation\nProcurement negotiates terms.\n"
                  "### Review Steps\n#### 1. Sanctions Screening\nRisk Rate screens the vendor name.\n"
                  "#### 2.A. Buyer Review\nThe buyer checks documents.\n#### 2.B. New Supplier Onboarding\nCoupa asks for banking data.\n"
                  "#### 3.A. FP&A Review\nFP&A checks the budget.\n### Other\nNo steps here.\n")
        steps (f/numbered-steps text)
        at (fn [ev title] {:id title :title title :evidence ev :step (f/step-of steps text {:evidence ev :title title})})
        sanctions (at "Risk Rate screens the vendor name" "Sanctions screening")
        buyer (at "The buyer checks documents" "Buyer review")
        onboarding (at "Coupa asks for banking data" "Supplier onboarding")
        fpa (at "FP&A checks the budget" "FP&A review")
        rfp (at "All new spend follows the RFP" "RFP")
        other (at "No steps here" "Other")]
    (testing "numbered headings, their numbers and the list they belong to"
      (is (= [nil nil 1 2 nil 1 2 2 3 nil] (map :n steps)))
      (is (= {:parent "Review Steps" :n 2 :sub "A"} (select-keys (:step buyer) [:parent :n :sub])))
      (is (nil? (:step other)) "text under an unnumbered heading has no step"))
    (testing "a later step comes after an earlier one of the same list"
      (is (= {:from "FP&A review" :to "Buyer review"} (select-keys (f/pair-signals fpa buyer) [:from :to])))
      (is (= :step-after (:sig (first (:why (f/pair-signals buyer sanctions)))))))
    (testing "2.A and 2.B are parallel; two lists do not order each other"
      (is (nil? (f/pair-signals buyer onboarding)))
      (is (nil? (f/pair-signals rfp sanctions))))
    (testing "only ADJACENT steps carry order: 1 → 3.A follows from 1 → 2.A → 3.A"
      (is (nil? (f/pair-signals fpa sanctions))))
    ;; business dogfood A8: no node for steps 4.B and 5 — nothing could order them
    (testing "the numbered steps no node sits under, list by list"
      (let [role (assoc (at "Coupa asks for banking data" "Coupa") :kind :system)
            step? #(not= :system (:kind %))
            miss (f/missing-steps text [sanctions buyer fpa role] step?)
            review (first (filter #(= "Review Steps" (:parent %)) miss))]
        (is (= [["2" "B"]] (map (juxt (comp str :n) :sub) (:steps review)))
            "2.B has no node: a system quoted under it is not the step")
        (is (= "#### 2.B. New Supplier Onboarding\nCoupa asks for banking data.\n" (:text (first (:steps review)))))
        (is (re-find #"^### Review Steps\n#### 1\. Sanctions" (:list-text review)) "the list's whole section comes along")
        (is (not (re-find #"### Other" (:list-text review))))
        (is (nil? (first (filter #(= "Vendor Lifecycle" (:parent %)) miss)))
            "a list no step of which has a node was not read — never reported")
        (is (= [] (f/missing-steps text [sanctions buyer onboarding fpa] (constantly true))))))
    ;; business dogfood A7: steps 1 → 2.A → 3.A were never asked — roles and
    ;; systems quoted under a step heading filled the pair budget with
    ;; «step 1 → step 2» hints, and a later part never met an earlier one's steps
    (let [kg (f/kg [(e :profile-set :profile shop-profile)])
          as (fn [c kind] (assoc c :kind kind :definition ""))
          cands (fn [batch kg] (f/edge-candidates kg batch 40))
          signals (fn [cs] (into {} (for [c cs] [#{(:from c) (:to c)} (set (map :sig (:why c)))])))]
      (testing "step order is a signal only where the ordering relation fits"
        (let [cs (signals (cands [(as sanctions :process) (as buyer :process)
                                  (assoc (as {:id "Counsel" :title "Counsel" :evidence "Risk Rate screens the vendor name"} :role)
                                         :step (:step sanctions))]
                                 kg))]
          (is (= #{:step-after} (cs #{"Buyer review" "Sanctions screening"})))
          (is (= #{} (cs #{"Counsel" "Buyer review"})) "a role «in step 1» is no earlier step")))
      (testing "adjacent steps pair across parts: the earlier part's topics come in kg"
        (let [kg2 (assoc-in kg [:concepts "Buyer review"] (as buyer :process))
              cs (cands [(as fpa :process)] kg2)]
          (is (= [["FP&A review" "Buyer review"]] (map (juxt :from :to) cs)))
          (is (= [:step-after] (map :sig (:why (first cs))))))))))

(deftest decimals-and-normalised-profiles
  ;; review 2026-10-16 (lower list)
  (is (not= (f/title-tokens "Step 4.1 Review") (f/title-tokens "Step 4.2 Review")) "4.1 and 4.2 are two steps")
  (is (= (f/title-tokens "over 5,000") (f/title-tokens "over 5k")) "amounts still read alike")
  (let [p {:id "x" :title "X" :node-types [{:id :thing :label "thing"}]
           :relations [{:id :grounded-in :label "grounded" :ordering? true}]}]
    (is (nil? (f/guard f/empty-kg (e :profile-set :profile p)))
        "a profile whose only ordering relation is grounded-in loses it in normalisation — and stands without one (2026-11-19)")
    (is (nil? (:ordering? (first (filter #(= :grounded-in (:id %)) (:profile (f/kg [(e :profile-set :profile p)]))))))
        "the flag does not survive on grounded-in")))

(deftest two-lists-under-one-heading-text-stay-two
  ;; review 2026-10-16 A23: lists were told apart by their parent's TEXT
  (let [text (str "## Purchase\n### Steps\n#### 1. Request\nThe buyer files a request.\n#### 2. Approve\nThe manager approves.\n"
                  "## Invoice\n### Steps\n#### 1. Receive\nAP receives the invoice.\n#### 2. Pay\nAP pays the invoice.\n"
                  "## Code\n```python\n# 1. load data\nx = 1\n```\n## 2024 Roadmap\nPlans.\n")
        steps (f/numbered-steps text)
        at (fn [ev] {:id ev :title ev :evidence ev :kind :process :step (f/step-of steps text {:evidence ev})})
        approve (at "The manager approves.") receive (at "AP receives the invoice.") request (at "The buyer files a request.")]
    (is (= [1 2 1 2] (keep :n steps)) "a code comment and a year are no steps")
    (is (nil? (f/pair-signals approve receive)) "step 2 of one list is not after step 1 of the other")
    (is (= :step-after (:sig (first (:why (f/pair-signals approve request))))))
    (let [miss (f/missing-steps text [request approve receive] (constantly true))]
      (is (= [["Steps" 2]] (for [l miss st (:steps l)] [(:parent l) (:n st)])) "Invoice step 2 is missing, Purchase is whole")
      (is (re-find #"Receive" (:list-text (first miss))))
      (is (not (re-find #"Request" (:list-text (first miss)))) "the list text is the Invoice list's"))))

(deftest near-duplicates-are-flagged-not-refused
  (let [kg (f/kg [(e :profile-set :profile shop-profile)
                  (e :concept-accepted :concept (typed "po-required-over-5000" "PO required above $5,000 contract value" :rule))
                  (e :concept-accepted :concept (typed "invoice" "Invoice processing" :process))
                  (e :cq-raised :cq {:id "q1" :question "Which rule is enforced in which system (e.g. contract required before work starts)?"
                                     :pattern {:relation :governed-by}})])]
    (testing "amounts read alike: 5,000 · 5000 · 5k · $5K"
      (is (= #{"po" "required" "above" "5000"} (f/title-tokens "PO required above $5K")))
      (is (= (f/title-tokens "over 5,000") (f/title-tokens "over 5k"))))
    (testing "the same rule from another part of the document, by id"
      (is (= "po-required-over-5000" (:id (f/near-duplicate kg {:id "po-required-over-5k" :title "PO required above $5K"})))))
    (testing "numbered siblings are not duplicates"
      (is (< (f/similarity "Step 1" "Step 2") f/near-duplicate-at))
      (is (< (f/similarity "Tier 1 review" "Tier 2 review") f/near-duplicate-at)))
    (testing "a related but different node is not a duplicate"
      (is (nil? (f/near-duplicate kg {:id "payroll-invoice" :title "Payroll invoice processing"})))
      (is (nil? (f/near-duplicate kg {:id "invoice" :title "Invoice processing"})) "never itself"))
    (testing "a paraphrased competency question, not a different one"
      (is (= "q1" (:id (f/similar-question kg "Which rule is enforced by or checked in which system (e.g. contract required before work starts)?"))))
      (is (nil? (f/similar-question kg "Which team performs step X?"))))))

(deftest a-gap-reads-the-source-where-its-node-came-from
  (let [text (str (apply str (repeat 300 "a")) " Invoices are subject to a 10% PO tolerance. " (apply str (repeat 300 "b")))]
    (testing "a window around the node's evidence, not the start of the text"
      (let [w (f/source-window text ["[стр. 3] invoices are subject to a 10% PO tolerance"] 100)]
        (is (= 100 (count w)))
        (is (str/includes? w "10% PO tolerance"))))
    (testing "the title is the second anchor"
      (is (str/includes? (f/source-window text [nil "PO tolerance"] 100) "PO tolerance")))
    (testing "no anchor found, or a short text: the start"
      (is (= (subs text 0 100) (f/source-window text ["nowhere to be found"] 100)))
      (is (= "short" (f/source-window "short" ["x"] 100))))
    (testing "an anchor near the end still gives a full window"
      (is (= 100 (count (f/source-window (str text "the very end") ["the very end"] 100)))))))

(deftest a-graph-carries-its-own-ontology
  (testing "every event passes the schema"
    (is (every? schema/valid? shop-events) (pr-str (map schema/explain (remove schema/valid? shop-events)))))
  (let [kg (f/kg shop-events)
        p (f/profile kg)]
    (testing "the profile folded, grounded-in joined it"
      (is (= "shop" (:id p)))
      (is (some #(= :grounded-in (:id %)) (:relations p)))
      (is (not (profile/learnable? p)))
      (is (= :triggers (profile/ordering-relation p))))
    (testing "the guard speaks the profile's vocabulary"
      (is (= :unknown-relation (:error (f/guard kg (e :edge-accepted :edge {:from "invoice" :to "checkout" :type :requires :provenance :authored})))))
      (is (= :relation-misfit (:error (f/guard kg (e :edge-accepted :edge {:from "vat" :to "checkout" :type :performs :provenance :authored})))))
      (is (= :unknown-node-type (:error (f/guard kg (e :concept-accepted :concept (typed "x" "X" :metric))))))
      (is (= :unknown-node-type (:error (f/guard kg (e :concept-accepted :concept (concept "y" "Y")))))))
    (testing "the ordering relation stays a DAG"
      (is (= :requires-cycle (:error (f/guard kg (e :edge-accepted :edge {:from "checkout" :to "invoice" :type :triggers :provenance :authored}))))))
    (testing "the ontology is chosen once, for an empty graph"
      (is (= :profile-locked (:error (f/guard kg (e :profile-set :profile shop-profile)))))
      (is (= :profile-locked (:error (f/guard (f/kg (rest shop-events)) (e :profile-set :profile shop-profile))))))
    (testing "reserved relations are refused; an ontology without an ordering relation stands (2026-11-19)"
      (is (= :bad-profile (:error (f/guard f/empty-kg (e :profile-set :profile (update shop-profile :relations conj {:id :requires :label "x"}))))))
      (is (nil? (f/guard f/empty-kg (e :profile-set :profile (update shop-profile :relations #(mapv (fn [r] (dissoc r :ordering?)) %)))))))
    (testing "labels read in both directions"
      (is (= "запускается после" (profile/label p :triggers)))
      (is (= "запускает" (profile/label p :triggers true))))
    (testing "the learning engine is untouched: no requires edges, no closure"
      (is (= #{"invoice"} (set (f/requires-closure kg #{"invoice"})))))))

(deftest learning-graphs-fold-exactly-as-before
  (let [kg (f/kg sql-events)]
    (is (profile/learning? (:profile kg)))
    (is (= "learning" (:id (f/profile kg))))
    (is (profile/learnable? (f/profile kg)))
    (is (= :requires (profile/ordering-relation (f/profile kg))))
    (testing "a learning topic may carry a kind tag or none"
      (is (nil? (f/guard kg (e :concept-accepted :concept (typed "cte" "CTE" :procedure)))))
      (is (nil? (f/guard kg (e :concept-accepted :concept (concept "cte2" "CTE 2"))))))))

(deftest markdown-for-the-agent
  (let [kg (f/kg shop-events)
        md (export/markdown kg "Магазин")]
    (testing "header names the ontology"
      (is (str/includes? md "# Магазин"))
      (is (str/includes? md "`triggers` запускается после / запускает")))
    (testing "foundations first: checkout precedes invoice"
      (is (< (str/index-of md "## Оформление заказа") (str/index-of md "## Выставление счёта"))))
    (testing "relations in both directions with the inverse label"
      (is (str/includes? md "- запускается после → [Оформление заказа](#checkout)"))
      (is (str/includes? md "- запускает ← [Выставление счёта](#invoice)"))
      (is (str/includes? md "- выполняется ← [Бухгалтер](#accountant)"))
      (is (str/includes? md "- type: rule")))))

(deftest competency-questions-are-checked-by-the-fold
  (let [events (into shop-events
                     [(e :cq-raised :cq {:id "cq-who" :question "Кто выполняет процесс?"
                                         :pattern {:relation :performs :from-type :process :inverse? true}})
                      (e :cq-raised :cq {:id "cq-rule" :question "Какие правила регулируют процесс?"
                                         :pattern {:relation :governed-by :from-type :process}})
                      (e :cq-raised :cq {:id "cq-free" :question "Зачем компании этот процесс?"})])
        kg (f/kg events)]
    (testing "every event passes the schema"
      (is (every? schema/valid? events) (pr-str (map schema/explain (remove schema/valid? events)))))
    (testing "a pattern is checked over the graph: nodes of the type without the relation are the gaps"
      (let [{:keys [asked answered gaps]} (f/cq-check kg (get-in kg [:cqs "cq-rule"]))]
        (is (= 2 asked)) (is (= 1 answered)) (is (= ["checkout"] gaps))))
    (testing "an inverse pattern asks from the receiving end: the process with no performer is the gap"
      (let [{:keys [asked answered gaps]} (f/cq-check kg (get-in kg [:cqs "cq-who"]))]
        (is (= 2 asked)) (is (= 1 answered)) (is (= ["checkout"] gaps))))
    (testing "a question without a pattern is a person's to judge"
      (is (nil? (f/cq-check kg (get-in kg [:cqs "cq-free"])))))
    (testing "the report puts the widest gap first (ties by id); the node work queue is derived"
      (is (= ["cq-rule" "cq-who" "cq-free"] (map :id (f/cq-report kg))))
      (is (= ["cq-rule" "cq-who"] (map :id (get (f/cq-gaps kg) "checkout"))))
      (is (not (contains? (f/cq-gaps kg) "invoice")))
      (is (not (contains? (f/cq-gaps kg) "vat"))))
    (testing "the «no relation without a reader» rule, mechanical: triggers has no question"
      (is (= [:triggers] (f/unjustified-relations kg))))
    (testing "a pattern must name the profile's relation and type"
      (is (= :unknown-relation (:error (f/guard kg (e :cq-raised :cq {:id "x" :question "?" :pattern {:relation :requires}})))))
      (is (= :unknown-relation (:error (f/guard kg (e :cq-raised :cq {:id "x" :question "?" :pattern {:relation :performs :from-type :metric}}))))))
    (testing "retiring drops it"
      (is (nil? (get-in (f/kg (conj events (e :cq-retired :id "cq-free"))) [:cqs "cq-free"]))))))

(deftest the-ontology-grows-additively
  (let [kg (f/kg shop-events)
        rel {:id :measures :label "измеряет" :inverse "измеряется" :from #{:metric} :to #{:process}}]
    (testing "a relation naming an unknown type is refused; after the type joins, it passes"
      (is (= :bad-profile (:error (f/guard kg (e :relation-added :relation rel)))))
      (let [kg2 (f/kg (conj shop-events (e :node-type-added :node-type {:id :metric :label "метрика"})))]
        (is (nil? (f/guard kg2 (e :relation-added :relation rel))))
        (let [kg3 (f/kg (into shop-events [(e :node-type-added :node-type {:id :metric :label "метрика"})
                                           (e :relation-added :relation rel)]))]
          (is (profile/relation (f/profile kg3) :measures))
          (is (nil? (f/guard kg3 (e :concept-accepted :concept (typed "churn" "Отток" :metric))))))))
    (testing "a second ordering relation, a reserved id, a duplicate are refused"
      (is (= :bad-profile (:error (f/guard kg (e :relation-added :relation (assoc rel :from #{:process} :ordering? true))))))
      (is (= :bad-profile (:error (f/guard kg (e :relation-added :relation {:id :requires :label "x"})))))
      (is (= :bad-profile (:error (f/guard kg (e :relation-added :relation {:id :performs :label "x"}))))))
    (testing "the learning profile does not grow this way"
      (is (= :profile-locked (:error (f/guard (f/kg sql-events) (e :relation-added :relation rel))))))))

;; wiki/Property-Graph.md: one `change` event for every graph; an ontology
;; graph is an ordinary graph whose vocabulary is READ OUT of its nodes and
;; edges; a data graph carries a copy of it and never takes one that breaks
;; its data
(defn- ch [& ops] (e :change :ops (vec ops)))
(defn- ch-ops [ops] (e :change :ops (vec ops)))

(def shop-ontology
  [(ch [:graph-set {:ontology "ontology" :title "Магазин"}]
       [:node-set "process" {:title "процесс" :definition "Шаг работы." :color "#2fa36b" :learnable? true}]
       [:node-set "role" {:title "роль"}]
       [:node-set "rule" {:title "правило"}]
       [:edge-set "process" :triggers "process" {:label "запускается после" :inverse "запускает" :ordering? true}]
       [:edge-set "role" :performs "process" {:label "выполняет" :inverse "выполняется"}]
       [:edge-set "process" :governed-by "rule" {:label "регулируется"}])])

(deftest an-ontology-is-an-ordinary-graph
  (let [onto (f/kg shop-ontology)
        v (f/vocabulary onto)]
    (testing "a graph property makes it an ontology graph; its nodes and edges are real"
      (is (:ontology-graph? onto))
      (is (= #{"process" "role" "rule"} (set (keys (:concepts onto)))))
      (is (= 3 (count (:edges onto)))))
    (testing "its vocabulary is read out of them, properties and all"
      (is (= [:process :role :rule] (mapv :id (:node-types v))))
      (is (= {:id :process :label "процесс" :description "Шаг работы." :color "#2fa36b" :learnable? true}
             (first (:node-types v))))
      (let [perf (profile/relation v :performs)]
        (is (= {:label "выполняет" :inverse "выполняется" :from #{:role} :to #{:process}}
               (select-keys perf [:label :inverse :from :to]))))
      (is (= :triggers (profile/ordering-relation v)))
      (is (nil? (profile/problems v)) "whole, so a data graph can take it"))
    (testing "the same events edit it: properties merge, nil removes, a second pair widens a relation"
      (let [k (f/kg (conj shop-ontology
                          (ch [:node-set "role" {:title "исполнитель" :color "#c94f7c"}]
                              [:node-set "process" {:learnable? nil}]
                              [:node-set "system" {:title "система"}]
                              [:edge-set "system" :performs "process" {}])))
            v (f/vocabulary k)]
        (is (= "исполнитель" (:label (profile/node-type-of v :role))))
        (is (not (:learnable? (profile/node-type-of v :process))))
        (is (= #{:role :system} (:from (profile/relation v :performs))))))
    (testing "removing a type takes its relations' edges with it"
      (let [v (f/vocabulary (f/kg (conj shop-ontology (ch [:node-remove "rule"]))))]
        (is (nil? (profile/node-type-of v :rule)))
        (is (nil? (profile/relation v :governed-by)))))
    (testing "an ontology graph has no conformance rules: any relation id, no usage checks"
      (is (nil? (f/guard onto (ch [:edge-set "rule" :mentions "role" {:label "упоминает"}]))))
      (is (nil? (f/guard onto (ch [:node-remove "process"])))))
    (testing "the vocabulary it would leave stays sound: no reserved id, one ordering relation at most"
      (is (= :bad-profile (:error (f/guard onto (ch [:edge-set "role" :requires "process" {}])))))
      (is (= :bad-profile (:error (f/guard onto (ch [:edge-set "role" :performs "process" {:ordering? true}]))))))
    (testing "the structural rules hold everywhere"
      (is (= :missing-endpoint (:error (f/guard onto (ch [:edge-set "process" :x "nowhere" {}])))))
      (is (= :missing (:error (f/guard onto (ch [:node-remove "nowhere"])))))
      (is (= :invalid-schema (:error (f/guard onto (ch [:node-set "role" {:status :deprecated}])))) "the fold's own keys are not set by ops")
      (is (= :missing-endpoint (:error (f/guard onto (ch [:node-set "a" {:title "A"}] [:edge-set "a" :x "b" {}]))))
          "a change is refused whole at its first bad op"))
    (testing "an ontology graph's own nodes are never learnable"
      (is (not (profile/learnable? (f/profile onto))))
      (is (= "выполняет" (profile/label (f/profile onto) :performs)) "its edges read by their relation's name"))
    (testing "a data graph binds by a copy, reads it, and keeps vocabulary edits out"
      (let [bind (ch [:graph-set {:ontology "o-shop" :ontology-version "v1" :ontology-copy (assoc v :id "o-shop")}])
            bound (f/kg (into [bind] (rest shop-events)))]
        (is (= {:graph "o-shop" :version "v1"} (:ontology bound)))
        (is (= 4 (count (:concepts bound))))
        (is (= :ontology-elsewhere (:error (f/guard bound (e :node-type-added :node-type {:id :metric :label "метрика"})))))
        (testing "a node property edit passes; a kind outside the ontology does not"
          (is (nil? (f/guard bound (ch [:node-set "invoice" {:sla "2 дня" :owner "финансы"}]))))
          (is (= "2 дня" (get-in (f/kg (into [bind] (conj (vec (rest shop-events)) (ch [:node-set "invoice" {:sla "2 дня"}])))) [:concepts "invoice" :sla])))
          (is (= :unknown-node-type (:error (f/guard bound (ch [:node-set "invoice" {:kind :planet}]))))))
        (testing "conformance holds for new edges in a data graph"
          (is (= :relation-misfit (:error (f/guard bound (ch [:edge-set "vat" :performs "invoice" {}]))))))
        (testing "a copy that drops a type or relation in use is refused, with what it would break"
          (let [no-rule (-> v (assoc :id "o-shop")
                            (update :node-types #(vec (remove (comp #{:rule} :id) %)))
                            (update :relations #(vec (remove (comp #{:governed-by} :id) %))))
                err (f/guard bound (ch [:graph-set {:ontology-version "v2" :ontology-copy no-rule}]))]
            (is (= :ontology-breaks-data (:error err)))
            (is (= {:node-types [:rule] :relations [:governed-by]} (:detail err)))))
        (testing "competency questions are raised in the ontology, travel in the copy and are checked over the data"
          (let [q {:id "cq-who" :question "Кто выполняет процесс?" :pattern {:relation :performs :from-type :process :inverse? true}}]
            (is (nil? (f/guard onto (e :cq-raised :cq q))) "an ontology graph asks of its own vocabulary")
            (let [with-q (f/kg (into [(ch [:graph-set {:ontology "o-shop" :ontology-version "v2"
                                                       :ontology-copy (assoc v :id "o-shop") :ontology-cqs [q]}])]
                                     (rest shop-events)))
                  check (:check (first (filter #(= "cq-who" (:id %)) (f/cq-report with-q))))]
              (is (:inherited? (get-in with-q [:cqs "cq-who"])))
              (is (= 2 (:asked check)) "asked of every process in THIS graph")
              (is (= #{"checkout"} (set (:gaps check))) "and the one nobody performs is the gap")
              (is (= :ontology-elsewhere (:error (f/guard with-q (e :cq-retired :id "cq-who"))))
                  "an inherited question is retired in its ontology graph"))))
        (testing "a MIGRATION — the new copy with the data retyped — is judged whole (2026-11-20)"
          (let [no-rule (-> v (assoc :id "o-shop")
                            (update :node-types #(-> (vec (remove (comp #{:rule} :id) %)) (conj {:id :law :label "закон"})))
                            (update :relations #(-> (vec (remove (comp #{:governed-by} :id) %))
                                                    (conj {:id :bound-by :label "подчиняется" :from #{:process} :to #{:law}}))))
                copy [:graph-set {:ontology-version "v2" :ontology-copy no-rule}]]
            (is (nil? (f/guard bound (ch copy [:node-set "vat" {:kind :law}]
                                         [:edge-remove "invoice" :governed-by "vat"]
                                         [:edge-set "invoice" :bound-by "vat" {}])))
                "retyped to a type only the new version has, relinked by a new relation: accepted")
            (is (nil? (f/guard bound (ch copy [:node-set "vat" {:kind :law}] [:edge-remove "invoice" :governed-by "vat"])))
                "or the dropped relation's links simply removed")
            (is (= :ontology-breaks-data (:error (f/guard bound (ch copy [:node-set "vat" {:kind :law}]))))
                "a dropped relation still used is not left without meaning")
            (is (= :relation-misfit (:error (f/guard bound (ch copy [:node-set "vat" {:kind :law}]
                                                             [:edge-remove "invoice" :governed-by "vat"]
                                                             [:edge-set "vat" :bound-by "invoice" {}]))))
                "and every edge must fit its relation in the new version")))
        (testing "a copy that only adds passes"
          (is (nil? (f/guard bound (ch [:graph-set {:ontology-version "v2"
                                                    :ontology-copy (update (assoc v :id "o-shop") :node-types conj {:id :metric :label "метрика"})}])))))))))

(deftest an-edit-is-reversed-not-erased
  ;; undo (2026-11-20): a new change whose ops put back what an event did
  (let [base (f/kg shop-events)
        undo (fn [evs e] (let [before (f/kg evs) ops (f/undo-ops before e)]
                           (f/kg (conj (conj evs e) (ch-ops ops)))))
        same? (fn [a b] (and (= (set (keys (:concepts a))) (set (keys (:concepts b))))
                             (= (set (map (juxt :from :type :to) (:edges a))) (set (map (juxt :from :type :to) (:edges b))))))]
    (testing "a property set, then undone"
      (let [e (ch [:node-set "invoice" {:sla "2 дня" :title "Счёт"}])
            k (undo shop-events e)]
        (is (nil? (get-in k [:concepts "invoice" :sla])))
        (is (= "Выставление счёта" (get-in k [:concepts "invoice" :title])))))
    (testing "a node removed — with its links — comes back"
      (let [k (undo shop-events (ch [:node-remove "invoice"]))]
        (is (same? base k))))
    (testing "the editor's own events"
      (is (same? base (undo shop-events (e :concept-accepted :concept (typed "x" "Икс" :process)))))
      (is (same? base (undo shop-events (e :concept-discarded :id "invoice"))))
      (is (same? base (undo shop-events (e :edge-accepted :edge {:from "accountant" :to "checkout" :type :performs :provenance :authored}))))
      (is (= "Выставление счёта" (get-in (undo shop-events (e :version-created :entity :concept :id "invoice" :change :patch
                                                              :payload {:title "Новое имя"}))
                                         [:concepts "invoice" :title]))))
    (testing "a change of several ops is reversed as a whole, last op first"
      (let [k (undo shop-events (ch [:node-set "z" {:title "Зет" :kind :rule}] [:edge-set "invoice" :governed-by "z" {}]))]
        (is (same? base k))))
    (testing "an undo is itself a valid change"
      (let [e (ch [:node-remove "invoice"])
            evs (conj shop-events e)]
        (is (nil? (f/guard (f/kg evs) (ch-ops (f/undo-ops (f/kg shop-events) e)))))))))

(deftest an-ontology-that-can-grow-hears-about-unfitting-pairs
  ;; slice 4 (2026-11-20): role ↔ rule has no relation in the shop ontology;
  ;; a signalled pair of them is asked only when the ontology can grow
  (let [kg (f/kg shop-events)
        batch [{:id "boss" :title "Главный бухгалтер" :definition "Ведёт учёт." :kind :role}
               {:id "tax" :title "Налог" :definition "Его проверяет Главный бухгалтер." :kind :rule}]
        pair? (fn [cs] (some #(= #{"boss" "tax"} #{(:a %) (:b %)}) cs))]
    (is (not (pair? (f/edge-candidates kg batch 20))) "closed: no relation fits, never asked")
    (let [c (first (filter #(= #{"boss" "tax"} #{(:a %) (:b %)}) (f/edge-candidates kg batch 20 {:open? true})))]
      (is (some? c) "open: the signalled pair is asked")
      (is (= [] (:fits c)) "with no relation to suggest"))))

(deftest properties-reach-every-reader
  ;; 2026-11-19: what a person sets on a node or an edge in the popup must
  ;; reach the agent export, the RDF and the read tools — not only the app
  (let [kg (f/kg (conj shop-events
                       (ch [:node-set "invoice" {:sla "2 дня" :owner "финансы"}]
                           [:edge-set "accountant" :performs "invoice" {:since "2024" :share "30 %"}])))
        md (export/markdown kg "Магазин")
        ttl (rdf/turtle kg {:title "Магазин" :base "urn:loretile:shop/" :lang "ru"})
        node (gt/open-node {:kg kg} "invoice")
        edges (gt/list-edges kg :performs nil)]
    (testing "the fold keeps them, apart from its own keys"
      (is (= [[:owner "финансы"] [:sla "2 дня"]] (vec (f/node-props (get-in kg [:concepts "invoice"])))))
      (let [e (first (filter #(= :performs (:type %)) (:edges kg)))]
        (is (= [[:share "30 %"] [:since "2024"]] (vec (f/edge-props e))))
        (is (= "denis" (:changed-by e)) "an edge-set says who changed the edge")))
    (testing "the agent markdown"
      (is (str/includes? md "- owner: финансы"))
      (is (str/includes? md "- sla: 2 дня"))
      (is (str/includes? md "{share: 30 %; since: 2024}") "an edge's properties ride on its line"))
    (testing "the RDF: node properties under their key, edge properties on a reified statement"
      (is (str/includes? ttl "@prefix vp: <urn:loretile:shop/vocab/prop#>"))
      (is (re-find #"vp:sla \"2 дня\"@ru" ttl))
      (is (re-find #"(?s)rdf:Statement ;\s+rdf:subject <urn:loretile:shop/node/accountant> ;\s+rdf:predicate v:performs ;\s+rdf:object <urn:loretile:shop/node/invoice> ;\s+vp:share \"30 %\"@ru ;\s+vp:since \"2024\"@ru" ttl)))
    (testing "the read tools"
      (is (str/includes? node "properties: owner = финансы; sla = 2 дня"))
      (is (str/includes? edges "{share = 30 %; since = 2024}")))))

(deftest the-fold-sees-ontology-pitfalls
  (let [profile (update shop-profile :relations conj {:id :related :label "связан с" :from #{:process} :to #{:process}})
        events [(e :profile-set :profile profile)
                (e :concept-accepted :concept (typed "a" "A" :process))
                (e :concept-accepted :concept (typed "b" "B" :process))
                (e :concept-accepted :concept (typed "lonely" "Один" :process))
                (e :edge-accepted :edge {:from "a" :to "b" :type :related :provenance :authored})
                (e :edge-accepted :edge {:from "b" :to "a" :type :related :provenance :authored})]
        kg (f/kg events)
        pits (f/ontology-pitfalls kg)
        kinds (frequencies (map :kind pits))]
    (testing "a directed relation used both ways between two nodes, once"
      (is (= 1 (:symmetric-use kinds)))
      (is (= ["b" "a" :related] (:detail (first (filter #(= :symmetric-use (:kind %)) pits))))))
    (testing "a node with no edge; types and relations nobody uses"
      (is (= ["lonely"] (map :detail (filter #(= :isolated (:kind %)) pits))))
      (is (= #{:role :rule} (set (map :detail (filter #(= :unused-type (:kind %)) pits)))))
      (is (= #{:triggers :performs :governed-by} (set (map :detail (filter #(= :unused-relation (:kind %)) pits))))))
    (testing "a learning graph reports none; an empty custom graph reports none"
      (is (empty? (f/ontology-pitfalls (f/kg sql-events))))
      (is (empty? (f/ontology-pitfalls (f/kg [(e :profile-set :profile shop-profile)])))))))

(deftest the-read-tools-speak-any-ontology
  (let [kg (f/kg (into shop-events
                       [(e :cq-raised :cq {:id "cq-who" :question "Кто выполняет процесс?"
                                           :pattern {:relation :performs :from-type :process :inverse? true}})]))]
    (testing "open-node reads a custom graph's relations both ways, in its words"
      (let [s (gt/open-node {:kg kg} "invoice")]
        (is (str/includes? s "[process: процесс]"))
        (is (str/includes? s "запускается после: Оформление заказа"))
        (is (str/includes? s "выполняется ← Бухгалтер"))
        (is (str/includes? s "регулируется: НДС на цифровые товары"))
        (is (not (str/includes? s "questions:")) "no question counts outside learning")))
    (testing "material: clipped for a prompt, whole for an agent, no learning level on a business node"
      (let [long-list (str "Exceptions: " (str/join "; " (map #(str "case " %) (range 1 40))))
            kg2 (f/kg (conj shop-events (e :object-added :object {:id "o-ex" :concept-id "invoice" :kind :explanation
                                                                   :level :novice :locale "ru" :text long-list})))
            short-view (gt/open-node {:kg kg2} "invoice")
            agent-view (gt/open-node {:kg kg2 :material-chars 4000} "invoice")]
        (is (not (str/includes? short-view "case 39")))
        (is (str/includes? agent-view "case 39"))
        (is (str/includes? agent-view "[explanation, ru]"))
        (is (not (str/includes? agent-view "novice")))))
    (testing "the node's unanswered competency questions ride along"
      (is (str/includes? (gt/open-node {:kg kg} "checkout") "unanswered competency questions: Кто выполняет процесс?"))
      (is (not (str/includes? (gt/open-node {:kg kg} "invoice") "unanswered"))))
    (testing "search, list (foundations first, by type), describe, check"
      (is (str/starts-with? (gt/search-nodes kg nil "счёт") "invoice — Выставление счёта [process]"))
      (is (= ["checkout — Оформление заказа [process]" "invoice — Выставление счёта [process]"]
             (str/split-lines (gt/list-nodes kg :process))))
      (is (str/includes? (gt/describe-ontology kg) "- triggers — запускается после (process → process) [ordering]"))
      (is (str/includes? (gt/describe-ontology kg) "reads from the LATER node to the EARLIER one"))
      (is (str/includes? (gt/check-cqs kg) "answered 1/2; gaps: Оформление заказа"))
      (is (= "Бухгалтер (accountant) —выполняет→ Выставление счёта (invoice)" (gt/list-edges kg :performs nil)))
      (is (= 3 (count (str/split-lines (gt/list-edges kg nil "invoice")))) "every relation around one node")
      (is (str/includes? (gt/list-edges kg nil nil 1) "… and 2 more")))
    (testing "a learning graph reads as before"
      (let [s (gt/open-node {:kg (f/kg sql-events)} "group-by")]
        (is (str/includes? s "requires: Фильтры WHERE"))
        (is (str/includes? s "required by: Агрегатные функции"))
        (is (str/includes? s "questions: 3"))))))

;; What a MODEL reads (2026-10-21, after Netflix's UDA): what each type and
;; relation means, who owns the graph, which links are the model's own
;; inference, material that search can reach — and the same graph as RDF.
(deftest the-graph-tells-a-model-what-it-means
  (let [inferred {:from "accountant" :to "checkout" :type :performs :provenance :llm-drafted
                  :justification "the handbook says accounting closes every order" :inferred? true}
        evs (into shop-events
                  [(e :vocabulary-described :of :relation :id :performs :description "Роль → процесс: роль делает этот процесс.")
                   (e :vocabulary-described :of :node-type :id :rule :description "Обязательное условие.")
                   (e :graph-owner-set :owner {:team "Финансы" :contact "fin@example.com"})
                   (e :edge-accepted :edge inferred)
                   (e :object-added :object {:id "o-tol" :concept-id "invoice" :kind :explanation :level :novice :locale "ru"
                                             :text "Счёт оплачивается, если расхождение с заказом не больше 10% (tolerance)."})])
        kg (f/kg evs)]
    (testing "a description is words on the custom vocabulary, nothing else"
      (is (= "Роль → процесс: роль делает этот процесс." (:description (profile/relation (f/profile kg) :performs))))
      (is (= "Обязательное условие." (:description (profile/node-type-of (f/profile kg) :rule))))
      (is (= :profile-locked (:error (f/guard (f/kg sql-events) (e :vocabulary-described :of :relation :id :requires :description "x")))))
      (is (= :unknown-relation (:error (f/guard kg (e :vocabulary-described :of :relation :id :nope :description "x")))))
      (is (= :unknown-relation (:error (f/guard kg (e :vocabulary-described :of :relation :id :grounded-in :description "x"))))
          "the reserved source link keeps its own words")
      (is (= :unknown-node-type (:error (f/guard kg (e :vocabulary-described :of :node-type :id :nope :description "x")))))
      (is (nil? (:description (profile/node-type-of (f/profile (f/kg (conj evs (e :vocabulary-described :of :node-type :id :rule :description " ")))) :rule)))
          "an empty description clears it"))
    (testing "describe-ontology: the owner first, then each word with its meaning"
      (let [d (gt/describe-ontology kg)]
        (is (str/includes? d "owner: Финансы (fin@example.com)"))
        (is (str/includes? d "- performs — выполняет (role → process): Роль → процесс: роль делает этот процесс."))
        (is (str/includes? d "- rule — правило: Обязательное условие."))))
    (testing "an owner names a team"
      (is (= :invalid-schema (:error (f/guard kg (e :graph-owner-set :owner {:team "  "})))))
      (is (= {:team "Финансы"} (:owner (f/kg (conj evs (e :graph-owner-set :owner {:team " Финансы " :contact ""}))))))
      (is (str/includes? (gt/describe-ontology (f/kg sql-events)) "ontology: Learning") "no owner, no owner line")
      (is (not (str/includes? (gt/describe-ontology (f/kg sql-events)) "owner:"))))
    (testing "the learning profile explains its own relations"
      (is (str/includes? (gt/describe-ontology (f/kg sql-events)) "B is a prerequisite of A")))
    (testing "an inferred link says so, with its reason, everywhere an agent reads"
      (is (str/includes? (gt/open-node {:kg kg} "checkout")
                         "выполняется ← Бухгалтер (inferred: the handbook says accounting closes every order)"))
      (is (str/includes? (gt/list-edges kg :performs nil) "Оформление заказа (checkout) (inferred: the handbook"))
      (is (not (str/includes? (gt/open-node {:kg kg} "invoice") "inferred")) "a stated link reads as before")
      (let [md (export/markdown kg "Магазин")]
        (is (str/includes? md "Owner: Финансы (fin@example.com)"))
        (is (str/includes? md "- `performs` выполняет / выполняется: Роль → процесс"))
        (is (str/includes? md "*inferred, not stated in the source*: the handbook says"))))
    (testing "search reaches material, and shows the words that matched"
      (let [s (gt/search-nodes kg nil "tolerance")]
        (is (str/starts-with? s "invoice — Выставление счёта [process]"))
        (is (str/includes? s "material: …") s)
        (is (str/includes? s "10% (tolerance)")))
      (is (not (str/includes? (gt/search-nodes kg nil "счёт") "material:")) "a title hit needs no snippet"))
    (testing "search forgives ё and word endings, and says where to go when it finds nothing"
      (doseq [q ["счет" "счета" "счёту" "выставлении счетов"]]
        (is (str/starts-with? (gt/search-nodes kg nil q) "invoice — Выставление счёта") q))
      (is (= ["счет" "счет" "invoice" "ро"] (vec (gt/stems "Счёт счета invoices ро"))))
      (let [none (gt/search-nodes kg nil "zzzz")]
        (is (str/includes? none "no node matches «zzzz» (4 nodes in the graph)"))
        (is (str/includes? none "list-nodes"))))
    (testing "the graph as RDF: classes, properties, nodes, edges, the inference, the owner"
      (let [ttl (rdf/turtle kg {:title "Магазин" :base "urn:loretile:shop/" :lang "ru"})]
        (is (str/includes? ttl "@prefix v: <urn:loretile:shop/vocab#> ."))
        (is (str/includes? ttl "v:rule a owl:Class ;\n    rdfs:label \"правило\"@ru ;\n    rdfs:comment \"Обязательное условие.\"@ru ."))
        (is (str/includes? ttl "v:performs a owl:ObjectProperty ;"))
        (is (str/includes? ttl "rdfs:domain v:role ;"))
        (is (str/includes? ttl "lt:ordering true"))
        (is (str/includes? ttl "<urn:loretile:shop/node/invoice> a v:process ;\n    rdfs:label \"Выставление счёта\"@ru"))
        (is (str/includes? ttl "lt:material \"Счёт оплачивается, если расхождение с заказом не больше 10% (tolerance).\"@ru"))
        (is (str/includes? ttl "<urn:loretile:shop/node/invoice> v:triggers <urn:loretile:shop/node/checkout> ."))
        (is (str/includes? ttl "rdf:subject <urn:loretile:shop/node/accountant> ;\n    rdf:predicate v:performs ;\n    rdf:object <urn:loretile:shop/node/checkout> ;\n    lt:inferred true"))
        (is (str/includes? ttl "lt:owner [ a lt:Owner ; lt:team \"Финансы\" ; lt:contact \"fin@example.com\" ]"))
        (is (= 1 (count (re-seq #"rdf:Statement" ttl))) "only the inferred link is reified")))
    (testing "RDF of a learning graph: concepts, English vocabulary, kinds as tags, escaped text"
      (let [ttl (rdf/turtle (f/kg (conj sql-events (e :concept-accepted :concept {:id "q\"t" :title "Кавычка \" и\nстрока" :definition "d"})))
                            {:title "SQL" :base "urn:loretile:sql/" :lang "ru"})]
        (is (str/includes? ttl "v:requires a owl:ObjectProperty ;\n    rdfs:label \"requires\"@en"))
        (is (str/includes? ttl "a v:concept ;"))
        (is (str/includes? ttl "<urn:loretile:sql/node/q%22t>"))
        (is (str/includes? ttl "rdfs:label \"Кавычка \\\" и\\nстрока\"@ru"))))))

;; A new version of a source flags what changed, not the whole page (the
;; change test 2026-10-23: 207 topics flagged where 5 facts had changed).
(deftest a-new-version-flags-only-what-changed
  (let [old-text "# AP\n\nAll invoices are subject to a 10% PO tolerance.\n\nPayment Runs are completed on Thursdays.\n\nVendors email ap@example.com."
        new-text "# AP\n\nAll invoices are subject to a 5% PO tolerance.\n\nPayment   Runs are completed on Thursdays.\n\nVendors email ap@example.com."
        kg (f/kg [(e :profile-set :profile shop-profile)
                  (e :source-added :source {:id "src-ap" :title "ap.md" :ref "src-ap.md"})
                  (e :concept-accepted :concept (assoc (typed "tolerance" "10% tolerance" :rule) :source-id "src-ap" :evidence "All invoices are subject to a 10% PO tolerance."))
                  (e :concept-accepted :concept (assoc (typed "run" "Weekly run" :process) :source-id "src-ap" :evidence "Payment Runs are completed on Thursdays."))
                  (e :concept-accepted :concept (assoc (typed "inbox" "AP inbox" :process) :source-id "src-ap"))
                  (e :concept-accepted :concept (assoc (typed "ghost" "Ghost" :process) :source-id "src-ap" :evidence "A sentence that was never in the page at all."))])
        topics ["tolerance" "run" "inbox" "ghost"]]
    (testing "a quote gone from the new text changed; one still there (spaces aside) is kept"
      (is (= {:changed ["tolerance"] :kept ["run"] :unchecked ["inbox" "ghost"]}
             (f/sort-by-quote kg topics old-text new-text))))
    (testing "without the old text, a quote missing from the new text counts as changed"
      (is (= {:changed ["tolerance" "ghost"] :kept ["run"] :unchecked ["inbox"]}
             (f/sort-by-quote kg topics nil new-text))))
    (testing "without the new text nothing can be checked"
      (is (= {:unchecked topics} (f/sort-by-quote kg topics old-text nil))))
    (testing "an agent lists what is stale and reads the quote each was written from"
      (let [kg2 (f/kg [(e :profile-set :profile shop-profile)
                       (e :source-added :source {:id "src-ap" :title "ap.md" :ref "src-ap.md"})
                       (e :concept-accepted :concept (assoc (typed "tolerance" "10% tolerance" :rule) :source-id "src-ap" :evidence "All invoices are subject to a 10% PO tolerance."))
                       (e :concept-accepted :concept (assoc (typed "run" "Weekly run" :process) :source-id "src-ap" :evidence "Payment Runs are completed on Thursdays."))
                       (e :source-updated :id "src-ap" :change :material :concepts ["tolerance"])
                       (e :source-updated :id "src-ap" :change :patch :concepts ["run"])])]
        (is (= "tolerance — 10% tolerance [rule]" (gt/list-nodes kg2 nil true)))
        (is (= "no stale nodes: no source changed under what the graph says" (gt/list-nodes (f/kg shop-events) nil true)))
        (is (str/includes? (gt/open-node {:kg kg2} "tolerance") "quote from its source: «All invoices are subject to a 10% PO tolerance.»"))))))

;; An ontology from Protégé, TopBraid or VocBench, saved as Turtle, becomes
;; a graph's profile (2026-10-23 — the reverse of the RDF export).
(def protege-ttl "
@prefix : <http://example.org/shop#> .
@prefix owl: <http://www.w3.org/2002/07/owl#> .
@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
@prefix skos: <http://www.w3.org/2004/02/skos/core#> .

<http://example.org/shop> rdf:type owl:Ontology ;
    rdfs:label \"Shop operations\"@en , \"Как работает магазин\"@ru ;
    rdfs:comment '''How the shop runs;
its processes and roles.'''@en .

# classes
:Process rdf:type owl:Class ; rdfs:label \"process\"@en , \"процесс\"@ru ;
    skos:definition \"A sequence of work.\"@en .
:Role a owl:Class ; rdfs:label \"role\"@en .
:BusinessRule a owl:Class .
:Team a owl:Class ; rdfs:subClassOf :Role .

# object properties
:comesAfter a owl:ObjectProperty ; rdfs:label \"comes after\"@en ;
    rdfs:domain :Process ; rdfs:range :Process .
:performs a owl:ObjectProperty ; rdfs:label \"performs\" ;
    rdfs:domain [ a owl:Class ; owl:unionOf ( :Role :Team ) ] ; rdfs:range :Process ;
    owl:inverseOf :performedBy .
:performedBy a owl:ObjectProperty ; rdfs:label \"is performed by\" .
:governedBy a owl:ObjectProperty ; rdfs:range :BusinessRule ; rdfs:comment \"X must follow the rule.\" .
:partOf a owl:ObjectProperty , owl:TransitiveProperty ; rdfs:domain :Process ; rdfs:range :Process .
:relatedTo a owl:ObjectProperty , owl:SymmetricProperty .

# what Loretile does not model
:amount a owl:DatatypeProperty ; rdfs:range xsd:decimal .
:Checkout a :Process ; :amount 12.5 .
[] a owl:Restriction ; owl:onProperty :performs ; owl:someValuesFrom :Role .
")

(deftest an-ontology-comes-in-as-turtle
  (testing "the parser reads what a modelling tool writes"
    (let [{:keys [triples]} (turtle/parse protege-ttl)]
      (is (some #(= % ["http://example.org/shop#Team" "http://www.w3.org/2000/01/rdf-schema#subClassOf" "http://example.org/shop#Role"]) triples))
      (is (some #(= (nth % 2) {:value "How the shop runs;\nits processes and roles." :lang "en"}) triples) "long strings keep their newline")
      (is (some #(= (nth % 2) {:value "12.5" :datatype "http://www.w3.org/2001/XMLSchema#decimal"}) triples))
      (is (= 2 (count (filter #(= "http://www.w3.org/1999/02/22-rdf-syntax-ns#first" (second %)) triples))) "the union is a collection")))
  (let [{:keys [profile report error]} (turtle/profile (turtle/parse protege-ttl) {:lang "ru"})
        rel (fn [id] (first (filter #(= id (:id %)) (:relations profile))))]
    (testing "classes become node types, labels in the graph's language first"
      (is (nil? error))
      (is (= [:process :role :business-rule :team] (mapv :id (:node-types profile))))
      (is (= "процесс" (:label (first (:node-types profile)))))
      (is (= "A sequence of work." (:description (first (:node-types profile)))))
      (is (= "business rule" (:label (nth (:node-types profile) 2))) "no label: the local name, in words")
      (is (= "Как работает магазин" (:title profile)))
      (is (= "How the shop runs;\nits processes and roles." (:brief profile)) "the English comment when there is no Russian one"))
    (testing "object properties become relations: endpoints, unions, inverse, flags"
      (is (= {:id :performs :label "performs" :from #{:role :team} :to #{:process} :inverse "is performed by"} (rel :performs)))
      (is (nil? (rel :performed-by)) "the inverse is the same relation read backwards, not a second one")
      (is (= #{:business-rule} (:to (rel :governed-by))))
      (is (nil? (:from (rel :governed-by))) "no domain: any type")
      (is (= "X must follow the rule." (:description (rel :governed-by))))
      (is (:symmetric? (rel :related-to)))
      (is (:acyclic? (rel :part-of-x)) "transitive → acyclic"))
    (testing "the order: a relation from a type to itself whose name reads later → earlier"
      (is (:ordering? (rel :comes-after)))
      (is (= 1 (count (filter :ordering? (:relations profile)))))
      (is (= "comes-after" (:ordering report))))
    (testing "learning's reserved ids are renamed, and the report says so"
      (is (rel :part-of-x))
      (is (= ["part-of → part-of-x"] (:renamed report))))
    (testing "what Loretile does not model is counted, never silently dropped"
      (is (= {:data-properties 1 :individuals 1 :restrictions 1} (:skipped report))))
    (testing "the profile is sound and folds into a graph"
      (is (nil? (profile/problems profile)))
      (is (nil? (profile/problems (profile/normalize profile))))
      (is (nil? (f/guard f/empty-kg (e :profile-set :profile profile))))))
  (testing "no order relation in the file: a neutral one is added, never a guessed direction"
    (let [{:keys [profile report]} (turtle/profile (turtle/parse "@prefix : <http://x#> . @prefix owl: <http://www.w3.org/2002/07/owl#> .
:A a owl:Class . :precedes a owl:ObjectProperty ; <http://www.w3.org/2000/01/rdf-schema#domain> :A ; <http://www.w3.org/2000/01/rdf-schema#range> :A .") {})]
      (is (not (:ordering? (first (:relations profile)))) "«precedes» reads earlier → later: never the order")
      (is (= {:id :comes-after :label "comes after" :inverse "comes before"} (select-keys (last (:relations profile)) [:id :label :inverse])))
      (is (:ordering-added report))
      (is (nil? (profile/problems profile)))))
  (testing "a file with no class, and a broken file, are refused in words"
    (is (= "no classes (owl:Class or rdfs:Class) in the file" (:error (turtle/profile (turtle/parse "@prefix : <http://x#> . :a :b :c .") {}))))
    (is (thrown-with-msg? #?(:cljs js/Error :clj Exception) #"unknown prefix" (turtle/parse ":a :b :c .")))
    (is (thrown-with-msg? #?(:cljs js/Error :clj Exception) #"unclosed string" (turtle/parse "<a> <b> \"oops ."))))
  (testing "round trip: a Loretile graph's RDF export comes back as the same vocabulary"
    (let [kg (f/kg (into shop-events [(e :vocabulary-described :of :relation :id :performs :description "Роль → процесс.")]))
          ttl (rdf/turtle kg {:title "Магазин" :base "urn:loretile:shop/" :lang "ru"})
          {:keys [profile report]} (turtle/profile (turtle/parse ttl) {:lang "ru"})
          strip (fn [p] {:types (mapv #(select-keys % [:id :label :description]) (:node-types p))
                         :rels (mapv #(select-keys % [:id :label :inverse :from :to :ordering? :acyclic? :symmetric? :description])
                                     (remove #(= :grounded-in (:id %)) (:relations p)))})]
      (is (= (strip (profile/normalize shop-profile)) (update (strip profile) :rels #(mapv (fn [r] (dissoc r :description)) %)))
          "types, relations, labels, inverses, endpoints and the order survive")
      (is (= "Роль → процесс." (:description (first (filter #(= :performs (:id %)) (:relations profile))))))
      (is (= 4 (get-in report [:skipped :individuals])) "the graph's own nodes are counted, not imported"))))

;; Who changed what (2026-10-21: every event said «denis»). The actor is a
;; device id; `author-named` in the graph's own log says whose it is.
(deftest every-change-names-its-author
  (let [evs [(e :profile-set :profile shop-profile)
             (e :author-named :author {:id "a-anna" :name "Анна"} :ev/actor "a-anna")
             (e :concept-accepted :concept (typed "invoice" "Счёт" :process) :ev/actor "a-anna" :ev/ts 1792540800000)
             (e :object-added :object {:id "o1" :concept-id "invoice" :kind :explanation :level :novice :locale "ru" :text "текст"}
                :ev/actor "a-boris" :ev/ts 1792627200000)]
        kg (f/kg evs)
        c (get-in kg [:concepts "invoice"])]
    (testing "the fold keeps who added a topic and who changed it last, and when"
      (is (= ["a-anna" "a-boris" 1792627200000] [(:added-by c) (:changed-by c) (:changed-at c)]))
      (is (= "Анна" (f/author-name kg "a-anna")))
      (is (= "a-boris" (f/author-name kg "a-boris")) "an unnamed author reads as their id")
      (is (= "denis" (f/author-name (f/kg sql-events) "denis")) "old logs read as before"))
    (testing "only an author names themself, and with a name"
      (is (= :invalid-schema (:error (f/guard kg (e :author-named :author {:id "a-boris" :name "Борис"} :ev/actor "a-anna")))))
      (is (= :invalid-schema (:error (f/guard kg (e :author-named :author {:id "a-boris" :name "  "} :ev/actor "a-boris")))))
      (is (nil? (f/guard kg (e :author-named :author {:id "a-boris" :name "Борис"} :ev/actor "a-boris")))))
    (testing "a rewrite of the topic changes «last changed by»"
      (let [kg2 (f/kg (conj evs (e :version-created :entity :concept :id "invoice" :change :patch
                                   :payload {:definition "новое"} :ev/actor "a-anna" :ev/ts 1792713600000)))]
        (is (= ["a-anna" "a-anna"] ((juxt :added-by :changed-by) (get-in kg2 [:concepts "invoice"]))))))
    (testing "every reader says it: open-node, the markdown export, RDF"
      (is (str/includes? (gt/open-node {:kg kg} "invoice") "added by Анна; last changed by a-boris on 2026-10-22"))
      (is (str/includes? (export/markdown kg "Магазин") "- added by Анна; last changed by a-boris on 2026-10-22"))
      (let [ttl (rdf/turtle kg {:title "Магазин" :base "urn:loretile:shop/" :lang "ru"})]
        (is (str/includes? ttl "dcterms:creator \"Анна\""))
        (is (str/includes? ttl "lt:lastChangedBy \"a-boris\""))
        (is (str/includes? ttl "dcterms:modified \"2026-10-22\"^^xsd:date"))))))

;; The graph navigates, the document answers (2026-10-21: the raw handbook
;; page scored ~70/70 against the graph's ~55/70 — the intake loses detail).
(deftest a-node-opens-onto-its-source
  (let [doc (str "# Accounts payable\n\nVendors send invoices to ap@example.com.\n\n"
                 "## Payment runs\n\nPayment runs happen every Thursday. Invoices must be fully approved by Wednesday EOD.\n\n"
                 "## Methods\n\nWe pay by NetSuite electronic payment, ACH or wire; no checks.\n")
        evs [(e :profile-set :profile shop-profile)
             (e :source-added :source {:id "src-ap" :title "ap.md" :ref "src-ap.md"})
             (e :concept-accepted :concept (assoc (typed "pay-run" "Payment run" :process)
                                                  :source-id "src-ap" :evidence "Payment runs happen every Thursday."))
             (e :concept-accepted :concept (typed "manual" "Manual step" :process))]
        kg (f/kg evs)
        texts {"src-ap" doc}]
    (testing "read-source opens the passage the node was written from"
      (let [s (gt/read-source kg texts "pay-run" nil 120)]
        (is (str/starts-with? s "source «ap.md» (chars "))
        (is (str/includes? s "around the node's quote"))
        (is (str/includes? s "every Thursday"))))
    (testing "or around words the agent asks for (stems, ё as е)"
      (let [s (gt/read-source kg texts "pay-run" "methods ACH" 600)]
        (is (str/includes? s "around «methods ACH»"))
        (is (str/includes? s "no checks"))))
    (testing "a quote whose words are gone: the closest paragraph, and the agent is told the source changed"
      (let [changed (str/replace doc "Payment runs happen every Thursday." "Payment runs now happen on Tuesdays.")
            s (gt/read-source kg {"src-ap" (str (apply str (repeat 40 "Filler paragraph about something else.\n\n")) changed)} "pay-run" nil 200)]
        (is (str/includes? s "its exact words are no longer in the source: it CHANGED"))
        (is (str/includes? s "Tuesdays"))))
    (testing "a node with no source, a source with no text, an unknown node"
      (is (str/includes? (gt/read-source kg texts "manual" nil nil) "has no source"))
      (is (str/includes? (gt/read-source kg {} "pay-run" nil nil) "its text is not available"))
      (is (str/includes? (gt/read-source kg {} "pay-run" nil nil) "«Payment runs happen every Thursday.»"))
      (is (= "no node with id nope" (gt/read-source kg texts "nope" nil nil))))
    (testing "search-sources finds passages and names the nodes written from them"
      (let [s (gt/search-sources kg texts "Thursday approved")]
        (is (str/starts-with? s "«ap.md» @ "))
        (is (str/includes? s "Wednesday EOD"))
        (is (str/includes? s "nodes written from here: pay-run")))
      (is (str/includes? (gt/search-sources kg texts "wire") "NetSuite electronic payment"))
      (is (not (str/includes? (gt/search-sources kg texts "wire") "nodes written from here")) "no node came from that paragraph")
      (is (= "no source passage matches «zzzz»" (gt/search-sources kg texts "zzzz")))
      (is (= "no source texts are loaded on this server" (gt/search-sources kg {} "wire"))))))

;; The loretile codebase as a graph (kg/loretile/build.py): every event
;; passes schema AND guard in order, under its own `software` ontology.
#?(:cljs
   (deftest loretile-graph-is-sound
     (let [file "kg/loretile/loretile.edn"
           evs (reader/read-string (str "[" (.readFileSync (js/require "fs") file "utf8") "]"))
           kg (reduce (fn [kg e]
                        (is (schema/valid? e) (str file " schema: " (:ev/id e) " " (schema/explain e)))
                        (is (nil? (f/guard kg e)) (str file " guard: " (:ev/id e) " " (f/guard kg e)))
                        (f/apply-event kg e))
                      (f/kg []) evs)]
       (is (= "software" (:id (f/profile kg))))
       (is (> (count (:concepts kg)) 150))
       (testing "every module and gate is grounded in its file"
         (is (every? (fn [[id c]] (or (not (#{:module :gate} (:kind c)))
                                      (some #(and (= id (:from %)) (= :grounded-in (:type %))) (:edges kg))))
                     (:concepts kg)))))))

;; The four events nothing wrote until 2026-10-14 (the loretile graph's
;; check-cqs found them): their guards, and a split that keeps history.
(deftest the-four-events-have-guards
  (let [kg (f/kg sql-events)
        split (fn [& kvs] (apply e :concept-split :id "agg"
                                 :into [(concept "agg-basic" "COUNT/SUM") (concept "agg-more" "MIN/MAX")]
                                 :item-remap {} :object-remap {} kvs))]
    (testing "each needs its subject"
      (is (= :missing (:error (f/guard kg (e :concept-deprecated :id "nope")))))
      (is (= :missing (:error (f/guard kg (e :object-promoted :id "o-gb-1")))) "an active object is not promotable")
      (is (nil? (f/guard (f/kg (conj sql-events (e :object-demoted :id "o-gb-1"))) (e :object-promoted :id "o-gb-1"))))
      (is (= :missing (:error (f/guard kg (e :question-answered :id "q-x" :answer "да")))))
      (let [kg2 (f/kg (conj sql-events (e :question-raised :id "q1" :concept-id "where" :question "?")))]
        (is (= :missing (:error (f/guard kg2 (e :question-answered :id "q1" :answer "  ")))) "a blank answer is no answer")
        (is (nil? (f/guard kg2 (e :question-answered :id "q1" :answer "да"))))))
    (testing "a split may not orphan, collide or shrink to one; a course target cannot be split"
      (is (= :in-course (:error (f/guard kg (split)))))
      (let [kg3 (f/kg (conj (vec (remove #(= :course-published (:ev/type %)) sql-events))))]
        (is (nil? (f/guard kg3 (split))) "agg has no items or objects: nothing to assign")
        (is (= {:error :bad-split :detail :fewer-than-two}
               (f/guard kg3 (split :into [(concept "agg-basic" "COUNT/SUM")]))))
        (is (= :id-taken (:detail (f/guard kg3 (split :into [(concept "where" "x") (concept "agg-more" "y")])))))
        (is (= :item-unassigned (:detail (f/guard kg3 (e :concept-split :id "group-by"
                                                         :into [(concept "g1" "A") (concept "g2" "B")]
                                                         :item-remap {"i-gb-1" "g1" "i-gb-2" "g2"}
                                                         :object-remap {"o-gb-1" "g1"})))))))
    (testing "a deprecated target is not ready to publish"
      (let [kg4 (f/kg (conj sql-events (e :concept-deprecated :id "group-by")))]
        (is (= :deprecated (f/concept-status kg4 "group-by")))
        (is (not (:ready? (first (filter #(= "group-by" (:concept %)) (f/course-readiness kg4 {:targets [["group-by" :apprentice]]}))))))))
    (testing "an attempt counts for its item's CURRENT topic: a split keeps the record"
      (let [kg5 (f/kg (conj (vec (remove #(= :course-published (:ev/type %)) sql-events))
                            (e :concept-split :id "group-by"
                               :into [(concept "group-basics" "GROUP BY: основы") (concept "having" "HAVING")]
                               :item-remap {"i-gb-1" "group-basics" "i-gb-2" "group-basics" "i-gb-3" "having"}
                               :object-remap {"o-gb-1" "group-basics"})))]
        (is (= "having" (f/attempt-concept kg5 {:item "i-gb-3" :concept "group-by"})))
        (is (= "group-by" (f/attempt-concept kg {:item "i-gb-3" :concept "group-by"})) "before the split")
        (is (= "x" (f/attempt-concept kg5 {:item "gone" :concept "x"})) "an unknown item keeps its logged topic")))))

(deftest the-graph-as-it-stood
  ;; two clocks over one log (2026-10-31): :ev/ts = when the change was MADE
  ;; (a merge file keeps its dates), :ev/recorded = when this log took it in
  (let [log [(e :concept-accepted :concept (concept "a" "A") :ev/ts 10)
             (e :concept-accepted :concept (concept "b" "B") :ev/ts 20)
             ;; made on day 15 elsewhere, merged here on day 30
             (e :concept-accepted :concept (concept "c" "C") :ev/ts 15 :ev/recorded 30)
             (e :concept-discarded :id "a" :ev/ts 25)]]
    (testing "nil = the whole log, HEAD"
      (is (= #{"b" "c"} (set (keys (:concepts (f/kg (f/events-as-of log nil))))))))
    (testing "as of day 20: c already existed by its own clock, a not yet removed"
      (is (= #{"a" "b" "c"} (set (keys (:concepts (f/kg (f/events-as-of log {:valid 20}))))))))
    (testing "as known by day 20: c had not arrived yet"
      (is (= #{"a" "b"} (set (keys (:concepts (f/kg (f/events-as-of log {:recorded 20}))))))))
    (testing "both bounds compose"
      (is (= #{"b"} (set (keys (:concepts (f/kg (f/events-as-of log {:valid 26 :recorded 26}))))))))
    (testing "a retroactive removal (dated before the topic it removes) is nonsense in, nonsense out — but invisible before it was KNOWN"
      (let [log [(e :concept-accepted :concept (concept "a" "A") :ev/ts 10)
                 (e :concept-discarded :id "a" :ev/ts 5 :ev/recorded 50)]]
        (is (= #{} (set (keys (:concepts (f/kg (f/events-as-of log {:valid 8})))))))
        (is (= #{"a"} (set (keys (:concepts (f/kg (f/events-as-of log {:recorded 20})))))))))))

(deftest signals-from-a-real-regulation
  ;; real-model run 2026-11-20 (wiki/Real-Model-Run-2026-11-20.md)
  (testing "a title counts as mentioned with other endings"
    (let [scale {:id "vesovaya" :title "Весовая"}
          weigh {:id "vzv" :title "Взвешивание" :definition "Взвешивание партии на весовой."}]
      (is (= "vzv" (:from (f/pair-signals weigh scale))))
      (is (nil? (f/pair-signals {:id "x" :title "Весовая"} {:id "y" :title "Сбор" :definition "Сбор урожая."})))))
  (testing "numbered list items are steps of one list"
    (let [text "# Регламент\n\n## Порядок\n1. Взвешивание на весовой.\n2. Отбор пробы и анализ.\n3. Решение технолога.\n"
          steps (f/numbered-steps text)
          at (fn [ev] (f/step-of steps text {:evidence ev}))]
      (is (= [1 2 3] (keep :n steps)))
      (is (= "Порядок" (:parent (at "Отбор пробы и анализ"))))
      (is (f/step-next? (at "Отбор пробы и анализ") (at "Взвешивание на весовой")))))
  (testing "two nodes quoted from one sentence: a weak signal, ranked after the strong ones"
    (let [text "Технолог переводит вино в ёмкость для выдержки: дубовую бочку на 225 литров. Погребщик доливает бочки."
          node (fn [id title ev] {:id id :title title :definition "" :evidence ev :sentence (f/sentence-of text {:evidence ev})})
          move (node "move" "Перевод в ёмкость" "переводит вино в ёмкость для выдержки")
          barrel (node "barrel" "Дубовая бочка 225 л" "дубовую бочку на 225 литров")
          topup (node "topup" "Долив" "Погребщик доливает бочки")
          strong (assoc topup :id "t2" :title "Долив бочек" :definition "Перевод в ёмкость закончен")]
      (is (= (:sentence move) (:sentence barrel)))
      (is (not= (:sentence move) (:sentence topup)))
      (is (:weak? (f/pair-signals move barrel)))
      (is (nil? (f/pair-signals barrel topup)))
      (let [cs (f/edge-candidates f/empty-kg [move barrel strong] 10)]
        (is (= #{"move" "t2"} (set [(:a (first cs)) (:b (first cs))])) "the strong pair first")
        (is (= #{"move" "barrel"} (set [(:a (second cs)) (:b (second cs))])) "then the weak one")))))
