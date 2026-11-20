(ns loretile.llm
  "BYOK, kintale-style, M0-lite: two provider shapes cover the fleet —
   :anthropic native, :openai-compat for everything OpenAI-shaped
   (Yandex, DeepSeek, Moonshot, OpenRouter… via base-url+model).
   Key lives in localStorage, calls go browser→provider, we are never
   in the path. The full kintale facade/proto port lands when more
   providers than these two shapes are actually used."
  (:require [cljs.reader :as reader]
            [promesa.core :as p]
            [loretile.i18n :refer [t]]))

(def presets
  ;; :key-url — where a newcomer gets a key (resolved 2026-10-03)
  {:anthropic {:label "Anthropic" :shape :anthropic
               :key-url "https://platform.claude.com/settings/keys"
               :url "https://api.anthropic.com/v1/messages"
               :model "claude-sonnet-5"
               ;; :max-output — how long a reply the provider allows, for the one
               ;; call that legitimately answers long (an intake part); every
               ;; other call keeps max-reply-tokens. Absent = max-reply-tokens.
               :max-output 16000}
   ;; embed-url/-model: OpenAI-shaped /embeddings for concept vectors
   ;; (search over the index). Anthropic has none — those stay lexical.
   ;; Yandex values are the documented OpenAI-compat shape, unverified live.
   :yandex    {:label "Yandex (Qwen)" :shape :openai-compat
               :key-url "https://yandex.cloud/ru/docs/iam/operations/authentication/manage-api-keys"
               :url "https://llm.api.cloud.yandex.net/v1/chat/completions"
               :model "gpt://<folder>/qwen3-235b-a22b-fp8/latest"
               :embed-url "https://llm.api.cloud.yandex.net/v1/embeddings"
               :embed-model "emb://<folder>/text-search-doc/latest"}
   :deepseek  {:label "DeepSeek" :shape :openai-compat
               :key-url "https://platform.deepseek.com/api_keys"
               :url "https://api.deepseek.com/chat/completions"
               :model "deepseek-chat"
               :max-output 8192}
   ;; CORS-open for browser calls (preflight verified 2026-09-09); no
   ;; public /embeddings, so concept search stays lexical on this preset
   :moonshot  {:label "Moonshot (Kimi 3)" :shape :openai-compat
               :key-url "https://platform.kimi.ai/console/api-keys"
               :url "https://api.moonshot.ai/v1/chat/completions"
               :model "kimi-k3"}
   :custom    {:label "OpenAI-compatible…" :shape :openai-compat :url "" :model ""
               :embed-url "" :embed-model ""}})

(def ^:private store-key "loretile-llm-config")

(defonce backend
  ;; served by the Loretile server (server/), index.html carries
  ;; <meta name="loretile-backend" content="{:shape … :model …}">: calls go to
  ;; its api/, which holds the gateway and its token. Absent — the static
  ;; build — the key lives in this browser as before.
  (some-> (js/document.querySelector "meta[name=loretile-backend]")
          (.getAttribute "content")
          reader/read-string))

(defn config []
  (if backend
    (cond-> (assoc (select-keys backend [:shape :model :max-output]) :url "api/llm")
      (:embed-model backend) (assoc :embed-url "api/embed" :embed-model (:embed-model backend)))
    (try (some-> (or (js/localStorage.getItem store-key)
                     (js/localStorage.getItem "tessella-llm-config"))   ; pre-rename key
                 reader/read-string)
         (catch :default _ nil))))

(defn save-config! [cfg]
  (js/localStorage.setItem store-key (pr-str cfg)))

(defn ready? [] (boolean (or backend (some-> (config) :key seq))))

(defn- parse-json [s] (js->clj (js/JSON.parse s) :keywordize-keys true))

;; --- the token budget: caps day one, fail closed (Ethics; review 2026-10-02 #7) ---
;; The key is the user's, so is the bill: every reply is capped, a day's
;; tokens are counted on this device, and a call that could cross the budget
;; is refused BEFORE it is sent. Counted from what providers report; where a
;; provider reports nothing, ~3 characters per token (Cyrillic runs dense).
;; A record that cannot be read refuses every call until Settings or the
;; next UTC day repairs it; in-flight calls hold a reservation of their
;; worst case, so parallel calls cannot each be admitted against the same room.

(def max-reply-tokens 4096)
(def default-daily-tokens
  ;; about one evening of heavy authoring: a 100-page document read in parts
  ;; plus links, drafts and tutoring
  400000)

(def ^:private budget-key "loretile-llm-budget")
(def ^:private usage-key "loretile-llm-usage")

(defn- read-ls
  "::absent when the key is missing, ::corrupt when it cannot be read."
  [k]
  (try (if-some [s (js/localStorage.getItem k)] (reader/read-string s) ::absent)
       (catch :default _ ::corrupt)))
(defn- write-ls! [k v] (try (js/localStorage.setItem k (pr-str v)) true (catch :default _ false)))

(defn daily-budget
  "Absent → the default; present but not a positive count → nil, which
   refuses every call until a budget is saved (fail closed)."
  []
  (let [b (read-ls budget-key)] (cond (= b ::absent) default-daily-tokens (pos-int? b) b)))
(defn set-daily-budget! [n] (when (pos-int? n) (write-ls! budget-key n)))

(defn- today [] (.slice (.toISOString (js/Date.)) 0 10))

(defn usage
  "{:day :tokens :calls} for today; a new day starts at zero. A record that
   cannot be read, or whose counts are not counts, is today's budget fully
   spent (:corrupt true). An unreadable one is rewritten as today's, so the
   next UTC day starts clean."
  []
  (let [u (read-ls usage-key) d (today) fresh {:day d :tokens 0 :calls 0}]
    (cond
      (= u ::absent) fresh
      (and (map? u) (string? (:day u)) (not= (:day u) d)) fresh
      (and (map? u) (nat-int? (:tokens u)) (nat-int? (:calls u))) u
      :else (do (when-not (and (map? u) (= (:day u) d))
                  (write-ls! usage-key {:day d :tokens :corrupt :calls 0}))
                (assoc fresh :corrupt true)))))

;; tokens spent that storage refused to record (a full localStorage): they
;; count against the budget, and no call is admitted until they are written
;; — the budget failed OPEN here before (review 2026-10-16 A11)
(defonce ^:private unwritten (atom 0))

(defn- spend! [tokens]
  (let [u (usage) n (+ tokens @unwritten)]
    (when-not (:corrupt u)
      (if (write-ls! usage-key (cond-> (update u :tokens + n) (pos? tokens) (update :calls inc)))
        (reset! unwritten 0)
        (reset! unwritten n)))))

(defn- estimate [& texts] (js/Math.ceil (/ (reduce + (map #(count (str %)) texts)) 3)))

;; worst cases of calls admitted and not yet ended
(defonce ^:private reserved (atom 0))

(defn- admit
  "nil when a call costing up to `worst` tokens fits today's budget beside
   the calls still in flight — and `worst` is then reserved, to be released
   by the caller when the call ends — else the refusal, a rejected promise,
   before anything is sent."
  [worst]
  (when (pos? @unwritten) (spend! 0))
  (let [{:keys [tokens corrupt]} (usage) budget (daily-budget) used (+ tokens @reserved @unwritten)]
    (cond
      (or corrupt (nil? budget))
      (p/rejected (ex-info (t :ui/budget-corrupt) {:budget true}))
      (pos? @unwritten)
      (p/rejected (ex-info (t :ui/budget-unrecorded) {:budget true}))
      (> (+ used worst) budget)
      (p/rejected (ex-info (t :ui/budget-spent {"{used}" used "{budget}" budget}) {:budget true}))
      :else (do (swap! reserved + worst) nil))))

(defn- extract-text [shape resp]
  (case shape
    :anthropic (->> (:content resp) (filter #(= "text" (:type %))) (map :text) (apply str))
    (-> resp :choices first :message :content)))

(defn embeddings? []
  (let [{:keys [embed-url embed-model key]} (config)]
    (boolean (and (seq embed-url) (seq embed-model) (or backend (seq key))))))

(defn embed!
  "texts → promise of vectors (JS arrays), OpenAI-shaped /embeddings.
   ponytail: batches of 50 in parallel; sequential if a provider rate-limits.
   Inside the token budget like every call; a failed batch still counts
   its input estimate."
  [texts]
  (let [batches (partition-all 50 texts)
        ests (map #(apply estimate %) batches)]
  (or (admit (reduce + ests))
  (let [{:keys [embed-url embed-model key]} (config)
        one (fn [batch est]
              (let [n (atom est)]
              (-> (js/fetch embed-url
                            (clj->js {:method "POST"
                                      :headers (cond-> {"content-type" "application/json"}
                                                 key (assoc "authorization" (str "Bearer " key)))
                                      :body (js/JSON.stringify
                                             (clj->js {:model embed-model :input batch}))}))
                  (p/then (fn [r] (if (.-ok r)
                                    (.text r)
                                    (p/rejected (ex-info "embed error" {:status (.-status r)})))))
                  (p/then (fn [txt]
                            (let [resp (parse-json txt)]
                              (some->> (get-in resp [:usage :total_tokens]) (reset! n))
                              (->> (:data resp) (sort-by :index) (mapv :embedding)))))
                  (p/finally (fn [_ _] (swap! reserved - est) (spend! @n))))))]
    (p/let [parts (p/all (map one batches ests))]
      (vec (apply concat parts)))))))

(defn long-reply-cap
  "The reply cap for a call that may need a long answer — an intake part,
   one node per entity of a dense document: the provider's :max-output,
   else max-reply-tokens. A 48k-char process document needed 7.5–9.3k
   tokens per 20k-char part (business dogfood 2026-10-15); at 4096 it is
   read in 32 small parts, at 16k in 3. A provider that still cuts the
   reply short gets the part read again in halves (chat/send!)."
  []
  (or (:max-output backend) (get-in presets [(:preset (config)) :max-output]) max-reply-tokens))

(defn chat!
  "system+user → promise of raw text. Provider chosen by saved config.
   Every reply is capped at `max-reply-tokens`, for every provider; a call
   that could cross today's budget is refused before it is sent. A call
   that fails, is aborted or cannot be parsed still counts its prompt.
   `max-tokens` lowers the cap for one call (the key check asks for a word)."
  ([system user] (chat! system user nil))
  ([system user {:keys [max-tokens]}]
  (let [cap (or max-tokens max-reply-tokens)
        prompt (estimate system user) worst (+ prompt cap)]
  (or (admit worst)
  (let [{:keys [shape url model key]} (config)
        n (atom prompt)
        [headers body]
        (case shape
          :anthropic
          [(cond-> {"content-type" "application/json"
                    "anthropic-version" "2023-06-01"}
             key (assoc "x-api-key" key
                        "anthropic-dangerous-direct-browser-access" "true"))
           {:model model :max_tokens cap :system system
            :messages [{:role "user" :content user}]}]
          [(cond-> {"content-type" "application/json"}
             key (assoc "authorization" (str "Bearer " key)))
           {:model model :max_tokens cap
            :messages [{:role "system" :content system}
                       {:role "user" :content user}]}])]
    (-> (js/fetch url (clj->js {:method "POST"
                                :headers headers
                                :body (js/JSON.stringify (clj->js body))}))
        (p/then (fn [r] (if (.-ok r)
                          (.text r)
                          (p/rejected (ex-info "LLM error" {:status (.-status r)})))))
        (p/then (fn [txt]
                  (let [resp (parse-json txt)
                        text (extract-text shape resp)
                        {:keys [input_tokens output_tokens prompt_tokens completion_tokens total_tokens]} (:usage resp)]
                    (reset! n (or total_tokens
                                  (when (or input_tokens output_tokens) (+ (or input_tokens 0) (or output_tokens 0)))
                                  (when (or prompt_tokens completion_tokens) (+ (or prompt_tokens 0) (or completion_tokens 0)))
                                  (estimate system user text)))
                    text)))
        (p/finally (fn [_ _] (swap! reserved - worst) (spend! @n)))))))))

(defn check-key!
  "One tiny real call with the saved config: promise of nil when the
   provider answered, else the failure (ex-data :status when it replied with
   an error, none when it could not be reached). It is a real call, so it
   counts against today's budget like any other (Denis 2026-10-03).
   Resolves nil, or {:error e}."
  []
  ;; the failure comes back WRAPPED: returned bare from a catch, the error
  ;; was adopted as a rejection again and the settings line hung on «checking»
  (-> (chat! "Reply with the single word: ok" "ping" {:max-tokens 5})
      (p/then (constantly nil))
      (p/catch (fn [e] {:error e}))))
