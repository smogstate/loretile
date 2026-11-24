(ns loretile.sync-merge-test
  "What others did, counted for the collaborators' pills."
  (:require [clojure.test :refer [deftest is testing]]
            [loretile.fold :as f]
            [loretile.sync-merge :as m]))

(defn- ch [id & ops] {:ev/id id :ev/type :change :ev/ns "g" :ev/actor "a" :ev/ts 1 :ops (vec ops)})

(def base [(ch "b1" [:node-set "x" {:title "Икс"}] [:node-set "y" {:title "Игрек"}])])

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
