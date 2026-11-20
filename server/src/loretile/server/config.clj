(ns loretile.server.config
  "Every setting of the server, from one place: built-in defaults, then the
   EDN file named by LORETILE_CONFIG (file:// or s3://), then the
   environment. Each key path doubles as its environment name, Spring
   style: [:llm :token-ttl-ms] is LLM_TOKEN_TTL_MS. The file is re-read
   every [:config :reload-ms]; a file that cannot be read or does not fit
   the spec keeps the last good config."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [loretile.server.log :refer [log]]
            [loretile.server.s3 :as s3])
  (:import (java.io File)
           (java.net URI)))

(def spec
  [[[:server :port] :int 8080]
   [[:server :public-dir] :string "public"]
   [[:config :reload-ms] :int 10000]
   [[:app :demos] :boolean true]
   [[:llm :url] :string nil]
   [[:llm :shape] :keyword :openai-compat]
   [[:llm :model] :string nil]
   [[:llm :max-output] :int nil]
   [[:llm :embed-url] :string nil]
   [[:llm :embed-model] :string nil]
   [[:llm :timeout-ms] :int 300000]
   [[:llm :token] :string nil]
   [[:llm :token-provider] :symbol nil]
   [[:llm :token-provider-file] :string nil]
   [[:llm :token-ttl-ms] :int 3600000]
   [[:llm :auth-header] :string "Authorization"]
   [[:llm :auth-prefix] :string "Bearer "]
   [[:merges :url] :string nil]
   [[:aws :region] :string nil]
   [[:aws :endpoint] :string nil]])

(defn env-name [path]
  (-> (str/join "_" (map name path)) (str/replace "-" "_") str/upper-case))

(let [names (map (comp env-name first) spec)]
  (when-not (apply distinct? names)
    (throw (ex-info "Two config keys share an environment name" {:names names}))))

(defn- parse-string [type s]
  (case type
    :string s
    :int (parse-long (str/trim s))
    :boolean (case (str/lower-case (str/trim s)) ("true" "1" "yes") true ("false" "0" "no") false nil)
    :keyword (keyword (str/replace (str/trim s) #"^:" ""))
    :symbol (symbol (str/trim s))))

(def ^:private fits?
  {:string string? :int int? :boolean boolean? :keyword keyword? :symbol symbol?})

(defn- check-file [m]
  (doseq [[path type] spec
          :let [v (get-in m path)]
          :when (and (some? v) (not ((fits? type) v)))]
    (throw (ex-info (str (pr-str path) " must be " (name type) ", got " (pr-str v)) {:path path})))
  m)

(defn- env-layer [env]
  (reduce (fn [m [path type]]
            (if-let [s (get env (env-name path))]
              (let [v (parse-string type s)]
                (when (nil? v)
                  (throw (ex-info (str (env-name path) " is not " (name type) ": " s) {:path path})))
                (assoc-in m path v))
              m))
          {} spec))

(def defaults
  (reduce (fn [m [path _ default]] (assoc-in m path default)) {} spec))

(defn- deep-merge [& ms]
  (apply merge-with (fn [a b] (if (and (map? a) (map? b)) (deep-merge a b) b)) ms))

(defn build [file-map env]
  (deep-merge defaults (check-file (or file-map {})) (env-layer env)))

;; --- reading the file ---------------------------------------------------------

(defn location [] (some-> (System/getenv "LORETILE_CONFIG") str/trim not-empty))

(defn- local-file ^File [^String loc]
  (if (str/starts-with? loc "file:") (File. (URI. loc)) (File. ^String loc)))

(defn- read-source
  "{:text … :version …} when the file changed since `version`, :unchanged
   when it did not."
  [loc version aws]
  (if (str/starts-with? loc "s3://")
    (let [{:keys [bucket key]} (s3/parse-url loc)
          r (s3/get-object aws bucket key version)]
      (if (= r :not-modified) :unchanged {:text (String. ^bytes (:bytes r) "UTF-8") :version (:etag r)}))
    (let [f (local-file loc) stamp (.lastModified f)]
      (cond
        (not (.isFile f)) (throw (ex-info (str "No config file at " loc) {}))
        (= stamp version) :unchanged
        :else {:text (slurp f) :version stamp}))))

(defonce current (atom nil))
(defonce ^:private version (atom nil))

(defn get-in-config [path] (get-in @current path))

(defn load!
  "Reads the file when it changed; returns true when a new config is in
   force. Throws when the file is unreadable or invalid."
  []
  (let [loc (location)
        env (System/getenv)
        aws (:aws (build nil env))
        r (when loc (read-source loc @version aws))]
    (when-not (= r :unchanged)
      (reset! version (:version r))
      (reset! current (build (some-> r :text edn/read-string) env))
      true)))

(defn start-reloading! []
  (Thread/startVirtualThread
   (fn []
     (loop []
       (Thread/sleep (long (get-in-config [:config :reload-ms])))
       (try (when (load!) (log "config reloaded from" (location)))
            (catch Exception e (log "config reload failed, keeping the last good one:" (ex-message e))))
       (recur)))))
