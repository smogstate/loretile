(ns loretile.server.log
  (:import (java.time Instant)))

(defn log [& parts]
  (binding [*out* *err*]
    (println (str (Instant/now)) (apply str (interpose " " parts)))))
