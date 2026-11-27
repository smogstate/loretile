(ns loretile.server.projection-test
  "The gate of the server's projection (wiki/Graph-Store.md): every log in
   kg/, replayed into SQLite, reads back (graph-store/rows->kg of the current
   rows) as the fold's own graph; the guard through the tables refuses what
   the guard over the folded graph refuses. Run: `cd server && clojure -M:test`."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing run-tests]]
            [loretile.fold :as fold]
            [loretile.graph-store :as gs]
            [loretile.graph-rows :as rows]
            [loretile.server.genesis :as genesis]
            [loretile.server.projection :as proj])
  (:import (java.io File PushbackReader)))

(def logs
  (sort (for [d ["../kg/demo" "../kg/loretile" "../kg/ontologies"]
              ^File f (.listFiles (io/file d))
              :when (.endsWith (.getName f) ".edn")]
          (.getPath f))))

(defn read-log [path]
  (with-open [r (PushbackReader. (io/reader path))]
    (vec (take-while some? (repeatedly #(edn/read {:eof nil} r))))))

(defn- comparable [kg]
  (into {} (remove (fn [[k v]] (or (nil? v) (#{:loretile.fold/eidx :loretile.fold/nbr} k)))) kg))

(defn- temp-db []
  (let [f (File/createTempFile "projection-test" ".sqlite3")]
    (.deleteOnExit f)
    (proj/open (.getPath f))))

(deftest every-log-reads-back-as-the-fold
  (let [db (temp-db)]
    (doseq [path logs
            :let [evs (read-log path) g path
                  t (System/nanoTime)]]
      (proj/rebuild! db g evs)
      (let [ms (quot (- (System/nanoTime) t) 1000000)]
        (println (format "  %-32s %5d events  rebuild %5d ms" path (count evs) ms)))
      (is (= (comparable (fold/kg evs)) (comparable (gs/rows->kg (proj/dump db g)))) path)
      (is (= (count evs) (:seq (proj/head db g))) path))))

(deftest the-tables-judge-like-the-fold
  (let [db (temp-db)
        evs (read-log "../kg/demo/sql.ru.edn")
        g "sql"
        _ (proj/rebuild! db g evs)
        kg (fold/kg evs)
        {:keys [from to]} (first (filter #(= :requires (:type %)) (:edges kg)))
        ev (fn [id ops] {:ev/id id :ev/ns g :ev/actor "t" :ev/ts 1 :ev/type :change :ops ops})
        cycle (ev "c1" [[:edge-set to :requires from {}]])
        fine (ev "c2" [[:node-set from {:definition "Новое определение"}]])]
    (testing "a cycle is refused by both, nothing is written"
      (is (some? (fold/guard kg cycle)))
      (is (= (:error (fold/guard kg cycle))
             (:error (:error (proj/append! db g (count evs) [fine cycle] #(throw (ex-info "persisted" {})))))))
      (is (= (count evs) (:seq (proj/head db g)))))
    (testing "an accepted batch is projected and persisted"
      (let [persisted (atom false)]
        (is (nil? (proj/append! db g (count evs) [fine] #(reset! persisted true))))
        (is @persisted)
        (is (= (inc (count evs)) (:seq (proj/head db g))))
        (is (= (comparable (fold/kg (conj evs fine))) (comparable (gs/rows->kg (proj/dump db g)))))
        (is (= 2 (count (:nodes (proj/changes-since db g (count evs))))) "the new row and the one it closed")
        (is (seq (proj/search db g {:text "новое определение" :limit 5})) "hybrid without a vector: words")
        (is (seq (proj/search db g {:text "новое определение" :mode "verbatim" :limit 5})) "verbatim: the phrase")
        (is (empty? (proj/search db g {:text "определение новое" :mode "verbatim" :limit 5})) "verbatim: words out of order are not the phrase")
        (is (empty? (proj/search db g {:text "новое определение" :mode "semantic" :limit 5})) "semantic without a vector: nothing")))
    ;; the cycle check's relation cache (in memory) never outlives a rollback
    (testing "a refused batch leaves no edge behind in the cycle check's cache"
      (let [n (inc (count evs))
            nodes [[:node-set "n1" {:title "Эн один"}] [:node-set "n2" {:title "Эн два"}]]
            e1 (ev "r1" (conj nodes [:edge-set "n1" :requires "n2" {}]))
            e2 (ev "r2" [[:edge-set "n2" :requires "n1" {}]])
            e3 (ev "r3" (conj nodes [:edge-set "n2" :requires "n1" {}]))
            e4 (ev "r4" [[:edge-set "n1" :requires "n2" {}]])]
        (is (some? (:error (proj/append! db g n [e1 e2] (fn [])))) "n1→n2 then n2→n1: refused whole")
        (is (nil? (proj/append! db g n [e3] (fn []))) "n2→n1 alone is fine: n1→n2 was never stored")
        (is (some? (:error (proj/append! db g (inc n) [e4] (fn [])))) "and now n1→n2 closes a cycle")))))

;; the server's view of a FILTERED log (graphs/view) answers a folded kg in the
;; dump's shape: read back as rows, it must be the fold's own graph
(defn- read-dump [s]
  (let [d (json/read-str s)]
    {:nodes (for [[id c data] (get d "nodes")] [id c (json/write-str data)])
     :edges (for [[st data] (get d "edges")] [st (json/write-str data)])
     :discarded (get d "discarded")
     :heads (edn/read-string (get d "heads"))}))

(deftest a-folded-graph-dumps-and-reads-back
  (doseq [path logs :let [evs (read-log path) kg (fold/kg evs)]]
    (is (= (comparable kg) (comparable (gs/rows->kg (read-dump (proj/kg->dump-json kg {}))))) path))
  (testing "a learner pinned at a publication: the graph of fold/events-at, read back"
    (let [evs (read-log "../kg/demo/sql.ru.edn")
          course (first (keys (:courses (fold/kg evs))))
          cut (fold/publication-cut evs course)
          kg (fold/kg (fold/events-at evs cut))]
      (is (some? cut))
      (is (= (comparable kg) (comparable (gs/rows->kg (read-dump (proj/kg->dump-json kg {"cut" cut})))))))))

;; backend mode, step 3: a browser holding the graph at event n applies the
;; projection's rows since n (proj/delta-since → graph-rows/rows->delta →
;; fold/apply-delta) instead of folding — and must end at the fold of the log
(deftest rows-since-a-head-bring-a-graph-up-to-the-fold
  (let [db (temp-db)]
    (doseq [path logs
            :let [evs (read-log path) g (str "rows:" path) whole (fold/kg evs)]]
      (proj/rebuild! db g evs)
      (doseq [n (distinct [0 (quot (count evs) 3) (quot (* 2 (count evs)) 3) (count evs)])
              :let [at (fold/kg (subvec evs 0 n))
                    r (proj/delta-since db g n)]]
        (is (= (comparable whole) (comparable (fold/apply-delta at (rows/rows->delta at r))))
            (str path " from event " n))
        (is (= (count evs) (:head r)) path)))))

;; a graph that exists only as a projection gets a GENESIS log (genesis.clj):
;; read back from the file, its events replayed through the guard must fold to
;; the database's graph — with the authors and times the fold stamps, or without
(deftest a-projection-gets-a-genesis-log-that-folds-to-it
  (let [f (File/createTempFile "genesis-test" ".sqlite3")
        _ (.deleteOnExit f)
        db (proj/open (.getPath f))]
    (doseq [path logs :let [evs (read-log path) g (str "g" (Math/abs (hash path)))]]
      (proj/rebuild! db g evs)
      (let [kg (genesis/read-graph (.getPath f) g)]
        (is (= (comparable (fold/kg evs)) (comparable kg)) (str path ": the file reads back as the fold"))
        (doseq [authorship? [true false]
                :let [{:keys [events warnings]} (genesis/kg->events kg {:graph g :authorship? authorship?})
                      proof (genesis/verify kg events {:authorship? authorship?})]]
          (when-not (:ok? proof) (println "  " path "authorship" authorship? (pr-str (dissoc proof :ok?)) (pr-str warnings)))
          (is (:ok? proof) (str path (when-not authorship? " (no authorship)"))))))))

;; …and what the kg/ logs hardly have: two authors (one adds, the other edits),
;; versions, staleness from a source's material update, discarded proposals and
;; edges, proposals kept, material and items
(deftest a-genesis-log-keeps-versions-staleness-discards-and-authors
  (let [n (atom 1000)
        ev (fn [actor ops & [edit]] (cond-> {:ev/id (str "s" (swap! n inc)) :ev/ns "scn" :ev/actor actor :ev/ts (swap! n inc)
                                            :ev/type :change :ops ops} edit (assoc :edit edit)))
        evs [(ev "a1" [[:graph-set {:title "Сценарий"}]])
             (ev "a1" [[:graph-set {:authors {"a1" "Анна"}}]])
             (ev "a2" [[:graph-set {:authors {"a2" "Борис"}}]])
             (ev "a1" [[:node-set "s1" {:title "Регламент" :is :source}]])
             (ev "a1" [[:node-set "c1" {:title "Окно возврата" :definition "14 дней." :source-id "s1"}]])
             (ev "a2" [[:node-set "c2" {:title "Автовозврат" :definition "До 5000."}]])
             (ev "a2" [[:edge-set "c2" :requires "c1" {}]])
             (ev "a1" [[:node-set "o1" {:concept-id "c1" :kind :explanation :level :novice :locale "ru" :origin :authored :text "Объяснение." :grounded-in "s1" :is :object}]])
             (ev "a2" [[:node-set "o2" {:concept-id "c2" :kind :explanation :level :novice :locale "ru" :origin :authored :text "Другое." :is :object}]])
             (ev "a1" [[:node-set "i1" {:primary-concept-id "c1" :level :novice :kind :single-choice :locale "ru" :origin :authored
                                         :spec {:stem "Сколько дней?" :options ["14" "30"] :key 0} :status :active :is :item}]])
             (ev "a1" [[:node-set "c1" {:definition "Четырнадцать дней."}]] :patch)
             (ev "a1" [[:node-set "o1" {:text "Объяснение, новое."}]] :material)
             (ev "a1" [[:node-set "s1" {:updated {:change :material}}]])
             (ev "a1" [[:node-set "p1" {:title "Лишнее" :status :proposed}]])
             (ev "a1" [[:node-remove "p1"]])
             (ev "a2" [[:node-set "p2" {:title "Предложено" :status :proposed}]])
             (ev "a2" [[:edge-set "c1" :see-also "c2" {:status :proposed}]])
             (ev "a2" [[:edge-set "c2" :is-confused-with "c1" {:status :proposed}]])
             (ev "a2" [[:edge-remove "c2" :is-confused-with "c1"]])
             (ev "a1" [[:node-set "c2" {:definition "До 5000 рублей."}]])]
        {base :kg bad :bad err :error} (fold/replay fold/empty-kg evs)]
    (is (nil? bad) (str "the scenario itself is a log the guard accepts: " (pr-str err)))
    (when-not bad
      (let [f (File/createTempFile "genesis-scn" ".sqlite3") _ (.deleteOnExit f)
            db (proj/open (.getPath f))
            _ (proj/rebuild! db "scn" evs)
            kg (genesis/read-graph (.getPath f) "scn")]
        (testing "the scenario has what it is for"
          (is (= 2 (get-in kg [:concepts "c1" :version])))
          (is (true? (get-in kg [:concepts "c1" :stale?])))
          (is (true? (get-in kg [:objects "o1" :stale?])))
          (is (= ["a2" "a1"] [(get-in kg [:concepts "c2" :added-by]) (get-in kg [:concepts "c2" :changed-by])]))
          (is (contains? (:discarded kg) "p1"))
          (is (some vector? (:discarded kg))))
        (doseq [authorship? [true false]
                :let [{:keys [events warnings]} (genesis/kg->events kg {:graph "scn" :authorship? authorship?})
                      proof (genesis/verify kg events {:authorship? authorship?})]]
          (when-not (:ok? proof) (println "   scenario authorship" authorship? (pr-str (dissoc proof :ok?)) (pr-str warnings)))
          (is (:ok? proof) (str "the scenario" (when-not authorship? " (no authorship)"))))))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'loretile.server.projection-test)]
    (shutdown-agents)
    (System/exit (if (zero? (+ fail error)) 0 1))))
