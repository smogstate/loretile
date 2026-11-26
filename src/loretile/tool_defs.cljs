(ns loretile.tool-defs
  "The assistant's read tools (:auto — pure reads, look-don't-touch). Each is
   one map; the prompt block and the dispatcher derive from loretile.tools.
   Results are written for the MODEL: plain lines, English field labels, the
   graph's own content in its own language. Loaded at boot by core."
  (:require [clojure.string :as str]
            [loretile.chat :as chat]
            [loretile.exercise :as ex]
            [loretile.fold :as fold]
            [loretile.graph-local :as graph-local]
            [loretile.sync :as sync]
            [loretile.graph-tools :as gt]
            [promesa.core :as p]
            [loretile.profile :as profile]
            [loretile.guide :as guide]
            [loretile.i18n :refer [t]]
            [loretile.store :as store]
            [loretile.learn :as learn]
            [loretile.tools :as tools]))

(defn- title [kg id] (get-in kg [:concepts id :title] id))

(tools/register!
 {:name "search-nodes" :confirm :auto
  :args {:query "words" :mode "hybrid|semantic|verbatim"}
  ;; i18n-ignore — a tool description for the model
  :doc "найти узлы графа (темы — в учебном графе) в названии, определении и материалах; mode: hybrid (по умолчанию — слова и смысл вместе), semantic (по смыслу, даже другими словами), verbatim (точно эта фраза, как написано); вернёт id, название, начало определения"
  ;; `fence` (the tutor's course): only topics inside it are found, and only
  ;; the material a learner of that course reads is searched
  ;; the graph store's FTS when this tab holds it and it holds this graph as
  ;; it stands (wiki/Graph-Store.md); words in memory otherwise, and inside a
  ;; tutor's fence (what a learner of the course may read decides there)
  :handler (fn [{:keys [kg fence locale graph-id]} {:keys [query mode]}]
             (let [mode (#{"hybrid" "semantic" "verbatim"} (some-> mode str))
                   lexical #(gt/search-nodes kg fence query (when fence (fn [id] (learn/objects-of kg id locale (fence id)))) mode)]
               (cond
                 (or (nil? graph-id) fence) (lexical)
                 ;; backend mode: the server's projection (wiki/Graph-Store.md)
                 sync/enabled? (-> (p/let [hits (sync/search graph-id query 40 mode)] (or (gt/format-hits kg nil hits) (lexical)))
                                   (p/catch (fn [_] (lexical))))
                 (graph-local/current? graph-id (store/graph-events graph-id))
                 (-> (p/let [hits (graph-local/search! graph-id query 40 mode)] (or (gt/format-hits kg nil hits) (lexical)))
                     (p/catch (fn [_] (lexical))))
                 :else (lexical))))})

(tools/register!
 {:name "open-node" :confirm :auto
  :args {:id "node-id"}
  ;; i18n-ignore
  :doc "открыть узел (тему) по id: определение, все связи в обе стороны, материалы, вопросы, открытые вопросы к эксперту"
  ;; questions are COUNTED, never shown: no stem, no option, no key reaches the model.
  ;; With a `fence` (the tutor: {topic level} of its course) the material is
  ;; what a learner of that course reads, and no title outside it is named
  :handler (fn [{:keys [kg fence locale]} {:keys [id]}]
             (gt/open-node {:kg kg :fence fence
                            :objects-of (when fence #(learn/objects-of kg % locale (fence %)))}
                           id))})

(tools/register!
 {:name "list-courses" :confirm :auto
  :args {}
  ;; i18n-ignore
  :doc "курсы этого графа: id, название, язык, цели с уровнями"
  :handler
  (fn [{:keys [kg]} _]
    (if (empty? (:courses kg))
      "no courses in this graph"
      (str/join "\n" (for [[id c] (sort-by key (:courses kg))]
                       (str id " — " (:title c) " [" (or (:locale c) "any") "]: "
                            (str/join ", " (for [[t l] (:targets c)] (str (title kg t) " @" (name l)))))))))})

(tools/register!
 {:name "check-cqs" :confirm :auto
  :args {}
  ;; i18n-ignore
  :doc "вопросы компетенции этого графа и что на них НЕ отвечено: по каждому — сколько узлов спрошено, сколько ответили, какие молчат; связи, которых ни один вопрос не требует"
  ;; computed by the fold, never judged by the model (wiki/Profiles.md)
  :handler (fn [{:keys [kg]} _] (gt/check-cqs kg))})

(tools/register!
 {:name "learner-gaps" :confirm :auto
  :args {:course "course-id"}
  ;; i18n-ignore
  :doc "где ученик этого устройства стоит по курсу: что знает, где пробелы, что не проверить (по журналу попыток)"
  :handler
  (fn [{:keys [kg graph-id]} {:keys [course]}]
    (if-let [c (get-in kg [:courses course])]
      (let [{:keys [known gaps untested]} (learn/course-report kg graph-id (assoc c :graph-id graph-id))
            line (fn [label ids] (str label " (" (count ids) "): " (if (seq ids) (str/join ", " (map #(title kg %) ids)) "—")))]
        (str/join "\n" [(str "course " course " — " (:title c))
                        (line "known" known) (line "gaps" gaps) (line "untested" untested)]))
      (str "no course with id " course " (use list-courses)")))})

(tools/register!
 {:name "search-catalog" :confirm :auto
  :args {:query "words"}
  ;; i18n-ignore
  :doc "найти опубликованные курсы во ВСЕХ графах этого устройства по словам (название курса и тем); вернёт id курса, граф, сколько тем ученик уже доказал"
  :handler
  (fn [_ {:keys [query]}]
    (let [q (gt/stems query)
          rows (for [{gid :id gtitle :title gkg :kg} (store/graph-kgs)
                     [cid c] (:courses gkg)
                     :let [hay (gt/fold-text (str (:title c) " " (str/join " " (map #(title gkg (first %)) (:targets c)))))
                           score (if (empty? q) 1 (count (filter #(str/includes? hay %) q)))]
                     :when (pos? score)]
                 [score gid gtitle gkg cid c])]
      (if (empty? rows)
        (str "no published course matches «" query "» on this device")
        (str/join "\n" (for [[_ gid gtitle gkg cid c] (take 6 (sort-by (comp - first) rows))
                             :let [p (learn/course-progress gkg gid (assoc c :graph-id gid))]]
                         (str cid " — " (:title c) " [graph «" gtitle "», " (or (:locale c) "any") "]: "
                              (:total p) " topics, already proven " (:closed p) ", " (name (:state p))))))))})

(defn- ontology-of
  "A registry row's ontology as the model reads it: learning, the built-in
   types ontology, or the ontology graph it is bound to."
  [{:keys [ontology]}]
  (cond (nil? ontology) "learning"
        (= fold/types-ontology ontology) "ontology (this graph describes types and relations)"
        :else (str "bound to ontology graph " ontology " «" (store/graph-title ontology) "»")))

;; the server graphs list-graphs last saw — open-graph's card is labelled
;; synchronously, and a server graph is known only by that listing
(defonce ^:private server-listed (atom {}))

(tools/register!
 {:name "list-graphs" :confirm :auto
  :args {}
  ;; i18n-ignore
  :doc "графы: какие есть на этом устройстве (id, название, онтология, какой открыт) и — если есть сервер — какие хранит сервер, но их ещё нет здесь"
  :handler
  (fn [_ _]
    (let [here (str "on this device:\n"
                    (str/join "\n" (for [g @store/graphs]
                                     (str (:id g) " — «" (:title g) "» [" (ontology-of g) "]"
                                          (when (= (:id g) @store/current) " (open now)")))))]
      (if-not sync/enabled?
        here
        (-> (p/let [rows (sync/server-graphs)
                    mine (set (map :id @store/graphs))
                    others (remove #(mine (:id %)) rows)]
              (reset! server-listed (into {} (map (juxt :id identity) rows)))
              (str here "\n\non the server, not on this device yet:\n"
                   (if (seq others)
                     (str/join "\n" (for [g others] (str (:id g) " — «" (or (:title g) (:id g)) "» (" (or (:events g) 0) " events)")))
                     "none")))
            (p/catch (fn [_] (str here "\n\nthe server did not answer")))))))})

;; --- actions: one-tap cards, never run by the model (:action) ----------------

(defn- ontology-choice
  "The ontology a new graph may take, normalized, or nil when there is no
   such choice: learning (default), the types ontology, or a whole ontology
   graph of this device."
  [o]
  (let [o (some-> o str str/trim not-empty)]
    (cond (or (nil? o) (= "learning" o)) "learning"
          (#{fold/types-ontology "types"} o) fold/types-ontology
          (and (store/graph-on-device? o) (store/whole-ontology? o)) o)))

(defn- opened!
  "The assistant opened graph `gid` (nil: it did not). Cards left from the
   graph before would hold the conversation (they belong to another graph,
   chat/foreign?): that sitting goes to the history, where it can be
   reopened, and a new one starts here, saying which graph is open."
  [gid]
  (when gid
    (when (seq @chat/cards) (chat/new-conversation!))
    (swap! chat/msgs conj {:who :sys :text (t :chat/graph-opened {"{title}" (store/graph-title gid)})})))

(tools/register!
 {:name "create-graph" :confirm :action
  :args {:title "the new graph's name" :ontology "learning | ontology | <ontology graph id from list-graphs>"}
  ;; i18n-ignore
  :doc "создать новый граф и открыть его: ontology — learning (учебный, по умолчанию), ontology (граф, описывающий типы и связи) или id графа-онтологии этого устройства (данные по ней)"
  ;; the title is model text on a button: capped
  :label (fn [_ {:keys [title ontology]}]
           (let [tt (str/trim (str title)) o (ontology-choice ontology)]
             (when (and (seq tt) o)
               (t :chat/action-create-graph
                  {"{title}" (if (> (count tt) 60) (str (subs tt 0 60) "…") tt)
                   "{ontology}" (cond (= "learning" o) (t :ui/ontology-learning-builtin)
                                      (= fold/types-ontology o) (t :ui/ontology-types-builtin)
                                      :else (store/graph-title o))}))))
  :run (fn [_ {:keys [title ontology]}]
         (store/after! (store/create-graph! (str/trim (str title)) (ontology-choice ontology)) opened!))})

(tools/register!
 {:name "open-graph" :confirm :action
  :args {:id "graph-id from list-graphs"}
  ;; i18n-ignore
  :doc "открыть граф: с этого устройства или — с сервера (из list-graphs) — принести его сюда и открыть"
  :label (fn [_ {:keys [id]}]
           (let [id (str id)]
             (cond (= id @store/current) nil
                   (store/graph-on-device? id) (t :chat/action-open-graph {"{title}" (store/graph-title id)})
                   (get @server-listed id) (t :chat/action-open-graph {"{title}" (or (:title (get @server-listed id)) id)}))))
  :run (fn [_ {:keys [id]}]
         (let [id (str id)]
           (if (store/graph-on-device? id)
             (opened! (when (store/switch-graph! id) id))
             (when-let [g (get @server-listed id)] (p/then (sync/open-server-graph! g) opened!)))))})

(tools/register!
 {:name "go-to-node" :confirm :action
  :args {:id "node-id"}
  ;; i18n-ignore
  :doc "открыть узел (тему) на карте автора"
  :label (fn [{:keys [kg]} {:keys [id]}]
           (when (get-in kg [:concepts id]) (t :chat/action-go-topic {"{title}" (title kg id)})))
  :run (fn [_ {:keys [id]}] (set! js/location.hash (str "#/c/" id)))})

(tools/register!
 {:name "start-course" :confirm :action
  :args {:course "course-id"}
  ;; i18n-ignore
  :doc "открыть курс в режиме ученика"
  ;; any graph on the device — the catalog spans them all
  :label (fn [_ {:keys [course]}]
           (when-let [{c :course} (store/find-course course)] (t :chat/action-start-course {"{title}" (:title c)})))
  :run (fn [_ {:keys [course]}] (set! js/location.hash (str "#/learn/" course)))})

(tools/register!
 {:name "draft-exercises" :confirm :action
  :args {:id "topic-id"}
  ;; i18n-ignore
  :doc "предложить упражнения с запуском по теме (стенд: каждое проверяется запуском)"
  :label (fn [{:keys [kg]} {:keys [id]}]
           (when (get-in kg [:concepts id]) (t :chat/action-draft {"{title}" (title kg id)})))
  :run (fn [{:keys [kg]} {:keys [id]}] (chat/draft-exercises! id (ex/graph-runtime kg)))})

(tools/register!
 {:name "build-from-material" :confirm :action
  :args {:goal "what the learner wants to learn"}
  ;; i18n-ignore
  :doc "подходящего курса нет: собрать курс из СВОЕГО материала ученика (файл) — личный граф, темы, вопросы, курс"
  ;; the goal is model text on a button: capped
  :label (fn [_ {:keys [goal]}]
           (let [g (str/trim (str goal))]
             (when (seq g) (t :guide/action-build {"{goal}" (if (> (count g) 60) (str (subs g 0 60) "…") g)}))))
  ;; the tap is the user gesture a file picker needs; everything after it
  ;; runs only once a file is chosen
  :run (fn [_ {:keys [goal]}]
         (let [goal (str/trim (str goal))
               input (js/document.createElement "input")]
           (set! (.-type input) "file")
           (set! (.-accept input) chat/accept-types)
           (set! (.-onchange input)
                 (fn [_]
                   ;; the file is read FIRST: an unreadable, empty or non-text
                   ;; file creates nothing and says so where the learner is
                   (when-let [f (aget (.-files input) 0)]
                     (let [say! #(swap! guide/msgs conj {:who :sys :text %})]
                       (-> (chat/read-file f)
                           (.then (fn [d]
                                    (cond
                                      (chat/blank-doc? d) (say! (t :guide/build-empty {"{name}" (.-name f)}))
                                      ;; never mid-intake: its cards would land in the new sitting
                                      @chat/busy (say! (t :chat/busy-wait))
                                      (store/new-graph! goal {:personal? true})
                                      (do (chat/new-conversation!)
                                          (set! js/location.hash "#/chat")
                                          (swap! chat/msgs conj {:who :sys :text (t :guide/building {"{goal}" goal})})
                                          (-> (chat/attach-doc! (assoc d :file f))
                                              (.then (fn [_] (chat/send! goal)))
                                              (.then (fn [_] (chat/add-build-card! goal)))
                                              (.catch (fn [e] (swap! chat/msgs conj {:who :sys :text (str (t :chat/error-prefix) (ex-message e))}))))))))
                           (.catch (fn [e] (say! (t :guide/build-unreadable {"{name}" (.-name f) "{error}" (ex-message e)})))))))))
           (.click input)))})
