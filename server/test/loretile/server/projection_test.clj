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

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'loretile.server.projection-test)]
    (shutdown-agents)
    (System/exit (if (zero? (+ fail error)) 0 1))))
