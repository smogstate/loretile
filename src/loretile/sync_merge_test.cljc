(ns loretile.sync-merge-test
  "Pending edits laid on what others stored (wiki/Graph-Sync.md): what
   composes, and what becomes a conflict for a person."
  (:require [clojure.test :refer [deftest is testing]]
            [loretile.fold :as f]
            [loretile.sync-merge :as m]))

(defn- ch [id & ops] {:ev/id id :ev/type :change :ev/ns "g" :ev/actor "a" :ev/ts 1 :ops (vec ops)})

(def base [(ch "b1" [:node-set "x" {:title "Икс"}] [:node-set "y" {:title "Игрек"}])])

(defn- run [remote mine]
  (m/rebase (f/kg (concat base remote)) remote mine))

(deftest pending-edits-on-top-of-others
  (testing "different properties compose"
    (let [{:keys [keep conflicts]} (run [(ch "t1" [:node-set "x" {:owner "Анна"}])]
                                        [(ch "m1" [:node-set "x" {:title "Икс 2"}])])]
      (is (= ["m1"] (map :ev/id keep)))
      (is (empty? conflicts))))
  (testing "the same property, two values: a person decides"
    (let [{:keys [keep conflicts]} (run [(ch "t1" [:node-set "x" {:title "Икс Анны"}])]
                                        [(ch "m1" [:node-set "x" {:title "Икс мой"}])])]
      (is (empty? keep))
      (is (= :same-property (:kind (first conflicts))))
      (is (= {[[:node "x"] :title] {:theirs "Икс Анны" :mine "Икс мой"}} (:clash (first conflicts))))))
  (testing "the same value from both is no conflict"
    (is (empty? (:conflicts (run [(ch "t1" [:node-set "x" {:title "Икс 2"}])]
                                 [(ch "m1" [:node-set "x" {:title "Икс 2"}])])))))
  (testing "my edit of a node someone removed: a person decides"
    (let [{:keys [conflicts]} (run [(ch "t1" [:node-remove "x"])]
                                   [(ch "m1" [:node-set "x" {:owner "я"}])])]
      (is (= :removed (:kind (first conflicts))))
      (is (= [[:node "x"]] (:gone (first conflicts))))))
  (testing "my link to a node someone removed"
    (let [{:keys [conflicts]} (run [(ch "t1" [:node-remove "y"])]
                                   [(ch "m1" [:edge-set "x" :rel "y" {}])])]
      (is (= :removed (:kind (first conflicts))))))
  (testing "a later pending edit still lands when an earlier one conflicts"
    (let [{:keys [keep conflicts]} (run [(ch "t1" [:node-set "x" {:title "Икс Анны"}])]
                                        [(ch "m1" [:node-set "x" {:title "Икс мой"}])
                                         (ch "m2" [:node-set "y" {:owner "я"}])])]
      (is (= ["m2"] (map :ev/id keep)))
      (is (= 1 (count conflicts)))))
  (testing "after a push: theirs stored just before mine, overridden"
    (let [o (m/overridden [(ch "t1" [:node-set "x" {:title "Икс Анны"}])]
                          [(ch "m1" [:node-set "x" {:title "Икс мой"}])])]
      (is (= :overridden (:kind (first o))))
      (is (= [[:node-set "x" {:title "Икс Анны"}]] (m/ops-for {[[:node "x"] :title] "Икс Анны"}))))))

(deftest what-others-did
  (testing "new nodes, new links, properties of known ones, removals — counted"
    (is (= [{:kind :nodes :n 2} {:kind :links :n 1} {:kind :props :n 3} {:kind :removed :n 1}]
           (m/summary (f/kg base)
                      [(ch "t1" [:node-set "z" {:title "Зет"}] [:node-set "w" {:title "Дабл"}])
                       (ch "t2" [:edge-set "x" :rel "y" {}])
                       (ch "t3" [:node-set "x" {:owner "Анна" :stage "готово"}] [:edge-set "x" :rel "y" {:weight 2}])
                       (ch "t4" [:node-remove "w"])]))))
  (testing "nothing others did, nothing to show"
    (is (= [] (m/summary (f/kg base) [])))))
