(ns loretile.server.token
  "The gateway token: [:llm :token] as is, or the result of a provider —
   [:llm :token-provider] (a symbol on the classpath) or
   [:llm :token-provider-file] (a .clj file whose last form is the
   function). A provider is (fn [] …) returning a string, or
   {:token … :expires-at inst-or-epoch-ms} / {:token … :expires-in-ms n};
   without an expiry [:llm :token-ttl-ms] applies.

   Cached in memory and refreshed at 80% of its life. Single flight: the
   first caller to find the token stale installs a delay, every concurrent
   caller derefs that same delay, so the provider runs once. A failed run
   is dropped, so the next caller tries again."
  (:require [clojure.string :as str]
            [loretile.server.config :as config])
  (:import (java.io File)
           (java.net URI)))

(defonce ^:private cached (atom nil))
(defonce ^:private generation (atom 0))
(defonce ^:private loaded-file (atom nil))

(defn- now [] (System/currentTimeMillis))

(defn- provider-from-file [loc]
  (let [f (if (str/starts-with? loc "file:") (File. (URI. loc)) (File. ^String loc))
        stamp (.lastModified f)
        {:keys [path modified provider]} @loaded-file]
    (if (and (= path loc) (= modified stamp))
      provider
      (let [p (load-file (.getPath f))]
        (when-not (fn? p)
          (throw (ex-info (str loc " must evaluate to a function") {})))
        (reset! loaded-file {:path loc :modified stamp :provider p})
        p))))

(defn- source [llm] (select-keys llm [:token :token-provider :token-provider-file]))

(defn- expiry [result ttl]
  (let [t0 (now)
        at (cond
             (inst? (:expires-at result)) (inst-ms (:expires-at result))
             (number? (:expires-at result)) (long (:expires-at result))
             (:expires-in-ms result) (+ t0 (long (:expires-in-ms result)))
             :else (+ t0 ttl))]
    (+ t0 (long (* 0.8 (- at t0))))))

(defn- resolve-token [llm]
  (let [{:keys [token token-provider token-provider-file token-ttl-ms]} llm
        provider (cond
                   token-provider (requiring-resolve token-provider)
                   token-provider-file (provider-from-file token-provider-file))]
    (if-not provider
      {:token token :refresh-at Long/MAX_VALUE}
      (let [r (provider)
            r (if (string? r) {:token r} r)]
        (when (str/blank? (:token r))
          (throw (ex-info "The token provider returned no token" {})))
        {:token (:token r) :refresh-at (expiry r token-ttl-ms)}))))

(defn- usable? [d llm]
  (and d (or (not (realized? d))
             (let [t (try @d (catch Exception _ nil))]
               (and t (= (:source t) (source llm)) (< (now) (:refresh-at t)))))))

(defn current
  "{:token … :gen n}, or nil when no token is configured."
  []
  (let [llm (config/get-in-config [:llm])]
    (when (some llm [:token :token-provider :token-provider-file])
      (loop []
        (let [d @cached]
          (if (usable? d llm)
            (try @d
                 (catch Exception e
                   (compare-and-set! cached d nil)
                   (throw e)))
            (do (compare-and-set! cached d
                                  (delay (assoc (resolve-token llm)
                                                :gen (swap! generation inc)
                                                :source (source llm))))
                (recur))))))))

(defn invalidate!
  "Drops the cached token when it is still the one the gateway refused."
  [gen]
  (let [d @cached]
    (when (and d (realized? d) (= gen (:gen (try @d (catch Exception _ nil)))))
      (compare-and-set! cached d nil))))
