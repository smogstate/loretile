;; A token provider file: its last form is the function. Called once per
;; refresh (at 80% of the token's life, or after the gateway answers 401).
(require '[clojure.edn :as edn])

(fn []
  (let [r (slurp "/var/run/secrets/llm-token.edn")]
    (edn/read-string r)))
