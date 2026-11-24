(ns loretile.server.projection-test
  "The gate of the server's projection (wiki/Graph-Store.md): every log in
   kg/, replayed into SQLite, reads back (graph-store/rows->kg of the current
   rows) as the fold's own graph; the guard through the tables refuses what
   the guard over the folded graph refuses. Run: `cd server && clojure -M:test`."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing run-tests]]
            [loretile.fold :as fold]
            [loretile.graph-store :as gs]
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
        (is (seq (proj/search db g "новое определение" 5)))))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'loretile.server.projection-test)]
    (shutdown-agents)
    (System/exit (if (zero? (+ fail error)) 0 1))))
