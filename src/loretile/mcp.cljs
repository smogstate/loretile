(ns loretile.mcp
  "An MCP server over one graph's log.edn, for coding agents
   (wiki/Profiles.md — the second consumer of a curated graph). Stdio
   transport, newline-delimited JSON-RPC 2.0, the handful of methods the
   protocol needs (initialize, tools/list, tools/call, ping) written by
   hand — no SDK, no new dependency. Every tool is a pure read from
   loretile.graph-tools; nothing here writes, so an agent can be handed a
   graph without being able to change it.

     node out/mcp.js /path/to/log.edn [--sources /folder/of/documents]
     claude mcp add loretile -- node /abs/out/mcp.js /abs/log.edn

   The file is re-read when it changes on disk (mtime), so an author
   exporting a new log.edn is seen by the next call. With --sources, each
   source of the graph is looked up in that folder by its title (then its
   ref) — text files only, never outside the folder — and two more tools
   open the document behind a node (read-source, search-sources): measured
   2026-10-21, the raw document answered what the graph had lost."
  (:require [clojure.string :as str]
            [cljs.reader :as reader]
            [loretile.fold :as fold]
            [loretile.graph-tools :as gt]
            [loretile.schema :as schema]))

(def protocol-version "2025-03-26")

(defonce ^:private fs (js/require "fs"))
(defonce ^:private state (atom {:path nil :mtime nil :kg nil :bad nil :sources nil :texts {}}))
(defonce ^:private node-path (js/require "path"))

(defn- source-file
  "The file in `dir` named by a source's title or ref — a relative path that
   stays inside `dir`, a regular file, text (no NUL in its first KB), under
   20 MB. nil otherwise."
  [dir names]
  (some (fn [n]
          (when (seq n)
            (let [inside? (fn [base p] (let [rel (.relative node-path base p)]
                                         (and (seq rel) (not (str/starts-with? rel "..")) (not (.isAbsolute node-path rel)))))
                  p (.resolve node-path dir n)]
              ;; and its real path too: a symlink in the folder never leads out of it
              (when (and (inside? dir p)
                         (try (inside? (.realpathSync fs dir) (.realpathSync fs p)) (catch :default _ false)))
                (try (let [st (.statSync fs p)]
                       (when (and (.isFile st) (< (.-size st) 20000000))
                         (let [t (.readFileSync fs p "utf8")]
                           (when-not (str/includes? (subs t 0 (min 1024 (count t))) "\u0000") t))))
                     (catch :default _ nil))))))
        names))

(defn- load-texts [kg dir]
  (if-not dir
    {}
    (into {} (keep (fn [[sid s]] (when-let [t (source-file dir [(:title s) (:ref s)])] [sid t]))) (:sources kg))))

(defn- load-log!
  "Fold the file into `state` when it is new or changed. Every event must
   pass the schema and the guard (a log from elsewhere is untrusted, as in
   the app's import); a bad file keeps the previous graph and reports."
  []
  (let [{:keys [path mtime]} @state
        now (try (.-mtimeMs (.statSync fs path)) (catch :default _ nil))]
    (when (and now (not= now mtime))
      (try
        (let [evs (vec (reader/read-string (str "[" (.readFileSync fs path "utf8") "]")))
              bad (first (remove schema/valid? evs))
              ;; and the guard, replayed in order, as the app's import does
              r (when-not bad (fold/replay fold/empty-kg evs))]
          (cond
            bad (swap! state assoc :mtime now :bad (str "invalid event " (:ev/id bad) ": " (pr-str (schema/explain bad))))
            (:error r) (swap! state assoc :mtime now :bad (str "refused event " (:ev/id (:bad r)) ": " (pr-str (:error r))))
            :else (swap! state assoc :mtime now :bad nil :kg (:kg r)
                         :texts (load-texts (:kg r) (:sources @state)))))
        (catch :default e (swap! state assoc :mtime now :bad (str "unreadable log.edn: " (ex-message e))))))
    (:kg @state)))

(def tools
  [{:name "describe-ontology"
    :description "The graph's vocabulary: who owns the graph, node types and relations with what each means (how it reads, from which type to which) and the competency questions the graph must answer. Call first."
    :inputSchema {:type "object" :properties {}}
    :run (fn [kg _] (gt/describe-ontology kg))}
   {:name "list-nodes"
    :description "Every node «id — title [type]», foundations first along the ordering relation; optionally only one node type, or only the STALE nodes (their source changed since they were written — after an update, what to re-check: open each, compare its quote with read-source)."
    :inputSchema {:type "object" :properties {:type {:type "string" :description "node type id (from describe-ontology)"}
                                              :stale {:type "boolean" :description "only nodes whose source changed"}}}
    :run (fn [kg {:keys [type stale]}] (gt/list-nodes kg (some-> type not-empty keyword) (true? stale)))}
   {:name "search-nodes"
    :description "Find nodes by words in their title, definition and material (word endings are loosely matched; use the graph's own language — see describe-ontology); returns id, title, type, the start of the definition, and the matching words when only the material matched. Nothing found: try other words, or list-nodes to read every title."
    :inputSchema {:type "object" :properties {:query {:type "string"}
                                              :mode {:type "string" :enum ["hybrid" "semantic" "verbatim"]
                                                     :description "verbatim: the query as written, a phrase; hybrid (default) and semantic search by words here — this server keeps no vectors"}}
                  :required ["query"]}
    :run (fn [kg {:keys [query mode]}] (gt/search-nodes kg nil query nil mode))}
   {:name "open-node"
    :description "One node by id: definition, every relation in both directions (in the ontology's words; a link the source does not state reads «(inferred: why)»), material in full (lists, thresholds, steps), sources, whether it is STALE (its source changed), the competency questions it leaves unanswered."
    :inputSchema {:type "object" :properties {:id {:type "string"}} :required ["id"]}
    ;; an agent reads the material whole: lists, thresholds and steps live there
    :run (fn [kg {:keys [id]}] (gt/open-node {:kg kg :material-chars 4000} id))}
   {:name "list-edges"
    :description "Relations as «from —reads as→ to» lines: all of one relation (e.g. every module → store), and/or every relation touching one node. Use this instead of opening nodes one by one."
    :inputSchema {:type "object" :properties {:relation {:type "string" :description "relation id (from describe-ontology)"}
                                              :node {:type "string" :description "node id"}}}
    :run (fn [kg {:keys [relation node]}] (gt/list-edges kg (some-> relation not-empty keyword) (not-empty node)))}
   {:name "check-cqs"
    :description "The competency questions and what the graph does NOT answer yet: per question how many nodes were asked, how many answer, which are silent; relations no question needs. Computed from the graph, never a model's opinion."
    :inputSchema {:type "object" :properties {}}
    :run (fn [kg _] (gt/check-cqs kg))}
   ;; only with --sources: a tool that cannot act is not offered
   {:name "read-source" :sources? true
    :description "The passage of the source document a node was written from: around the node's quote, or around `query` (words to find in that document). Use it when the node's material lacks a detail — dates, amounts, systems, exceptions — the document has it."
    :inputSchema {:type "object" :properties {:id {:type "string" :description "node id"}
                                              :query {:type "string" :description "optional words to find in the node's source"}
                                              :chars {:type "number" :description "how much text (default 3000, up to 12000)"}}
                  :required ["id"]}
    :run (fn [kg {:keys [id query chars]}] (gt/read-source kg (:texts @state) id (not-empty query) chars))}
   {:name "search-sources"
    :sources? true
    :description "Search the full text of the graph's source documents (word endings loosely matched): the best passages, each with the nodes written from it. For details the nodes do not carry."
    :inputSchema {:type "object" :properties {:query {:type "string"}} :required ["query"]}
    :run (fn [kg {:keys [query]}] (gt/search-sources kg (:texts @state) query))}])

(defn- offered
  "The tools this server offers: the source tools only with --sources."
  []
  (filter #(or (not (:sources? %)) (:sources @state)) tools))

(defn- result-text [text & [error?]]
  (cond-> {:content [{:type "text" :text (str text)}]} error? (assoc :isError true)))

(defn- call-tool [{:keys [name arguments]}]
  (if-let [tool (first (filter #(= name (:name %)) (offered)))]
    (let [kg (load-log!)
          {:keys [bad]} @state]
      (cond
        (nil? kg) (result-text (or bad "no graph loaded") true)
        :else (try (cond-> (result-text ((:run tool) kg (or arguments {})))
                     bad (update-in [:content 0 :text] #(str "(note: the file on disk is " bad "; answering from the last good read)\n" %)))
                   (catch :default e (result-text (str "tool failed: " (ex-message e)) true)))))
    (result-text (str "unknown tool " name) true)))

(defn handle
  "One JSON-RPC request (as a clj map) → response map, or nil for a
   notification."
  [{:keys [id method params]}]
  (let [reply (fn [result] {:jsonrpc "2.0" :id id :result result})
        fail (fn [code msg] {:jsonrpc "2.0" :id id :error {:code code :message msg}})]
    (cond
      (nil? id) nil                                   ; notifications/initialized and friends
      (= method "initialize")
      (reply {:protocolVersion protocol-version
              :capabilities {:tools {}}
              :serverInfo {:name "loretile" :version "0.1"}
              :instructions (str "A curated knowledge graph: every node was accepted by a person; relations are typed by the graph's ontology. "
                                 "Start with describe-ontology, then search-nodes / open-node. A node marked STALE was written from a source that has since changed; a link marked «inferred» is the model's conclusion, not a quote — say so when you rely on it."
                                 (when (:sources @state)
                                   " The source documents are here too: when a node lacks a detail, read-source opens the passage it was written from, and search-sources searches the documents themselves."))})
      (= method "ping") (reply {})
      (= method "tools/list")
      (reply {:tools (mapv #(select-keys % [:name :description :inputSchema]) (offered))})
      (= method "tools/call")
      (reply (call-tool params))
      :else (fail -32601 (str "method not found: " method)))))

(defn- send! [m]
  (.write js/process.stdout (str (js/JSON.stringify (clj->js m)) "\n")))

(defn- on-line [line]
  (when-not (str/blank? line)
    (let [req (try (js->clj (js/JSON.parse line) :keywordize-keys true) (catch :default _ nil))]
      (if (nil? req)
        (send! {:jsonrpc "2.0" :id nil :error {:code -32700 :message "parse error"}})
        (when-let [res (handle req)] (send! res))))))

(defn main [& args]
  (let [path (first (remove #(str/starts-with? % "--") (remove (set (map second (filter #(= "--sources" (first %)) (partition 2 1 args)))) args)))
        sources (second (first (filter #(= "--sources" (first %)) (partition 2 1 args))))]
    (when sources (swap! state assoc :sources (.resolve node-path sources)))
    (when-not path
      (.write js/process.stderr "usage: node out/mcp.js /path/to/log.edn [--sources /folder/of/documents]\n")
      (js/process.exit 2))
    (swap! state assoc :path path)
    (when (nil? (load-log!))
      (.write js/process.stderr (str "loretile mcp: " (or (:bad @state) (str "cannot read " path)) "\n")))
    (let [buf (atom "")]
      (.setEncoding js/process.stdin "utf8")
      (.on js/process.stdin "data"
           (fn [chunk]
             (swap! buf str chunk)
             (let [parts (str/split @buf #"\n" -1)]
               (reset! buf (last parts))
               (doseq [l (butlast parts)] (on-line l)))))
      (.on js/process.stdin "end" (fn [] (js/process.exit 0))))))
