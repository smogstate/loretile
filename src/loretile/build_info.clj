(ns loretile.build-info
  "Facts about the build, fixed when it is compiled."
  (:require [clojure.java.io :as io]))

(defmacro fold-hash
  "A hash of the sources that decide what the fold writes (wiki/Graph-Store.md
   § Tables, rebuild): the projection is rebuilt only when it changes — not on
   every deploy, and never because someone forgot to bump a version."
  []
  (str (hash (apply str (for [f ["loretile/fold.cljc" "loretile/schema.cljc" "loretile/profile.cljc" "loretile/graph_store.cljc"]]
                          (some-> (io/resource f) slurp))))))
