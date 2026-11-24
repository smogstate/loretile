(ns loretile.graph-tools
  "The READ tools over a folded graph, pure and cljc: the assistant in the
   browser (loretile.tool-defs) and the MCP server for coding agents
   (loretile.mcp) answer with the same functions. Results are written for a
   MODEL: plain lines, English field labels, the graph's own content in its
   own language. Nothing here writes."
  (:require [clojure.string :as str]
            [loretile.fold :as fold]
            [loretile.profile :as profile]))

(defn words
  "Query words: split on space and punctuation — CLJS regexes have no u
   flag, so \\p{L} would match nothing (the CLAUDE.md gotcha)."
  [s]
  (->> (str/split (str/lower-case (str s)) #"[\s.,;:!?«»\"'()\[\]/\\—–-]+") (filter #(>= (count %) 2))))

(defn fold-text
  "Text as search compares it: lower case, ё as е (a query typed «счет»
   missed «Выставление счёта»). One char for one, so indices still point
   into the original."
  [s]
  (-> (str s) str/lower-case (str/replace "ё" "е")))

(defn stems
  "Query words as search matches them: folded, and a word of 5+ letters
   cut by its last letter and to at most 7 — a crude stand-in for endings,
   so «счета», «счёту» and «счёт» meet at «счет», «invoices» finds
   «invoice». Matching is by substring, so a stem finds every longer form."
  [s]
  (for [w (words (fold-text s))]
    (if (>= (count w) 5) (subs w 0 (min (dec (count w)) 7)) w)))

(defn clip [s n] (let [s (fold/plain-text (str s))] (if (> (count s) n) (str (subs s 0 n) "…") s)))

(defn title [kg id] (get-in kg [:concepts id :title] id))

(defn day
  "A timestamp (ms) as YYYY-MM-DD."
  [ts]
  (when ts
    #?(:cljs (subs (.toISOString (js/Date. ts)) 0 10)
       :clj (str (.toLocalDate (.atZone (java.time.Instant/ofEpochMilli ts) java.time.ZoneOffset/UTC))))))

(defn authorship
  "«added by A; last changed by B on YYYY-MM-DD» — nil for a topic with no
   recorded author."
  [kg c]
  (when-let [a (:added-by c)]
    (str "added by " (fold/author-name kg a)
         (when-let [b (:changed-by c)]
           (str "; last changed by " (fold/author-name kg b) (when-let [d (day (:changed-at c))] (str " on " d)))))))

(defn inferred-note
  "« (inferred: why)» on an edge the source does not state; empty otherwise."
  [e]
  (if (:inferred? e)
    (str " (inferred" (when (seq (:justification e)) (str ": " (clip (:justification e) 140))) ")")
    ""))

(defn- snippet
  "~`n` chars of `text` around the first query word it holds."
  [text q n]
  (let [t (fold/plain-text (str text))
        low (fold-text t)
        at (some #(let [i (str/index-of low %)] (when i i)) q)]
    (when at
      (let [start (max 0 (- at (quot n 3)))
            end (min (count t) (+ start n))]
        (str (when (pos? start) "…") (str/replace (subs t start end) #"\s+" " ") (when (< end (count t)) "…"))))))

(defn search-nodes
  "Stem score (`stems`, ё as е) over title (3), definition (1) and material (1 — the 10%
   tolerance of the business dogfood lived in material, where a title and
   definition search never looked); `fence` (the tutor's course) limits
   what can be found, `objects-of` what material a fenced reader sees. Up to
   8 lines «id — title: definition…», with the matching words of material
   when only material matched."
  ([kg fence query] (search-nodes kg fence query nil nil))
  ([kg fence query objects-of] (search-nodes kg fence query objects-of nil))
  ([kg fence query objects-of mode]
   ;; :verbatim — the query as written, a phrase; anything else — its words
   ;; (no vectors in memory: :semantic and :hybrid search by words here)
   (let [q (if (= :verbatim (keyword mode)) [(str/trim (fold-text query))] (distinct (stems query)))
         mat (fn [id] (str/join "\n" (keep :text (if (and fence objects-of) (objects-of id) (fold/concept-objects kg id)))))
         score (fn [[id c]] (let [t (fold-text (:title c)) d (fold-text (:definition c))
                                  m (fold-text (mat id))]
                              (reduce + (for [w q] (+ (if (str/includes? t w) 3 0) (if (str/includes? d w) 1 0)
                                                      (if (str/includes? m w) 1 0))))))
         hits (->> (:concepts kg) (filter #(or (nil? fence) (fence (key %))))
                   (map (juxt identity score)) (filter #(pos? (second %)))
                   (sort-by (comp - second)) (take 8) (map first))]
     (if (empty? hits)
       ;; the way out an agent took on its own in the dogfood: read every title
       (str "no node matches «" query "» (" (count (:concepts kg)) " nodes in the graph) — try other words, "
            "the graph's own language (see describe-ontology), or list-nodes to read every title")
       (str/join "\n" (for [[id c] hits
                            :let [head (fold-text (str (:title c) " " (:definition c)))
                                  only-mat? (not-any? #(str/includes? head %) q)]]
                        (str id " — " (:title c)
                             (when (:kind c) (str " [" (name (:kind c)) "]"))
                             ": " (clip (:definition c) 120)
                             (when only-mat? (when-let [s (snippet (mat id) q 140)] (str "\n    material: " s))))))))))

(defn format-hits
  "Search hits from the graph store (FTS, best first: {:id :class :short
   :parent}) as `search-nodes` lines: a hit on material names its topic, with
   the matching words; up to 8 topics, each once. nil when none is a topic of
   `kg` inside `fence`."
  [kg fence hits]
  (let [lines (->> hits
                   (keep (fn [{:keys [id class short parent]}]
                           (let [cid (if (= "concepts" class) id parent)]
                             (when (and (get-in kg [:concepts cid]) (or (nil? fence) (fence cid)))
                               [cid (when (not= "concepts" class) short)]))))
                   (reduce (fn [acc [cid mat]] (if (some #(= cid (first %)) acc) acc (conj acc [cid mat]))) [])
                   (take 8))]
    (when (seq lines)
      (str/join "\n" (for [[cid mat] lines
                           :let [c (get-in kg [:concepts cid])]]
                       (str cid " — " (:title c)
                            (when (:kind c) (str " [" (name (:kind c)) "]"))
                            ": " (clip (:definition c) 120)
                            (when mat (str "\n    material: " (clip mat 140)))))))))

(defn- edge-props-note
  "An edge's own properties, inline after its endpoint: « {since = 2024}»."
  [e]
  (when-let [ps (seq (fold/edge-props e))]
    (str " {" (str/join "; " (for [[k v] ps] (str (name k) " = " v))) "}")))

(defn open-node
  "One node for the model: definition, every relation both ways (the
   learning three by name, a custom ontology in its own words), material,
   question COUNTS (never stems), open expert questions. `fence` = the
   tutor's course: nothing outside it is named; `objects-of` (optional) =
   what a learner of that course reads instead of every object;
   `material-chars` = how much of each piece of material to show (160 for
   the assistant's prompts; the MCP server asks for much more — a business
   dogfood agent read «the list is cut off» where the graph held all 12
   PO exceptions)."
  [{:keys [kg fence objects-of material-chars] :or {material-chars 160}} id]
  (if-let [c (when (or (nil? fence) (fence id)) (get-in kg [:concepts id]))]
    (let [es (:edges kg)
          p (fold/profile kg)
          learning? (profile/learning? p)
          ;; a link the model inferred (the source does not state it) says so,
          ;; with its reason — an agent must tell a quote from a guess
          rel (fn [pred f] (->> es (filter pred) (filter #(or (nil? fence) (fence (f %))))
                                (map #(str (title kg (f %)) (edge-props-note %) (inferred-note %))) (str/join ", ")))
          objs (if (and fence objects-of) (objects-of id) (fold/concept-objects kg id))
          items (fold/concept-items kg id)
          qs (filter #(and (= id (:concept-id %)) (not (:answer %))) (vals (:questions kg)))
          answered (filter #(and (= id (:concept-id %)) (:answer %)) (vals (:questions kg)))
          srcs (for [e es :when (and (= id (:from e)) (= :grounded-in (:type e)))]
                 (get-in kg [:sources (:to e) :title] (:to e)))]
      (str/join "\n"
                (remove nil?
                        (concat
                         [(str id " — " (:title c) (when (fold/stale? kg id) " [STALE]")
                               (when-not learning?
                                 (let [k (name (or (:kind c) "node")) l (profile/kind-label p (:kind c))]
                                   (str " [" k (when (and l (not= l k)) (str ": " l)) "]"))))
                          (str "definition: " (:definition c))
                          ;; what a person set on the node (2026-11-19)
                          (when-let [ps (seq (fold/node-props c))]
                            (str "properties: " (str/join "; " (for [[k v] ps] (str (name k) " = " v)))))
                          ;; the passage it was written from, as it read then: after an
                          ;; update, set it beside read-source to see what changed
                          (when (seq (:evidence c)) (str "quote from its source: «" (clip (:evidence c) 400) "»"))
                          (authorship kg c)]
                         (if learning?
                           [(str "requires: " (or (not-empty (rel #(and (= :requires (:type %)) (= id (:from %))) :to)) "—"))
                            (str "required by: " (or (not-empty (rel #(and (= :requires (:type %)) (= id (:to %))) :from)) "—"))
                            (when-let [x (not-empty (rel #(and (= :is-confused-with (:type %)) (#{(:from %) (:to %)} id))
                                                         #(if (= id (:from %)) (:to %) (:from %))))]
                              (str "confused with: " x))]
                           ;; a custom ontology: every relation, both ways, in its own words
                           (for [r (profile/relations p) :when (not= :grounded-in (:id r))
                                 :let [out (not-empty (rel #(and (= (:id r) (:type %)) (= id (:from %))) :to))
                                       in (not-empty (rel #(and (= (:id r) (:type %)) (= id (:to %))) :from))]
                                 :when (or out in)]
                             (str (when out (str (:label r) ": " out))
                                  (when (and out in) "; ")
                                  (when in (str (or (:inverse r) (:label r)) " ← " in)))))
                         [(when (seq srcs) (str "sources: " (str/join "; " srcs)))
                          (str "material (" (count objs) "):")
                          ;; levels are the learning engine's word — a business node has none
                          (str/join "\n" (for [o objs] (str "  - [" (name (:kind o)) (when learning? (str ", " (name (:level o)))) ", " (:locale o)
                                                           (when (:origin o) (str ", " (name (:origin o)))) "] " (clip (:text o) material-chars))))
                          (when learning?
                            (str "questions: " (count items)
                                 (when (seq items)
                                   (str " (" (str/join ", " (for [[k n] (frequencies (map #(str (name (:kind %)) "@" (name (:level %))) items))]
                                                             (str n "× " k))) ")"))))
                          (when (seq qs) (str "open expert questions: " (str/join " | " (map :question qs))))
                          (when (seq answered) (str "expert answers: " (str/join " | " (map #(str (:question %) " → " (:answer %)) answered))))
                          (when (= :deprecated (:status c)) "status: DEPRECATED — kept for history, not for new courses")
                          (when-let [gaps (seq (get (fold/cq-gaps kg) id))]
                            (str "unanswered competency questions: " (str/join " | " (map :question gaps))))]))))
    (if (and fence (get-in kg [:concepts id]))
      (str id " is outside this course — stay within its topics")
      (str "no node with id " id))))

(defn list-nodes
  "Every node, or those of one type, or only the STALE ones (their source
   changed since they were written — what to re-check after an update):
   «id — title», foundations first along the ordering relation."
  ([kg type] (list-nodes kg type false))
  ([kg type stale?]
  (let [p (fold/profile kg)
        ids (fold/topo-order kg (set (keys (:concepts kg))) (profile/ordering-relation p))
        ids (if type (filter #(= type (get-in kg [:concepts % :kind])) ids) ids)
        ids (if stale? (filter #(fold/stale? kg %) ids) ids)]
    (if (empty? ids)
      (cond stale? "no stale nodes: no source changed under what the graph says"
            type (str "no nodes of type " (name type))
            :else "the graph is empty")
      (str/join "\n" (for [id ids :let [c (get-in kg [:concepts id])]]
                       (str id " — " (:title c) (when (:kind c) (str " [" (name (:kind c)) "]")))))))))

(defn list-edges
  "Relations as lines «from-title (id) —reads as→ to-title (id)», optionally
   only one relation and/or only those touching one node; grounded-in
   (node → source) is left out unless asked for. Capped at `cap` lines, the
   rest counted — an agent asking «who keeps which store» gets one answer
   instead of opening every node (dogfood 2026-10-14, Q5: 22 turns)."
  ([kg relation node] (list-edges kg relation node 200))
  ([kg relation node cap]
   (let [p (fold/profile kg)
         es (->> (:edges kg)
                 (filter #(if relation (= relation (:type %)) (not= :grounded-in (:type %))))
                 (filter #(or (nil? node) (= node (:from %)) (= node (:to %))))
                 (sort-by (juxt (comp name :type) :from :to)))
         label (fn [id] (str (or (get-in kg [:concepts id :title]) (get-in kg [:sources id :title]) id) " (" id ")"))
         lines (for [{:keys [from to type] :as e} (take cap es)]
                 (str (label from) " —" (profile/label p type) "→ " (label to) (edge-props-note e) (inferred-note e)))]
     (cond
       (and relation (nil? (profile/relation p relation))) (str "no relation " (name relation) " in this ontology (see describe-ontology)")
       (empty? es) "no such edges"
       :else (str (str/join "\n" lines)
                  (when (> (count es) cap) (str "\n… and " (- (count es) cap) " more (narrow by relation or node)")))))))

(defn describe-ontology
  "The graph's vocabulary: node types and relations with their words."
  [kg]
  (let [p (fold/profile kg)]
    (str "ontology: " (:title p) (when (seq (:brief p)) (str " — " (:brief p))) "\n"
         (when-let [{:keys [team contact]} (not-empty (:owner kg))]
           (str "owner: " team (when (seq contact) (str " (" contact ")")) " — who answers for this graph\n"))
         "node types:\n" (profile/node-type-lines p) "\n"
         "relations (id — reads as; from → to; the [ordering] one is what the layout follows, and reads from the LATER node to the EARLIER one):\n" (profile/relation-lines p)
         (when-let [qs (seq (vals (:cqs kg)))]
           (str "\ncompetency questions the graph must answer: " (str/join " | " (map :question qs)))))))

(defn check-cqs
  "Every competency question with its check — computed by the fold, never
   judged by a model — and the relations no question needs."
  [kg]
  (let [report (fold/cq-report kg)
        loose (fold/unjustified-relations kg)]
    (if (and (empty? report) (empty? loose))
      "no competency questions in this graph"
      (str/join "\n"
                (concat
                 (for [{:keys [id question pattern check]} report]
                   (str id " — " question
                        (when pattern (str " [" (name (:relation pattern))
                                           (when (:from-type pattern) (str " from " (name (:from-type pattern)))) "]"))
                        (if check
                          (str ": answered " (:answered check) "/" (:asked check)
                               (when (seq (:gaps check)) (str "; gaps: " (str/join ", " (map #(title kg %) (:gaps check))))))
                          ": no pattern — a person judges it")))
                 (when (seq loose)
                   [(str "relations no question needs: " (str/join ", " (map name loose)))]))))))

;; --- the source behind a node (2026-10-21) ----------------------------------
;; Measured: on one handbook page an agent reading the raw document scored
;; ~70/70 and the graph ~55/70 — the intake loses detail (the Thursday
;; payment run, NetSuite). So the graph navigates and the document answers:
;; with the source texts at hand (`texts` = {source-id text}, loaded by the
;; MCP server from a folder), a node opens onto the passage it was written
;; from, and a search over the sources names the nodes written from there.

(defn- window-at
  "`cap` chars of `text` around `pos`: a third before, the rest after."
  [text pos cap]
  (let [n (count text)
        start (max 0 (min (- pos (quot cap 3)) (- n cap)))
        end (min n (+ start cap))]
    [start end (subs text start end)]))

(defn- source-ids-of [kg id]
  (distinct (for [e (:edges kg) :when (and (= id (:from e)) (= :grounded-in (:type e)))] (:to e))))

(defn- probe
  "Where an anchor (a quote, a title) first occurs in folded text, by its
   first 60 chars; nil when it does not."
  [low a]
  (let [a (str/trim (fold-text (fold/plain-text (str a))))
        a (subs a 0 (min 60 (count a)))]
    (when (>= (count a) 4) (str/index-of low a))))

(defn- closest-paragraph
  "Where in `text` the paragraph sharing most stems with `words` starts
   (at least two in common); nil when none does."
  [text words]
  (let [q (distinct (remove #(< (count %) 3) (stems words)))
        low (fold-text text)
        starts (cons 0 (loop [from 0 acc []]
                         (if-let [i (str/index-of text "\n\n" from)] (recur (+ i 2) (conj acc (+ i 2))) acc)))
        best (->> (partition 2 1 (concat starts [(count text)]))
                  (map (fn [[a b]] [a (count (filter #(str/includes? (subs low a b) %) q))]))
                  (sort-by (comp - second))
                  first)]
    (when (and best (>= (second best) 2)) (first best))))

(defn read-source
  "The passage of each source a node was written from: `cap` chars around
   `query` when given (its first stem found), else around the node's
   evidence quote, its material or its title. A source the server has no
   text for says so and shows the stored quote."
  [kg texts id query cap]
  (let [c (get-in kg [:concepts id])
        cap (max 500 (min 12000 (or cap 3000)))]
    (cond
      (nil? c) (str "no node with id " id)
      (empty? (source-ids-of kg id)) (str id " has no source: it was written by a person, or before sources were kept")
      :else
      (str/join
       "\n\n"
       (for [sid (source-ids-of kg id)
             :let [s (get-in kg [:sources sid]) t (get texts sid) stitle (or (:title s) sid)]]
         (if-not t
           (str "source «" stitle "»: its text is not available to this server"
                (when (seq (:evidence c)) (str "\nthe quote the node was written from: «" (:evidence c) "»")))
           (let [low (fold-text t)
                 qpos (when (seq query) (some #(str/index-of low %) (stems query)))
                 ;; a node with a quote is found BY its quote: its title alone
                 ;; («Payment run») would match a heading and hide that the
                 ;; sentence it was written from is gone
                 anchors (if (seq (:evidence c))
                           [(:evidence c)]
                           (concat (map :text (fold/concept-objects kg id)) [(:title c)]))
                 pos (or qpos (some #(probe low %) anchors))
                 ;; the exact words are gone (the source changed under the node):
                 ;; the paragraph sharing most of the quote's words is where it was —
                 ;; from the top of a long page an agent never found it (change test
                 ;; 2026-10-23: «$300 a night» sought at the start of a 67k-char page)
                 near (when (and (not pos) (seq (:evidence c))) (closest-paragraph t (str (:evidence c) " " (:title c))))
                 [a b w] (window-at t (or pos near 0) cap)]
             (str "source «" stitle "» (chars " a "–" b " of " (count t)
                  (cond qpos (str ", around «" query "»")
                        pos ", around the node's quote"
                        near ", around the passage closest to the node's quote — its exact words are no longer in the source: it CHANGED"
                        :else (str ", from the start — " (if (seq query) (str "«" query "» not found") "the quote was not found")))
                  "):\n" w))))))))

(defn search-sources
  "Paragraphs of the source texts that hold the query's words (stems, ё as
   е), best first: «source @ char: passage», and the nodes written from
   that passage — the way back from the document into the graph."
  [kg texts query]
  (let [q (distinct (stems query))
        paras (for [[sid t] texts
                    :let [low (fold-text t)
                          ;; paragraphs: split on blank lines, keep offsets
                          starts (cons 0 (loop [from 0 acc []]
                                           (if-let [i (str/index-of t "\n\n" from)]
                                             (recur (+ i 2) (conj acc (+ i 2)))
                                             acc)))]
                    [a b] (partition 2 1 (concat starts [(count t)]))
                    :let [p (subs low a b)
                          hit (count (filter #(str/includes? p %) q))]
                    :when (pos? hit)]
                {:sid sid :a a :b b :hit hit :text (subs t a b)})
        best (take 6 (sort-by (juxt (comp - :hit) :sid :a) paras))]
    (cond
      (empty? texts) "no source texts are loaded on this server"
      (empty? q) "say what to look for"
      (empty? best) (str "no source passage matches «" query "»")
      :else
      (str/join
       "\n\n"
       (for [{:keys [sid a b text]} best
             :let [from (for [[cid c] (sort-by key (:concepts kg))
                              :when (and (some #{sid} (source-ids-of kg cid))
                                         (when-let [i (probe (fold-text (get texts sid)) (:evidence c))] (<= a i b)))]
                          (str cid))]]
         (str "«" (get-in kg [:sources sid :title] sid) "» @ " a ": "
              (let [w (str/replace (str/trim text) #"\s+" " ")] (if (> (count w) 600) (str (subs w 0 600) "…") w))
              (when (seq from) (str "\n    nodes written from here: " (str/join ", " from)))))))))
