(ns loretile.tools
  "The tool registry — the assistant's hands (wiki/Assistant.md, the
   kintale.tools shape). A tool is ONE map {:name :doc :args :confirm
   :handler}; everything the model and the chat need — the prompt block, the
   dispatcher — is DERIVED from the registry, so adding a tool is adding one
   map, never edits across prompt and chat.

   Confirm classes keep the covenant «nothing lands unreviewed»:
     :auto   — pure reads: run immediately, the result goes back to the model
               in a bounded look-don't-touch loop
     :card   — writes: the ordinary proposal cards (the intake's own JSON)
     :action — moves or runs something, behind a one-tap card: the model
               proposes it in \"actions\", the user taps, only then it runs

   Calls are our own JSON in the reply ({\"tools\": [{\"name\" …}]}), not a
   provider's native tool calling — so every provider, Yandex and DeepSeek
   included, gets the same hands.

   Handlers are (fn [ctx args] -> string): ctx {:kg :graph-id}; the string
   is what the model reads. Tool definitions live in loretile.tool-defs,
   loaded at boot (they need the learner loop, which needs the chat)."
  (:require [clojure.string :as str]
            [promesa.core :as p]
            [loretile.llm :as llm]))

(defonce ^:private registry (atom {}))

(def max-hops
  ;; look-don't-touch: at most two rounds of reads before the model must answer
  2)

(def max-calls
  ;; per round — a model asking for twenty lookups is not reading, it is crawling
  4)

(defn register! [{:keys [name] :as tool}]
  (swap! registry assoc name tool))

(defn tools [] (sort-by :name (vals @registry)))

(defn- reads [] (filter #(= :auto (:confirm %)) (tools)))
(defn- actions [] (filter #(= :action (:confirm %)) (tools)))

(defn prompt-block
  "The tools as the model sees them — derived, never hand-written. `only` =
   the read tools this caller may use; `acts` = the actions it may propose
   (default: all actions when `only` is absent, none when it is given)."
  [& [{:keys [only acts]}]]
  (when (seq @registry)
    (str
     ;; i18n-ignore
     "\n\nИНСТРУМЕНТЫ (только чтение). Если, чтобы ответить или не дублировать, нужно посмотреть в базу — "
     ;; i18n-ignore
     "верни ТОЛЬКО {\"tools\":[{\"name\":\"…\",\"args\":{…}}]} (не больше " max-calls " вызовов) и ничего больше: "
     ;; i18n-ignore
     "получишь результаты и ответишь. Не выдумывай того, что можно посмотреть.\n"
     (str/join "\n" (for [{:keys [name args doc]} (cond->> (reads) only (filter #(only (:name %))))]
                      (str "- " name " " (js/JSON.stringify (clj->js args)) " — " doc)))
     (when-let [as (seq (cond->> (actions)
                          (or only acts) (filter #((or acts #{}) (:name %)))))]
       (str
        ;; i18n-ignore
        "\n\nДЕЙСТВИЯ (пользователь подтверждает одним нажатием). Чтобы предложить перейти куда-то или запустить что-то, "
        ;; i18n-ignore
        "добавь в ответ \"actions\":[{\"name\":\"…\",\"args\":{…}}]. Сам ничего не запускай.\n"
        (str/join "\n" (for [{:keys [name args doc]} as]
                         (str "- " name " " (js/JSON.stringify (clj->js args)) " — " doc))))))))

(defn run-call
  "One requested call → {:name :args :result}. Only :auto tools run here;
   anything else is refused in words the model can read."
  [ctx {:keys [name args]}]
  (let [tool (get @registry (str name))]
    {:name (str name) :args args
     :result (cond
               (nil? tool) (str "unknown tool: " name)
               (= :action (:confirm tool)) (str name " is an action, not a read: propose it in \"actions\" for the user to confirm")
               (and (:allowed ctx) (not ((:allowed ctx) (str name)))) (str name " is not available here")
               (not= :auto (:confirm tool)) (str "tool " name " is not a read; propose it as a card instead")
               :else (try ((:handler tool) ctx (or args {}))
                          (catch :default e (str "tool failed: " (ex-message e)))))}))

(defn action-card
  "A proposed action → the card the user confirms, or nil when it names no
   action or its arguments point at nothing (`:label` returns nil)."
  [ctx {:keys [name args]}]
  (let [tool (get @registry (str name))]
    (when (= :action (:confirm tool))
      (when-let [label ((:label tool) ctx (or args {}))]
        {:card :action :data {:id (str "a-" (random-uuid)) :name (str name) :args (or args {}) :label label}}))))

(defn run-action!
  "The user tapped the card: now, and only now, it runs."
  [ctx {:keys [name args]}]
  (when-let [tool (get @registry name)]
    (when (= :action (:confirm tool)) ((:run tool) ctx args))))

(defn results-block
  "What the model reads back after a round of calls."
  [results]
  (str
   ;; i18n-ignore
   "РЕЗУЛЬТАТЫ ИНСТРУМЕНТОВ:\n"
   (str/join "\n\n" (for [{:keys [name args result]} results]
                      (str "## " name " " (js/JSON.stringify (clj->js args)) "\n" result)))))

(defn converse!
  "The look-don't-touch loop every assistant mode shares: ask, and while the
   reply asks for read tools (and rounds remain), run them, append the
   results, ask again. `parse` turns the raw reply into a map (a plain-text
   tutor answer becomes {:text …}); `on-call` sees each run; `on-limit` fires
   when the model still wanted to look after the last round. Every round is a
   model call, so the token budget covers it. Promise of the final map."
  [{:keys [system user ctx parse on-call on-limit max-tokens]}]
  (p/loop [user user hop 0]
    (p/let [reply (p/then (llm/chat! system user (when max-tokens {:max-tokens max-tokens})) parse)]
      (if (and (seq (:tools reply)) (< hop max-hops))
        ;; a read may answer later (search reads the graph store's Worker)
        (p/let [results (p/all (map (fn [c] (p/let [r (:result c)] (assoc c :result r)))
                                    (mapv #(run-call ctx %) (take max-calls (:tools reply)))))]
          (doseq [r results] (when on-call (on-call r)))
          (p/recur (str user "\n\n" (results-block results)) (inc hop)))
        (do (when (and (seq (:tools reply)) on-limit) (on-limit))
            reply)))))
