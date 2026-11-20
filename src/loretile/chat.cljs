(ns loretile.chat
  "M0.3 intake chat — the assistant door (wiki/Authoring.md).
   Dump text → LLM proposes concepts/edges/objects/questions as CARDS →
   author accepts/edits/discards → same events as the manual door.
   The LLM returns strict JSON ops; cards render from data — the model
   never touches the fold directly."
  (:require [clojure.string :as str]
            [promesa.core :as p]
            [reagent.core :as r]
            [loretile.fold :as fold]
            [loretile.icons :as icons]
            [loretile.llm :as llm]
            [loretile.profile :as profile]
            [loretile.blobs :as blobs]
            [loretile.exercise :as ex]
            [loretile.session :as session]
            [loretile.tour :as tour]
            [loretile.store :as store]
            [loretile.sync :as sync]
            [loretile.tools :as tools]
            [loretile.vectors :as vectors]
            [loretile.i18n :as i18n :refer [t tn]]))

(defonce msgs (r/atom []))        ; {:who :user|:llm|:sys :text}
(defonce cards (r/atom []))       ; proposal cards awaiting decision
(defonce busy (r/atom false))
(defonce doc (r/atom nil))        ; attached document {:name :pages :text}
;; the graph this sitting's cards belong to — accepting them into another
;; graph attached drafts to topics that do not exist there
(defonce graph (r/atom nil))
(defonce focus (r/atom nil))     ; {:kind :topic|:course :id} last in the author's view
;; another graph's topic is not «this topic»
(defonce ^:private focus-clear
  (add-watch store/current ::focus (fn [_ _ o n] (when (not= o n) (reset! focus nil)))))
(defonce decided (r/atom 0))      ; cards resolved since the pile started: the progress
(defonce progress (r/atom nil))   ; {:i :n :from :to :step} while a document is read
(defonce ^:private stop? (atom false))
;; kintale's chat-panel window chrome: :min (title strip) | :normal
;; (right column) | :max (the whole width, map hidden)
(defonce size (r/atom :normal))

;; --- sessions (kintale.session) ---------------------------------------------
;; the sitting autosaves, debounced, whenever any of its three atoms move;
;; restoring sets a flag so the restore itself is not written back

(defonce ^:private restoring? (atom false))
(defonce ^:private save-timer (atom nil))

(defonce onto (r/atom nil))       ; the ontology interview (wiki/Profiles.md §CQ): {:graph :asks :story :accepted}

(defn- slice [] {:msgs @msgs :cards @cards :doc (some-> @doc (dissoc :file)) :graph @graph :decided @decided :onto @onto})

(defn- schedule-save! []
  (when-not @restoring?
    (some-> @save-timer js/clearTimeout)
    (reset! save-timer (js/setTimeout #(session/save-current! (slice)) 700))))

(defonce ^:private autosave
  (doseq [a [msgs cards doc graph decided onto]] (add-watch a ::autosave (fn [_ _ _ _] (schedule-save!)))))

(defn- flush-save!
  "Shelving reads STORAGE, so a sitting whose debounced save has not fired
   yet would be shelved as its previous state — write it now."
  []
  (some-> @save-timer js/clearTimeout)
  (reset! save-timer nil)
  (when (seq @msgs) (session/save-current! (slice))))

(defn- load-sitting! [s]
  (reset! restoring? true)
  (reset! msgs (vec (:msgs s))) (reset! cards (vec (:cards s))) (reset! doc (:doc s))
  (reset! graph (:graph s)) (reset! decided (or (:decided s) 0)) (reset! onto (:onto s))
  (reset! restoring? false))

(defn restore!
  "Put the last sitting back exactly as it was left. Once, at boot."
  []
  (when-let [cur (:current (session/load!))]
    (when (seq (:msgs cur)) (load-sitting! cur))))

(defn- refuse-busy!
  "A sitting is not swapped while a call runs in it: the running intake
   kept adding its cards to whichever sitting was open by then — a shelved
   sitting of another graph, accepted there (review 2026-10-16 A10)."
  []
  (swap! msgs conj {:who :sys :text (t :chat/busy-wait)})
  nil)

(defn new-conversation!
  "The sitting goes to the shelf, the panel starts empty. Nothing is
   deleted. true when done; refused while a call runs."
  []
  (if @busy
    (refuse-busy!)
    (do (flush-save!)
        (session/archive-current!)
        (load-sitting! nil)
        true)))

(defn open-past!
  "Palette → a shelved sitting becomes the open one; the open one is shelved."
  [{:keys [at current?]}]
  (cond
    current? nil
    @busy (refuse-busy!)
    :else (do (flush-save!)
              (when-let [pick (session/open-archived! at)] (load-sitting! pick)))))

(def past-sessions session/past)

(defn open-proposal!
  "A ghost on the map → the sitting whose card proposed it, shelved or not."
  [id]
  (let [mine? (fn [cs] (some #(and (= :concept (:card %)) (= id (get-in % [:data :id]))) cs))]
    (when-not (mine? @cards)
      (when-let [s (session/find-archived #(mine? (:cards %)))]
        (open-past! {:at (:at s)})))))

(defn foreign?
  "Are the open cards from a graph other than the open one?"
  []
  (boolean (and @graph (seq @cards) (not (store/append-to? @graph)))))

;; --- pdf --------------------------------------------------------------------
;; pdf.js lives as committed assets in public/js (offline, deterministic),
;; loaded lazily via native dynamic import — the bundle never carries it.

;; the whole document is capped (cost), then read in parts of chunk-chars:
;; one reply per part stays well under a provider's output limit, and the
;; author watches a progress bar instead of a spinner
(def ^:private max-doc-chars 100000)
(def ^:private chunk-chars 20000)
;; a part shorter than this is not split again when its reply is cut off
(def ^:private min-split-chars 3000)

(defonce ^:private pdfjs (atom nil))

(defn- asset-url [rel]
  ;; relative to the DOCUMENT, not the site root: the app lives at /app/
  ;; in production and at / under the dev server
  (.-href (js/URL. rel (.-baseURI js/document))))

(defn- load-pdfjs! []
  (or @pdfjs
      (reset! pdfjs
              (-> ((js/Function "u" "return import(u)") (asset-url "js/pdf.min.mjs"))
                  (p/then (fn [^js lib]
                            (set! (.. lib -GlobalWorkerOptions -workerSrc)
                                  (asset-url "js/pdf.worker.min.mjs"))
                            lib))))))

(defn- page-text [^js pdf n]
  (-> (.getPage pdf n)
      (p/then (fn [^js page] (.getTextContent page)))
      (p/then (fn [^js tc] (->> (.-items tc) (map (fn [^js it] (.-str it))) (str/join " "))))))

(defn extract-pdf!
  ;; i18n-ignore
  "File → promise of {:name :pages :text}, text page-marked with [стр. N]."
  [^js file]
  (p/let [^js lib (load-pdfjs!)
          buf (.arrayBuffer file)
          ^js pdf (.-promise (.getDocument lib #js {:data buf}))
          texts (p/all (map #(page-text pdf %) (range 1 (inc (.-numPages pdf)))))]
    {:name (.-name file) :pages (.-numPages pdf)
     ;; i18n-ignore — the [стр. N] convention the model is told about
     :text (str/join "\n" (map-indexed (fn [i t] (str "[стр. " (inc i) "] " t)) texts))}))

(defn- content-id
  "src-<12 hex of sha-256 over the text>: the same document attached twice
   is one source (wiki/Authoring.md file layout: sources/{content-hash})."
  [text]
  (p/let [buf (js/crypto.subtle.digest "SHA-256" (.encode (js/TextEncoder.) text))]
    (str "src-" (->> (js/Uint8Array. buf) (take 6)
                     (map #(.padStart (.toString % 16) 2 "0")) (apply str)))))

;; --- a new version of a document already in the graph (decided 2026-10-02, #7)
;; Sources are content-addressed, so a new version is a new source; the
;; material written so far stays grounded in the old one. The chat asks
;; whether the upload IS a new version, then asks per topic: patch or
;; material? — a card each, no diff engine.

(defn- name-key
  "A document name without its extension, version marks and punctuation:
   «Handbook v2.pdf», «handbook (2).md» and «Handbook_2026-09.pdf» meet."
  [s]
  (-> (str/lower-case (str s))
      (str/replace #"\.[a-z0-9]{1,5}$" "")
      ;; version words a file name may carry, anchored on separators: without
      ;; the u flag \b does not see Cyrillic letters. i18n-ignore (not UI copy):
      (str/replace #"(^|[\s_(\-])(v|ver|version|rev|fassung|entwurf|ред|изд)\.?\s*\d+" " ")
      ;; marks without a number: «Handbuch final», «Handbuch neu», «Entwurf»
      (str/replace #"(^|[\s_(\-])(final|neu|entwurf)(?=$|[\s_)\-.])" " ")
      (str/replace #"\(\d+\)|\d{4}[-._]\d{2}([-._]\d{2})?|[_\-.]\d+$" " ")
      (str/replace #"[\s_\-.,()\[\]]+" " ")
      str/trim))

(defn- one-word-more?
  "Is `longer` `shorter` plus ONE more word («Handbook» → «Handbook
   updated»)? A longer tail names another document: «procurement» →
   «procurement tips for submitting a zip request» asked «a new version?»
   on every page of a handbook section (corpus test 2026-10-21)."
  [longer shorter]
  (and (str/starts-with? longer (str shorter " "))
       (let [tail (str/trim (subs longer (count shorter)))]
         (and (seq tail) (not (str/includes? tail " "))))))

(defn- version-candidates
  "Sources the new upload could be a new version of, best first."
  [kg new-id new-name]
  (let [k (name-key new-name)]
    (->> (:sources kg)
         (keep (fn [[id src]]
                 (let [k2 (name-key (:title src))]
                   (when (and (not= id new-id) (>= (min (count k) (count k2)) 4)
                              (or (= k k2) (one-word-more? k k2) (one-word-more? k2 k)))
                     [id (:title src) (if (= k k2) 0 1)]))))
         (sort-by #(nth % 2)) (mapv (fn [[id title]] [id title])))))

(defn grounded-topics
  "Topics with material written from this source: their own definition
   (:source-id) or an active object on them (:grounded-in)."
  [kg src]
  (->> (concat (keep (fn [[id c]] (when (= src (:source-id c)) id)) (:concepts kg))
               (keep #(when (and (= src (:grounded-in %)) (= :active (:status %))) (:concept-id %)) (vals (:objects kg))))
       (filter #(get-in kg [:concepts %])) distinct sort vec))

(defn- refuse-claim!
  "The sitting holds undecided cards from another graph: taking it over for
   the open graph would make those cards acceptable here."
  []
  (swap! msgs conj {:who :sys :text (t :chat/claim-refused {"{title}" (store/graph-title @graph)})})
  nil)

(defn attach-doc! [{:keys [text pages ^js file] :as d}]
  (p/let [src-id (content-id text)]
    (if (foreign?)
      (refuse-claim!)
    (let [cut? (> (count text) max-doc-chars)
          ext (or (second (re-find #"\.([A-Za-z0-9]+)$" (:name d))) "bin")
          ref (str src-id "." (.toLowerCase ext))]
      ;; every upload is a source node — provenance is free (Authoring step 1);
      ;; the bytes and the extraction go to the blob store under the hash
      (blobs/put! ref file)
      (blobs/put! (str src-id ".md") text)
      (when-not (get-in (store/kg) [:sources src-id])
        ;; a NEW source whose name matches one already here: ask before anything
        (when-let [cands (not-empty (version-candidates (store/kg) src-id (:name d)))]
          (when (empty? @cards) (reset! decided 0))
          (reset! graph @store/current)
          ;; to the FRONT: the pile shows five at a time, and this question
          ;; decides what the rest of the upload means
          (swap! cards #(into [{:card :version-of
                                :data {:id (str "v-" src-id) :new src-id :new-title (:name d) :candidates cands}}]
                              %)))
        (store/append! {:ev/type :source-added :source {:id src-id :title (:name d) :ref ref}}))
      ;; the browser File stays out: it went to the blob store above, and in
      ;; the saved sitting it printed as #object[File …], which nothing can
      ;; read back — the next save then wiped every shelved sitting
      ;; (review 2026-10-16 A2)
      (reset! doc (cond-> (-> d (dissoc :file) (assoc :source-id src-id))
                    cut? (assoc :text (subs text 0 max-doc-chars))))
      (swap! msgs conj {:who :sys
                        :text (str (t :chat/doc-read {"{name}" (:name d)})
                                   (when pages (t :chat/doc-pages {"{n}" pages}))
                                   (t :chat/doc-chars {"{n}" (count text)})
                                   (when cut? (t :chat/doc-truncated {"{n}" max-doc-chars})))})))))

(def accept-types
  "What the file picker offers — the formats `read-file` can turn into text."
  ".md,.markdown,.txt,.pdf,.docx,.html,.htm,.ttl,.edn")

(defn- zip-entry
  "One file out of a zip archive (a .docx is one), with the browser's own
   DecompressionStream — no library. Stored (0) and deflate (8) entries,
   which is what Word, LibreOffice and Google Docs write. Promise of the
   entry's text, or nil when the archive has no such entry."
  [^js buf want]
  (let [dv (js/DataView. buf) n (.-byteLength buf) td (js/TextDecoder.)
        ;; the end-of-central-directory record sits in the last 64 KB
        eocd (loop [i (- n 22)]
               (cond (or (< i 0) (< i (- n 65558))) nil
                     (= (.getUint32 dv i true) 0x06054b50) i
                     :else (recur (dec i))))]
    (if-not eocd
      (p/resolved nil)
      (let [cnt (.getUint16 dv (+ eocd 10) true)]
        (loop [k 0 at (.getUint32 dv (+ eocd 16) true)]
          (if (>= k cnt)
            (p/resolved nil)
            (let [method (.getUint16 dv (+ at 10) true)
                  csize (.getUint32 dv (+ at 20) true)
                  nlen (.getUint16 dv (+ at 28) true)
                  xlen (.getUint16 dv (+ at 30) true)
                  clen (.getUint16 dv (+ at 32) true)
                  off (.getUint32 dv (+ at 42) true)
                  name (.decode td (js/Uint8Array. buf (+ at 46) nlen))]
              (if (not= name want)
                (recur (inc k) (+ at 46 nlen xlen clen))
                (let [start (+ off 30 (.getUint16 dv (+ off 26) true) (.getUint16 dv (+ off 28) true))
                      data (js/Uint8Array. buf start csize)]
                  (case method
                    0 (p/resolved (.decode td data))
                    8 (.text (js/Response. (.pipeThrough (.-body (js/Response. data))
                                                         (js/DecompressionStream. "deflate-raw"))))
                    (p/resolved nil)))))))))))

(def ^:private W-ns "http://schemas.openxmlformats.org/wordprocessingml/2006/main")

(defn- docx-heading-levels
  "word/styles.xml → {styleId level}: a heading by its style NAME («heading 1»
   — the name is English in every Word; the id is not: Russian Word writes
   «1», «a3»…) or by its outline level. {} without a styles part."
  [styles-xml]
  (if (str/blank? styles-xml)
    {}
    (let [doc (.parseFromString (js/DOMParser.) styles-xml "application/xml")
          one (fn [el tag] (some-> (first (array-seq (.getElementsByTagNameNS el W-ns tag))) (.getAttributeNS W-ns "val")))]
      (into {} (keep (fn [st]
                       (let [lvl (or (some->> (one st "name") (re-find #"(?i)^heading\s*(\d)") second js/parseInt)
                                     (some-> (one st "outlineLvl") js/parseInt inc))]
                         (when (and lvl (<= 1 lvl 6)) [(.getAttributeNS st W-ns "styleId") lvl])))
                     (array-seq (.getElementsByTagNameNS doc W-ns "style")))))))

(defn docx-text
  "word/document.xml → plain text: one paragraph per w:p, headings as
   markdown marks so topics keep their outline (by style name from
   styles.xml, the paragraph's outline level, or a Heading1-like style id);
   table cells are paragraphs too; a tab is a space, a line break a newline
   (both glued words together)."
  ([xml] (docx-text xml nil))
  ([xml styles-xml]
   (let [doc (.parseFromString (js/DOMParser.) xml "application/xml")
         levels (docx-heading-levels styles-xml)
         all (fn [el tag] (array-seq (.getElementsByTagNameNS el W-ns tag)))
         piece (fn [el] (when (= "r" (some-> (.-parentNode el) .-localName))
                          (case (.-localName el) "t" (.-textContent el) "tab" " " "br" "\n" nil)))]
     (->> (all doc "p")
          (keep (fn [p]
                  (let [txt (str/trim (apply str (keep piece (all p "*"))))
                        style (some-> (first (all p "pStyle")) (.getAttributeNS W-ns "val"))
                        own (some-> (first (all p "outlineLvl")) (.getAttributeNS W-ns "val") js/parseInt inc)
                        ;; i18n-ignore — Word style ids, not UI text (Heading1 · Заголовок1 · berschrift1)
                        by-id (some->> style (re-find #"(?i)^(?:heading|заголовок|berschrift)\s*(\d)") second js/parseInt)
                        lvl (or (get levels style) own by-id)]
                    (when-not (str/blank? txt)
                      (if lvl (str (apply str (repeat lvl "#")) " " txt) txt)))))
          (str/join "\n\n")))))

(defn html-text
  "An HTML page → plain text: scripts, styles, navigation, forms and
   footers dropped; headings as markdown marks; every block its own
   paragraph."
  [html]
  (let [doc (.parseFromString (js/DOMParser.) html "text/html")
        q (fn [sel] (array-seq (.querySelectorAll doc sel)))]
    (doseq [el (q "script,style,noscript,template,svg,iframe,nav,footer,aside,form,button")] (.remove el))
    (doseq [h (q "h1,h2,h3,h4,h5,h6")]
      (set! (.-textContent h) (str (apply str (repeat (js/parseInt (subs (.-tagName h) 1)) "#")) " "
                                   (str/trim (.-textContent h)))))
    (doseq [br (q "br")] (.replaceWith br "\n"))
    (doseq [el (q "p,div,li,h1,h2,h3,h4,h5,h6,pre,blockquote,section,article,tr,dt,dd,figcaption,table,header,main")]
      (.append el "\n\n"))
    (doseq [c (q "td,th")] (.append c " "))
    (->> (str/split-lines (or (some-> (.-body doc) .-textContent) ""))
         (map #(str/trim (str/replace % #"[ \t\u00a0]+" " ")))
         (str/join "\n")
         (#(str/replace % #"\n{3,}" "\n\n"))
         str/trim)))

(defn read-file
  "File → promise of {:name :text :pages}. Only text the reader can use:
   md/txt as text, pdf through pdf.js, docx and html by the browser itself
   (2026-10-03); anything else (an old .doc, slides, images) rejects."
  [^js file]
  (let [n (.-name file)]
    (cond
      (re-find #"(?i)\.pdf$" n) (extract-pdf! file)
      (re-find #"(?i)\.(md|markdown|txt|ttl|edn)$" n) (p/then (.text file) (fn [s] {:name n :text s}))
      (re-find #"(?i)\.docx$" n)
      (p/let [buf (.arrayBuffer file)
              xml (zip-entry buf "word/document.xml")
              styles (zip-entry buf "word/styles.xml")]
        (if xml {:name n :text (docx-text xml styles)} (p/rejected (ex-info (t :chat/docx-broken) {}))))
      (re-find #"(?i)\.(html?|xhtml)$" n) (p/then (.text file) (fn [s] {:name n :text (html-text s)}))
      :else (p/rejected (ex-info (t :chat/file-unsupported) {})))))

(defn blank-doc?
  "No words in it — whitespace, or a pdf that is only page marks (a scan)."
  [{:keys [text]}]
  (str/blank? (str/replace (str text) #"\[\S+ \d+\]" "")))

(defn attach-file! [^js file]
  (cond
    @busy (swap! msgs conj {:who :sys :text (t :chat/busy-wait)})
    (foreign?) (refuse-claim!)
    :else
    (do (reset! busy true)
        (-> (read-file file)
            (p/then #(attach-doc! (assoc % :file file)))
            (p/catch (fn [e] (swap! msgs conj {:who :sys :text (str (t :chat/file-read-error)
                                                                    (ex-message e))})))
            (p/finally #(reset! busy false))))))

;; --- prompt -----------------------------------------------------------------

;; Two-step intake (2026-09-04): step 1 proposes concepts/objects/questions;
;; step 2 judges ENGINE-PROPOSED pairs (order of mention, definition
;; mentions) one by one — free-form edge drafting over a whole document
;; was the weak link; a yes/no with the evidence in front of it is not.
;; The two prompt constants below are written as (str "line" "\n" ...)
;; instead of one raw multi-line literal purely so each Cyrillic line can
;; carry its own i18n-ignore marker; the resulting string is identical.
(def ops-instruction-learning
  (str
   ;; i18n-ignore
   "Ты помощник куратора базы знаний. Разбей присланный материал на маленькие" "\n"
   ;; i18n-ignore
   "учебные концепты (одна проверяемая идея, один повод ошибиться) и черновики" "\n"
   ;; i18n-ignore
   "учебных объектов. Связи между темами НЕ предлагай — их спросят отдельно." "\n"
   ;; i18n-ignore
   "Отвечай ТОЛЬКО валидным JSON без markdown:" "\n"
   ;; i18n-ignore
   "{\"concepts\":[{\"id\":\"slug\",\"title\":\"…\",\"definition\":\"…\",\"evidence\":\"короткая точная цитата-основание из материала, с [стр. N] если есть\"}]," "\n"
   " \"objects\":[{\"concept\":\"slug\",\"kind\":\"explanation\",\"level\":\"novice|apprentice|professional\",\"text\":\"…\"}]," "\n"
   ;; i18n-ignore
   " \"questions\":[{\"concept\":\"slug\",\"question\":\"что уточнить у эксперта\"}]," "\n"
   ;; i18n-ignore
   " \"note\":\"короткий комментарий куратору\"}" "\n"
   ;; i18n-ignore
   "Не выдумывай фактов — только из материала; чего не хватает, спрашивай через questions." "\n"
   ;; i18n-ignore
   "Если пользователь не присылает материал, а СПРАШИВАЕТ — о базе, курсах, пробелах ученика, — ответь в note, concepts оставь пустыми." "\n"
   ;; reader marks (Ideas 2026-10-02): one essence per draft, examples optional
   ;; i18n-ignore
   "В тексте каждого черновика объекта отметь ровно ОДНО главное предложение как ==так==, а пример, если он есть, как ++так++."))

(def edges-instruction-learning
  (str
   ;; i18n-ignore
   "Ты помощник куратора базы знаний. Для КАЖДОЙ пары тем ниже определи связь," "\n"
   ;; i18n-ignore
   "опираясь на цитаты и подсказки движка (порядок упоминания, термин в определении):" "\n"
   ;; i18n-ignore
   "requires — from нельзя понять без to (подсказка предлагает направление; если оно" "\n"
   ;; i18n-ignore
   "неверно — поменяй местами); is-confused-with — темы путают; none — связи нет." "\n"
   ;; i18n-ignore
   "Отвечай ТОЛЬКО валидным JSON без markdown:" "\n"
   ;; i18n-ignore
   "{\"edges\":[{\"from\":\"slug\",\"to\":\"slug\",\"type\":\"requires|is-confused-with\",\"why\":\"одна строка\"}]}" "\n"
   ;; i18n-ignore
   "Пары с type none не включай."))

(defn- ops-instruction
  "Step 1 in the graph's vocabulary: the learning text, or — for a custom
   ontology (wiki/Profiles.md) — the profile's node types, a `type` on
   every node, prose instead of learning objects, no levels."
  []
  (let [p (store/profile)]
    (if (profile/learning? p)
      ops-instruction-learning
      (str
       ;; i18n-ignore
       "Ты помощник куратора базы знаний. Онтология этой базы: " (:title p)
       (when (seq (:brief p)) (str ". " (:brief p))) "\n"
       ;; i18n-ignore
       "Типы узлов (поле type — ровно один из них):\n" (profile/node-type-lines p) "\n"
       ;; i18n-ignore
       "Выдели из присланного материала узлы этих типов (один узел — одна сущность, одно короткое определение)." "\n"
       ;; i18n-ignore
       "Связи между узлами НЕ предлагай — их спросят отдельно. Отвечай ТОЛЬКО валидным JSON без markdown:" "\n"
       ;; i18n-ignore
       "{\"concepts\":[{\"id\":\"slug\",\"type\":\"" (name (:id (first (profile/node-types p)))) "\",\"title\":\"…\",\"definition\":\"…\",\"evidence\":\"короткая точная цитата-основание из материала, с [стр. N] если есть\"}]," "\n"
       ;; i18n-ignore
       " \"objects\":[{\"concept\":\"slug\",\"kind\":\"explanation\",\"text\":\"подробности узла, если материал даёт больше, чем определение: списки, пороги, сроки, шаги — полностью, каждый пункт, а не пересказом\"}]," "\n"
       ;; i18n-ignore
       " \"questions\":[{\"concept\":\"slug\",\"question\":\"что уточнить у владельца процесса\"}]," "\n"
       ;; i18n-ignore
       " \"note\":\"короткий комментарий куратору\"}" "\n"
       ;; i18n-ignore
       "Не выдумывай фактов — только из материала; чего не хватает, спрашивай через questions." "\n"
       ;; the ontology graph is on this device: a missing kind can be ADDED to
       ;; it (wiki/Ontology-Graph.md slice 4) — better than a wrong type
       (when (some-> (store/kg) :ontology :graph store/graph-on-device?)
         ;; i18n-ignore
         (str "Если сущность не подходит НИ К ОДНОМУ из типов — не подгоняй: дай ей новый type (slug) и \"type_label\" — как называется этот вид;"
              ;; i18n-ignore
              " его предложат добавить в онтологию." "\n"))
       ;; i18n-ignore
       "Если пользователь не присылает материал, а СПРАШИВАЕТ о базе — ответь в note, concepts оставь пустыми."))))

(defn- edges-instruction
  "Step 2 in the graph's vocabulary: the model picks a relation of the
   profile for each engine-proposed pair (or none); the ordering relation
   is explained like `requires` is for learning."
  []
  (let [p (store/profile)]
    (if (profile/learning? p)
      edges-instruction-learning
      (str
       ;; i18n-ignore
       "Ты помощник куратора базы знаний. Онтология: " (:title p) ". Для КАЖДОЙ пары узлов ниже определи связь" "\n"
       ;; i18n-ignore
       "из списка (в скобках — какие типы узлов она соединяет; [ordering] — отношение порядка: from идёт ПОСЛЕ to," "\n"
       ;; i18n-ignore
       "подсказка движка предлагает направление; если оно неверно — поменяй местами), или none — связи нет:" "\n"
       (profile/relation-lines p) "\n"
       ;; i18n-ignore
       "Отвечай ТОЛЬКО валидным JSON без markdown:" "\n"
       ;; i18n-ignore
       "{\"edges\":[{\"from\":\"slug\",\"to\":\"slug\",\"type\":\"id связи\",\"why\":\"одна строка\"}]}" "\n"
       ;; i18n-ignore
       "Пары с type none не включай."
       ;; the ontology graph is on this device: a missing relation can be
       ;; ADDED to it (wiki/Ontology-Graph.md slice 4)
       (when (some-> (store/kg) :ontology :graph store/graph-on-device?)
         ;; i18n-ignore
         (str "\nЕсли материал явно называет связь, которой НЕТ в списке, — не подгоняй: дай новый type (slug),"
              ;; i18n-ignore
              " \"label\" (как читается) и \"inverse\" (как читается обратно); её предложат добавить в онтологию."))))))

(defn- default-relation
  "What an edge without a type means: `requires`, or the ordering
   relation of a custom ontology."
  []
  (name (profile/ordering-relation (store/profile))))

(def ^:private pair-cap 40)   ; pairs per judging call — its reply stays under 4096 tokens
(def ^:private pair-max 160)  ; pairs per part, at most

(defn signal-text
  "A pair-signal, in the author's language — what the edge card shows."
  [{:keys [sig title p1 p2 inner outer n1 n2]}]
  (case sig
    :earlier (t :chat/sig-earlier {"{title}" title "{p1}" p1 "{p2}" p2})
    :in-def (t :chat/sig-in-def {"{inner}" inner "{outer}" outer})
    :step-after (t :chat/sig-step {"{title}" title "{n1}" n1 "{n2}" n2})
    (str sig)))

(defn- signal-ru
  "The same signal for the MODEL: prompts stay Russian."
  [{:keys [sig title p1 p2 inner outer n1 n2]}]
  (case sig
    ;; i18n-ignore
    :earlier (str "«" title "» упоминается раньше (стр. " p1 " → " p2 ")")
    ;; i18n-ignore
    :in-def (str "«" inner "» входит в определение «" outer "»")
    ;; i18n-ignore
    :step-after (str "«" title "» — шаг " n1 " того же нумерованного списка, этот — шаг " n2)
    (str sig)))

(defn- answer-language
  "One line appended to every intake instruction: user-facing commentary
   fields follow the UI language."
  []
  ;; i18n-ignore
  (str "\nПоля note и why пиши на языке пользователя: " (name @i18n/locale) "."))

(defn- content-language
  "What learners will read is written in the GRAPH's language, whatever the
   material's (decided 2026-10-02, Decisions #12); evidence stays a verbatim
   quote, so it stays in the material's language."
  []
  ;; i18n-ignore
  (str "\nНазвания, определения, черновики объектов и вопросы пиши на языке: " (store/locale-for-new)
       ;; i18n-ignore
       ". Цитаты evidence приводи дословно, на языке материала."))

(defn- pairs-block [kg cands]
  (let [c* (fn [id] (or (get-in kg [:concepts id]) (get-in kg [:proposals id]) {:id id}))
        line (fn [{:keys [a b from to why fits]}]
               (let [ca (c* a) cb (c* b)]
                 (str "- " a " («" (:title ca) "»: " (:definition ca)
                      (when (seq (:evidence ca)) (str " — " (:evidence ca))) ")\n"
                      "  " b " («" (:title cb) "»: " (:definition cb)
                      (when (seq (:evidence cb)) (str " — " (:evidence cb))) ")\n"
                      ;; a custom ontology names the relations the TYPES allow
                      ;; (`fits`); learning keeps its one relation
                      (let [rel (if (seq fits)
                                  ;; i18n-ignore
                                  (str/join " или " (map name fits))
                                  (default-relation))]
                        (cond
                          ;; an OPEN pair (fits = []: no relation joins these types,
                          ;; asked because the material links them) — naming the
                          ;; default relation here proposed «role requires indicator»
                          ;; (real-model run 2026-11-20)
                          (and (some? fits) (empty? fits))
                          ;; i18n-ignore
                          (str "  подсказка: " (str/join "; " (map signal-ru why))
                               ;; i18n-ignore
                               " → ни одна связь из списка эти типы не соединяет: если материал называет связь — новый type с label и inverse, иначе none")
                          (seq why)
                          ;; i18n-ignore
                          (str "  подсказка: " (str/join "; " (map signal-ru why)) " → вероятно " from " " rel " " to)
                          (seq fits)
                          ;; i18n-ignore
                          (str "  подсказка: сигналов нет; по типам возможно " from " " rel " " to)
                          ;; i18n-ignore
                          :else "  подсказка: нет")))))]
    ;; i18n-ignore
    (str "Пары тем (" (count cands) "):\n" (str/join "\n" (map line cands)))))

(defn- pair-budget
  "How many pairs a part's topics get judged: `pair-cap` per 25 topics, at
   most `pair-max`. A long part (16k replies, 40–60 topics) asked about only
   40 pairs left a 127-node graph with 59 links (business dogfood
   2026-10-15)."
  [n-topics]
  (min pair-max (* pair-cap (max 1 (quot (+ n-topics 24) 25)))))

(declare parse-reply)

(defn- judge-pairs!
  "One model call over at most `pair-cap` pairs → promise of the edges it
   kept (only pairs asked about, each carrying the engine's reasons). A
   reply that cannot be read costs this batch only."
  [kg cands]
  (-> (p/let [raw (llm/chat! (str (edges-instruction) (answer-language) "\n\n" (pairs-block kg cands))
                             ;; i18n-ignore
                             "Оцени каждую пару.")]
        (let [ops (parse-reply raw)
              by-pair (into {} (map (fn [c] [#{(:a c) (:b c)} c]) cands))]
          (vec (for [e (:edges ops)
                     :let [c (get by-pair #{(:from e) (:to e)})]
                     :when c]
                 (assoc e :signals (mapv signal-text (:why c)))))))
      ;; one batch that fails — unreadable, a 429, a network error — costs
      ;; its pairs only, never the part's other batches (review 2026-10-16
      ;; A20); a budget refusal still stops the run
      (p/catch (fn [e] (if (:budget (ex-data e)) (p/rejected e) [])))))

(declare add-relation-drafts!)   ; slice 4, with the type drafts below

(defn- propose-edges!
  "Step 2 of the intake: engine candidates among the just-proposed concepts
   (and existing ones they mention) → model verdicts, in batches of
   `pair-cap` pairs judged side by side → edge cards, each carrying the
   engine's reasons. Resolves when cards are in. `home` = the graph the
   intake started in: the judged pairs land only there (the author may have
   switched graphs during the calls — review 2026-10-16 A10)."
  [kg batch home]
  (let [cands (fold/edge-candidates kg batch (pair-budget (count batch))
                                    ;; an ontology on this device can grow: ask about
                                    ;; signalled pairs no relation fits yet (slice 4)
                                    {:open? (boolean (some-> (store/kg) :ontology :graph store/graph-on-device?))})]
    (if (empty? cands)
      (p/resolved 0)
      (p/let [found (p/all (map #(judge-pairs! kg %) (partition-all pair-cap cands)))]
        (if-not (store/append-to? home)
          (p/rejected (ex-info "wrong graph" {:wrong-graph true}))
        (let [p (store/profile)
              oid (some-> (store/kg) :ontology :graph)
              ;; a relation the ontology lacks (slice 4): when the ontology is on
              ;; this device the link waits for it instead of being refused
              new-rel? #(and oid (store/graph-on-device? oid) (seq (str (:type %)))
                             (nil? (profile/relation p (keyword (:type %)))))
              {waiting true fits false} (group-by (comp boolean new-rel?) (apply concat found))
              ok-e (filterv #(nil? (store/append!
                                    {:ev/type :edge-proposed
                                     :edge {:from (:from %) :to (:to %)
                                            :type (keyword (or (:type %) (default-relation)))
                                            :provenance :llm-drafted
                                            :justification (:why %)}}))
                            fits)]
          (reset! store/last-error nil)
          (swap! cards into (for [e ok-e] {:card :edge :data e}))
          (when (seq waiting)
            (add-relation-drafts! oid kg waiting)
            (swap! cards into (for [e waiting] {:card :edge :data e :waits-relation (str (:type e))})))
          (count ok-e)))))))

;; --- search over the concept index --------------------------------------------
;; the model never loads the whole tree (wiki/Architecture.md): a small
;; namespace goes in whole; a large one is searched — by vectors when an
;; embeddings endpoint is configured, lexically otherwise (or on any error)

(def ^:private candidate-cap 60)

(defn- candidate-ids
  "text → promise of concept ids worth showing the model."
  [kg text]
  (if (and (llm/embeddings?) (> (count (:concepts kg)) candidate-cap))
    (-> (p/let [_ (vectors/ensure! kg)
                q (vectors/query-vec text)]
          (fold/concept-candidates-by-vector kg q (vectors/vectors kg) candidate-cap))
        (p/catch (fn [_] (fold/concept-candidates kg text candidate-cap))))
    (p/resolved (fold/concept-candidates kg text candidate-cap))))

(defn existing-summary
  "Dedup context for the intake chat: what's already in the base, near this
   material. Promise of a string."
  [kg text]
  (if (empty? (:concepts kg))
    ;; i18n-ignore
    (p/resolved "База пуста.")
    (p/let [ids (candidate-ids kg text)]
      ;; i18n-ignore
      (str "Уже в базе (не дублируй, ссылайся по id; показаны " (count ids) " из "
           (count (:concepts kg)) (when (< (count ids) (count (:concepts kg)))
                                    ;; i18n-ignore
                                    "; остальные ищи инструментом search-nodes")
           "): "
           (str/join "; " (for [id (sort ids)] (str id " («" (get-in kg [:concepts id :title]) "»)")))))))

;; --- course intake: goal → target cards (wiki/Authoring.md) -----------------

(declare grow-textarea)

(defonce course-draft (r/atom {}))     ; concept-id -> level — the course form's picks
(defonce target-cards (r/atom []))     ; {:id :level :why} awaiting accept
(defonce course-msgs (r/atom []))

(def targets-instruction
  (str
   ;; i18n-ignore
   "Ты помощник куратора. По описанию курса выбери ЦЕЛИ курса из тем базы — из списка ниже" "\n"
   ;; i18n-ignore
   "или найденных инструментом search-nodes (только существующие, по id) — и предложи уровень для каждой под аудиторию. Отвечай" "\n"
   ;; i18n-ignore
   "ТОЛЬКО валидным JSON без markdown:" "\n"
   ;; i18n-ignore
   "{\"targets\":[{\"id\":\"slug\",\"level\":\"novice|apprentice|professional\",\"why\":\"одна строка\"}]," "\n"
   ;; i18n-ignore
   " \"unmatched\":[\"аспект описания, для которого в базе нет темы\"]," "\n"
   ;; i18n-ignore
   " \"note\":\"короткий комментарий\"}" "\n"
   ;; i18n-ignore
   "Не выдумывай тем: чего нет в списке — в unmatched."))

(defn- candidates-block [kg ids]
  ;; i18n-ignore
  (str "Темы базы (" (count ids) " из " (count (:concepts kg)) "):\n"
       (str/join "\n" (for [id (sort ids) :let [c (get-in kg [:concepts id])]]
                        (str id " — " (:title c) ": " (:definition c))))))

(declare parse-reply)

(def ^:private course-reads
  ;; the assistant in COURSE mode (design #5b): it may look before choosing
  ;; targets — beyond the 60 candidates on a big graph — but only reads
  #{"search-nodes" "open-node" "list-courses"})

(defn propose-targets!
  "Goal → target cards: the assistant in course mode, on the shared loop."
  [goal]
  (when (and (seq goal) (not @busy))
    (let [kg (store/kg)]
      (swap! course-msgs conj {:who :user :text goal})
      (reset! busy true)
      (-> (p/let [ids (candidate-ids kg goal)
                  ops (tools/converse!
                       {:system (str targets-instruction (tools/prompt-block {:only course-reads}) (answer-language)
                                     "\n\n" (candidates-block kg ids))
                        :user goal :parse parse-reply
                        :ctx {:kg kg :graph-id @store/current :allowed course-reads}
                        :on-call (fn [{:keys [name args]}]
                                   (swap! course-msgs conj {:who :sys :text (t :chat/tool-used {"{name}" name "{args}" (js/JSON.stringify (clj->js args))})}))
                        :on-limit #(swap! course-msgs conj {:who :sys :text (t :chat/tool-limit)})})]
            (let [known (filter #(get-in kg [:concepts (:id %)]) (:targets ops))]
              (swap! course-msgs conj {:who :llm :text (or (:note ops) (t :chat/course-targets-note))})
              (when (seq (:unmatched ops))
                (swap! course-msgs conj
                       {:who :sys :text (str (t :chat/unmatched-prefix) (str/join "; " (:unmatched ops))
                                             (t :chat/unmatched-suffix))}))
              (swap! target-cards into
                     (for [t known :when (not (contains? @course-draft (:id t)))]
                       {:id (:id t) :level (keyword (or (:level t) "apprentice")) :why (:why t)}))))
          (p/catch (fn [e] (swap! course-msgs conj {:who :sys :text (str (t :chat/error-prefix) (ex-message e))})))
          (p/finally #(reset! busy false))))))

(defn- drop-target! [t] (swap! target-cards (fn [ts] (vec (remove #(= % t) ts)))))

(defn accept-target! [t]
  (swap! course-draft assoc (:id t) (:level t))
  (drop-target! t))

(defn course-composer
  "Embedded in the course form: goal → target cards → accept into the draft."
  [kg]
  (let [goal (r/atom "")]
    (fn [kg]
      [:div.course-intake
       [:span.mode-badge.course (t :chat/mode-course)]
       [:div.msgs
        (for [[i m] (map-indexed vector @course-msgs)]
          ^{:key i} [:div {:class (str "msg msg-" (name (:who m)))} (:text m)])
        (when @busy [:div.msg.msg-sys (t :chat/finding-targets)])]
       (when (seq @target-cards)
         [:div.cards
          (for [t @target-cards]
            ^{:key (:id t)}
            [:div.card
             [:span.card-tag (str (t :chat/target-tag) " · " (i18n/enum-label "level" (:level t)))]
             [:b (get-in kg [:concepts (:id t) :title] (:id t))]
             (when (:why t) [:p.muted (:why t)])
             [:div.row
              [:button.primary {:on-click #(accept-target! t)} (t :chat/add-to-course)]
              [:button {:on-click #(drop-target! t)} (t :chat/no)]]])])
       [:div.composer
        [:div.chat-field
         [grow-textarea {:value @goal
                         :placeholder (t :chat/course-goal-placeholder)
                         :on-change #(reset! goal %)
                         :on-submit (fn [] (propose-targets! @goal) (reset! goal ""))}]
         [:button.primary.chat-sendbtn
          {:disabled (or @busy (not (llm/ready?))) :title (t :chat/pick-targets)
           :on-click (fn [_] (propose-targets! @goal) (reset! goal ""))}
          [icons/icon :send]]]]])))

;; --- ops → cards ------------------------------------------------------------

(defn ops->cards
  "Step-1 ops → cards. A topic card CARRIES its drafts and expert questions
   (ticked, accepted with the topic), so a document is one card per topic
   instead of three; drafts for topics already in the graph stay cards."
  [{:keys [concepts objects questions]}]
  (let [ids (set (map :id concepts))
        of (fn [xs id] (mapv #(assoc % :on? true) (filter #(= id (:concept %)) xs)))]
    (concat
     (for [c concepts] {:card :concept :data c
                        :objects (of objects (:id c)) :questions (of questions (:id c))})
     (for [o objects :when (not (ids (:concept o)))] {:card :object :data o})
     (for [q questions :when (not (ids (:concept q)))] {:card :question :data q}))))

(defn- parse-reply
  "The model's JSON reply → a map. Fences, a leading newline or a line of
   prose around the JSON are tolerated. A reply with no JSON at all is an
   ex-info (`:not-json`); a SyntaxError means the JSON was cut — only that
   one is read again in halves (review 2026-10-16 A20: a leading newline
   before the fence re-split the whole remaining queue)."
  [raw]
  (let [s (-> (str raw) str/trim (str/replace #"(?s)^```(json)?" "") (str/replace #"```\s*$" "") str/trim)
        i (str/index-of s "{")
        j (str/last-index-of s "}")]
    (cond
      (nil? i) (throw (ex-info (t :chat/reply-not-json) {:not-json true}))
      ;; the whole object when one is there; else the cut tail fails as cut
      (and j (> j i)) (js->clj (js/JSON.parse (subs s i (inc j))) :keywordize-keys true)
      :else (js->clj (js/JSON.parse (subs s i)) :keywordize-keys true))))

(defn- concept-payload [c]
  (cond-> {:id (:id c) :title (:title c) :definition (or (:definition c) "")}
    ;; a custom ontology's node TYPE (the model's `type`); learning's kind tag
    (or (:type c) (:kind c)) (assoc :kind (keyword (or (:type c) (:kind c))))
    (seq (:evidence c)) (assoc :evidence (:evidence c))
    (:source-id c) (assoc :source-id (:source-id c))))

(defn- screen-context
  "What the author was looking at before opening the chat (design #2), so
   «this topic» needs no repeating. nil when nothing is in focus."
  []
  (let [{:keys [kind id]} @focus kg (store/kg)]
    (case kind
      ;; i18n-ignore
      :topic (when-let [c (get-in kg [:concepts id])] (str "\nСейчас перед пользователем тема " id " («" (:title c) "»)."))
      ;; i18n-ignore
      :course (when-let [c (get-in kg [:courses id])] (str "\nСейчас перед пользователем курс " id " («" (:title c) "»)."))
      nil)))

;; the author intake's hands — explicit, so a tool registered for another
;; mode (the guide's build-from-material) is never offered here
;; — and by profile: a graph with nothing learnable has no courses, learners
;; or exercises, so those are never offered there (business dogfood
;; 2026-10-15: the intake of a process map was told it could start a course)
(defn- intake-reads []
  (if (profile/learnable? (store/profile))
    #{"search-nodes" "open-node" "list-courses" "learner-gaps"}
    #{"search-nodes" "open-node" "check-cqs"}))
(defn- intake-acts []
  (if (profile/learnable? (store/profile))
    #{"go-to-node" "start-course" "draft-exercises"}
    #{"go-to-node"}))

(defn- ask-with-tools!
  "An intake call in which the model may LOOK first (tools/converse!). The
   chat shows each lookup and a notice when the rounds run out. `max-tokens`
   (optional) raises the reply cap for this call."
  [system user & [max-tokens]]
  (tools/converse!
   {:system system :user user :parse parse-reply :max-tokens max-tokens
    :ctx {:kg (store/kg) :graph-id @store/current :allowed (intake-reads)}
    :on-call (fn [{:keys [name args]}]
               (swap! msgs conj {:who :sys :text (t :chat/tool-used {"{name}" name "{args}" (js/JSON.stringify (clj->js args))})}))
    :on-limit #(swap! msgs conj {:who :sys :text (t :chat/tool-limit)})}))

(defn- step-node?
  "Can this node be a step? A custom ontology: a kind its ordering relation
   joins (a role or system quoted under a step heading is not the step)."
  [c]
  (let [p (store/profile) k (some-> (or (:kind c) (:type c)) keyword)]
    (or (profile/learning? p) (nil? k)
        (boolean (profile/fits? p (profile/ordering-relation p) k k)))))

(defn- step-label [{:keys [n sub]}] (str n (when sub (str "." sub))))

(defn- missing-in
  "The document's lists with steps no node sits under, counting the graph's
   nodes, its pending proposals and `prior`."
  [text prior]
  (let [kg (store/kg)]
    (fold/missing-steps text (concat prior (vals (:concepts kg))
                                     (filter #(= :concept (:of %)) (vals (:proposals kg))))
                        step-node?)))

(defn- add-missing-steps!
  "After a document's intake: one pinned card per numbered list whose steps
   got no node (`fold/missing-steps`) — the author re-reads just those. The
   card keeps WHERE the list is, never its text: cards live in saved
   sittings (30 of them), and a list section could be most of a 100k
   document (review 2026-10-16); the re-read takes the text from the blob
   store."
  [d prior]
  (swap! cards into (for [{:keys [parent parent-at steps]} (missing-in (:text d) prior)]
                      {:card :missing-steps
                       :data {:id (str "missing-" (hash [(:name d) parent-at]))
                              :name (:name d) :source-id (:source-id d)
                              :parent parent :parent-at parent-at
                              :steps (mapv #(select-keys % [:n :sub :title]) steps)}})))

(declare resolve-card!)

(defn- add-type-drafts!
  "One card per kind the ontology lacks, naming the nodes that wait for it
   (wiki/Ontology-Graph.md slice 4)."
  [oid waiting]
  (let [have (set (keep #(when (= :type-draft (:card %)) (get-in % [:data :id])) @cards))]
    (swap! cards into
           (for [[k ws] (group-by (comp str :type) waiting) :when (not (have k))]
             {:card :type-draft
              :data {:id k :title (or (some #(not-empty (:type_label %)) ws) k)
                     :graph oid :examples (vec (take 3 (map :title ws)))}}))))

(defn accept-type-draft!
  "The missing kind joins the ONTOLOGY graph (another graph on this device)
   and its copy comes back here. The nodes that waited for it now go the
   intake's normal way: proposed (ghosts on the map) and through the pair
   search — so their links are found too (2026-11-20)."
  [{:keys [data] :as c}]
  (let [home @store/current]
    (if-let [err (store/add-to-ontology! (:graph data) [[:node-set (:id data) {:title (:title data)}]])]
      (reset! store/last-error err)
      (let [ws (filter #(= (:id data) (:waits-type %)) @cards)
            ok (filterv #(nil? (store/append! {:ev/type :concept-proposed
                                                :concept (concept-payload (:data %))
                                                :provenance :llm-drafted}))
                        ws)
            ok? (set ok)]
        (reset! store/last-error nil)
        (resolve-card! c)
        (swap! cards (fn [cs] (mapv #(if (ok? %) (dissoc % :waits-type) %) cs)))
        (when (seq ok)
          (reset! busy true)
          ;; pending proposals count as nodes here, as the intake's `prior` does:
          ;; the document's other nodes are usually still cards
          (-> (propose-edges! (update (store/kg) :concepts merge
                                      (into {} (for [[id pr] (:proposals (store/kg)) :when (= :concept (:of pr))] [id pr])))
                              (mapv (comp concept-payload :data) ok) home)
              (p/catch (fn [e] (swap! msgs conj {:who :sys :text (str (t :chat/error-prefix) (ex-message e))})))
              (p/finally #(reset! busy false))))))))

(defn- add-relation-drafts!
  "One card per relation the ontology lacks: its words and the type pairs
   it would join, read off the waiting links' endpoints."
  [oid kg waiting]
  (let [have (set (keep #(when (= :relation-draft (:card %)) (get-in % [:data :id])) @cards))
        kind (fn [id] (or (get-in kg [:concepts id :kind]) (get-in kg [:proposals id :kind])))
        title (fn [id] (or (get-in kg [:concepts id :title]) (get-in kg [:proposals id :title]) id))]
    (swap! cards into
           (for [[k es] (group-by (comp str :type) waiting) :when (not (have k))]
             {:card :relation-draft
              :data {:id k :graph oid
                     :label (or (some #(not-empty (:label %)) es) k)
                     :inverse (some #(not-empty (:inverse %)) es)
                     :pairs (vec (distinct (keep (fn [e] (let [a (kind (:from e)) b (kind (:to e))]
                                                           (when (and a b) [(name a) (name b)])))
                                                 es)))
                     :examples (vec (take 2 (map #(str (title (:from %)) " → " (title (:to %))) es)))}}))))

(defn accept-relation-draft!
  "The missing relation joins the ONTOLOGY graph — one edge per type pair it
   joins, its words as properties — and its copy comes back here."
  [{:keys [data] :as c}]
  (let [props (cond-> {:label (:label data)} (seq (:inverse data)) (assoc :inverse (:inverse data)))
        ops (vec (for [[a b] (:pairs data)] [:edge-set a (keyword (:id data)) b props]))]
    (if (empty? ops)
      (resolve-card! c)
      (if-let [err (store/add-to-ontology! (:graph data) ops)]
        (reset! store/last-error err)
        (resolve-card! c)))))

(defn- intake-part!
  "One part of the material → topic cards, then link cards judged over
   this part's topics. `prior` = topics proposed from earlier parts of the
   same document: named to the model so it does not repeat them, and in
   the engine's pair search so links across parts are found. Promise of
   {:concepts :dropped :note}."
  [home d material prior step!]
  (p/let [summary (existing-summary (store/kg) material)
          ops (ask-with-tools! (str (ops-instruction) (tools/prompt-block {:only (intake-reads) :acts (intake-acts)}) (content-language) (answer-language) (screen-context) "\n\n" summary
                              (when (seq prior)
                                ;; i18n-ignore
                                (str "\nУже предложено из этого материала — эти узлы не повторяй; но если в этой части о них есть подробности"
                                     ;; i18n-ignore
                                     " (списки, пороги, сроки, шаги), добавь их в objects с id этого узла: "
                                     (str/join ", " (map :id prior)))))
                         material
                         ;; one node per entity of a dense part answers long
                         (llm/long-reply-cap))]
    (if-not (store/append-to? home)
      (p/rejected (ex-info "wrong graph" {:wrong-graph true}))
      (let [src (:source-id d)
            ;; a kind the ontology lacks (slice 4): when the graph's ontology is
            ;; on this device, those nodes wait for their type instead of being
            ;; dropped — a type-draft card offers to add it to the ontology
            oid (some-> (store/kg) :ontology :graph)
            p (store/profile)
            new-type? #(and oid (store/graph-on-device? oid) (seq (str (:type %)))
                            (nil? (profile/node-type-of p (keyword (:type %)))))
            {waiting true fits false} (group-by (comp boolean new-type?) (:concepts ops))
            ;; proposals land in the event log too — the map shows them as
            ;; dashed ghosts; the guard drops duplicates and re-proposals of
            ;; discarded things, and their cards with them. Concepts from a
            ;; document carry its source id into the accept (→ grounded-in)
            ok-c (filterv #(nil? (store/append!
                                  {:ev/type :concept-proposed
                                   :concept (concept-payload %)
                                   :provenance :llm-drafted}))
                          (map #(cond-> % src (assoc :source-id src)) fits))
            waiting (map #(cond-> % src (assoc :source-id src)) waiting)]
        (when (seq waiting) (add-type-drafts! oid waiting))
        ;; drops are expected here and reported in-chat — not in the banner
        (reset! store/last-error nil)
        (when (seq ok-c) (tour/maybe-chat-tour!))
        ;; proposed ACTIONS become one-tap cards; ones that point at nothing
        ;; are dropped — nothing runs until the user taps
        (let [ctx {:kg (store/kg) :graph-id @store/current}]
          (swap! cards into (keep #(when ((intake-acts) (str (:name %))) (tools/action-card ctx %)) (:actions ops))))
        ;; step-1 edges (if a model still returns them) are ignored — step 2
        ;; judges engine candidates instead
        (swap! cards into (map #(if-let [k (some (fn [w] (when (= (:id w) (get-in % [:data :id])) (:type w))) waiting)]
                                  (assoc % :waits-type (str k)) %)
                               (ops->cards
                                (assoc ops :concepts (into ok-c waiting)
                                       :objects (map #(cond-> % src (assoc :source-id src)) (:objects ops))))))
        (step! :edges)
        ;; where each topic sits in the document's numbered headings («#### 3. …»):
        ;; the pair search reads step order from it, as it reads page marks.
        ;; The WHOLE document, not the part: a part starting at «4.B» lacks
        ;; the heading that names the list, and its steps never met 4.A's
        ;; A re-read of missing steps brings its list's text as :steps-text.
        (p/let [whole (or (:steps-text d) (:text d) material)
                steps (fold/numbered-steps whole)
                stepped #(if-let [st (fold/step-of steps whole %)] (assoc % :step st) %)
                batch (map (comp stepped concept-payload) ok-c)
                ;; nodes already in the graph sit in the steps too: a re-read
                ;; step 5 pairs with the 4.x and 6 accepted before
                _ (propose-edges! (-> (store/kg)
                                      (update :concepts merge (into {} (map (juxt :id identity) prior)))
                                      (update :concepts #(into {} (map (fn [[id c]] [id (stepped c)])) %)))
                                  batch home)]
          {:concepts batch
           :dropped (- (count (:concepts ops)) (count ok-c) (count waiting))
           :note (:note ops)})))))

(defn- part-label [{:keys [i n from to]}]
  (if from
    (t :chat/part-pages {"{from}" from "{to}" to})
    (t :chat/part-n {"{i}" (inc i) "{n}" n})))

(defn stop! [] (reset! stop? true))

(declare ontology-turn!)

;; --- documents into an ontology graph (wiki/Ontology-Graph.md slice 3) --------
;; With a graph on the types ontology open, a document is read for KINDS OF
;; THINGS and HOW THEY RELATE: type cards and relation cards. Accepting writes
;; the property-graph ops — a type is a node, a relation an edge between two
;; types with its words as properties; a relation card brings its two types
;; along when they are not in the graph yet, so the order of accepts does not
;; matter. A Turtle or Loretile edn file is read by code, not by a model.

(defn ontology-file? [d] (boolean (re-find #"(?i)\.(ttl|edn)$" (str (:name d)))))

(def ^:private vocab-instruction
  (str
   ;; i18n-ignore
   "Ты помогаешь построить онтологию — словарь предметной области: ВИДЫ вещей (типы узлов) и то, как они СВЯЗАНЫ (связи между типами)." "\n"
   ;; i18n-ignore
   "Не перечисляй конкретные экземпляры (не «Иван Петров», а «сотрудник»; не «счёт №42», а «счёт»). Типов 3–15, связей столько, сколько материал называет." "\n"
   ;; i18n-ignore
   "Связь читается от `from` к `to` («роль выполняет процесс»: from=роль, to=процесс); inverse — как она читается обратно." "\n"
   ;; i18n-ignore
   "Ответь ТОЛЬКО JSON:" "\n"
   ;; i18n-ignore
   "{\"types\":[{\"id\":\"slug\",\"label\":\"как называется\",\"description\":\"одно предложение: что это\",\"evidence\":\"цитата из материала\"}]," "\n"
   ;; i18n-ignore
   " \"relations\":[{\"id\":\"slug\",\"label\":\"как читается\",\"inverse\":\"как читается обратно\",\"from\":\"id типа\",\"to\":\"id типа\",\"description\":\"одно предложение\",\"evidence\":\"цитата\"}]," "\n"
   ;; i18n-ignore
   " \"note\":\"коротко: что это за материал\"}" "\n"
   ;; i18n-ignore
   "Если тип или связь уже есть в онтологии — используй ТОТ ЖЕ id. Не используй id requires, generalizes, is-confused-with, part-of, see-also."))

(defn- vocab-cards
  "A vocabulary — the model's or a parsed file's — as type and relation
   cards. Relations name their ends by id; titles ride along so an accept
   can bring a missing type with it. Reserved ids never become cards."
  [types relations kg]
  (let [tid #(let [s (str/trim (str %))] (when (seq s) s))
        titles (merge (into {} (for [[id c] (:concepts kg)] [id (:title c)]))
                      (into {} (for [t types :let [id (tid (:id t))] :when id] [id (or (not-empty (:label t)) id)])))
        reserved (set (map name (profile/reserved-ids)))]
    (concat
     (for [t types :let [id (tid (:id t))] :when id]
       {:card :type
        :data (cond-> {:id id :title (or (not-empty (:label t)) id) :definition (or (:description t) "")}
                (seq (:evidence t)) (assoc :evidence (:evidence t))
                (:color t) (assoc :color (:color t))
                (:icon t) (assoc :icon (:icon t))
                (:learnable? t) (assoc :learnable? true))})
     (for [r relations
           :let [id (tid (:id r)) from (tid (:from r)) to (tid (:to r))]
           :when (and id from to (not (reserved id)) (not= "grounded-in" id))]
       {:card :relation
        :data (cond-> {:id id :from from :to to :label (or (not-empty (:label r)) id)
                       :from-title (get titles from from) :to-title (get titles to to)}
                (seq (:inverse r)) (assoc :inverse (:inverse r))
                (seq (:description r)) (assoc :description (:description r))
                (seq (:evidence r)) (assoc :evidence (:evidence r))
                (:ordering? r) (assoc :ordering? true)
                (:acyclic? r) (assoc :acyclic? true))}))))

(defn- profile->cards
  "A parsed ontology file's profile → the same cards (one relation card per
   from → to pair it allows)."
  [p kg]
  (vocab-cards (for [t (:node-types p)] (assoc t :id (name (:id t))))
               (for [r (:relations p) :when (not= :grounded-in (:id r)) f (:from r) to (:to r)]
                 (assoc r :id (name (:id r)) :from (name f) :to (name to)))
               kg))

(defn- vocab-event
  "The `change` a type or relation card writes when accepted."
  [{:keys [card data]} kg]
  (case card
    :type {:ev/type :change
           :ops [[:node-set (:id data) (select-keys data [:title :definition :color :icon :learnable?])]]}
    :relation (let [{:keys [id from to from-title to-title]} data
                    missing (fn [n t] (when-not (get-in kg [:concepts n]) [[:node-set n {:title (or t n)}]]))]
                {:ev/type :change
                 :ops (vec (concat (missing from from-title)
                                   (when (not= from to) (missing to to-title))
                                   [[:edge-set from (keyword id) to
                                     (select-keys data [:label :inverse :description :ordering? :acyclic?])]]))})))

(defn- ontology-intake!
  "A document (or typed text) into the open ontology graph: a Turtle/edn file
   is parsed; anything else is read by the model, part by part."
  [home d text]
  ;; nil text = the triage card already showed this drop
  (when (some? text)
    (swap! msgs conj {:who :user :text (cond-> text d (str (when (seq text) "\n") (t :chat/file-tag) (:name d)))}))
  (reset! graph home) (reset! doc nil)
  (when (empty? @cards) (reset! decided 0))
  (if (and d (ontology-file? d))
    (let [{:keys [profile error]} (store/read-profile (:text d) (:name d))]
      (if error
        (swap! msgs conj {:who :sys :text (str (t :chat/error-prefix) (pr-str (:detail error)))})
        (let [cs (profile->cards profile (store/kg))]
          (swap! cards into cs)
          (swap! msgs conj {:who :llm :text (t :chat/vocab-proposed {"{t}" (count (filter #(= :type (:card %)) cs))
                                                                   "{r}" (count (filter #(= :relation (:card %)) cs))})}))))
    (let [parts (if d (fold/text-chunks (:text d) chunk-chars) [{:text text}])
          known (fn [] (let [v (fold/defined (store/kg))]
                         ;; i18n-ignore
                         (str "\n\nУже в онтологии — типы:\n" (profile/node-type-lines v)
                              ;; i18n-ignore
                              "\nсвязи:\n" (profile/relation-lines v))))]
      (reset! busy true) (reset! stop? false)
      (-> (reduce (fn [acc {part :text n :n}]
                    (p/let [{:keys [types relations notes]} acc]
                      (if @stop?
                        acc
                        (p/let [raw (llm/chat! (str vocab-instruction (answer-language) (known)
                                                    (when (seq types)
                                                      ;; i18n-ignore
                                                      (str "\nУже предложено из этого материала: " (str/join ", " (map :id types)))))
                                               (cond-> text
                                                 ;; i18n-ignore
                                                 d (str "\n\nМатериал из файла «" (:name d) "»" (when (> n 1) (str ", часть из " n)) ":\n" part))
                                               {:max-tokens (llm/long-reply-cap)})
                                ops (parse-reply raw)]
                          {:types (into types (remove (fn [x] (some #(= (:id %) (:id x)) types)) (:types ops)))
                           :relations (into relations (:relations ops))
                           :notes (conj notes (:note ops))}))))
                  (p/resolved {:types [] :relations [] :notes []})
                  parts)
          (p/then (fn [{:keys [types relations notes]}]
                    (when (store/append-to? home)
                      (let [cs (vocab-cards types relations (store/kg))]
                        (swap! cards into cs)
                        (swap! msgs conj {:who :llm :text (str (some not-empty notes) (when (some not-empty notes) "\n\n")
                                                               (t :chat/vocab-proposed {"{t}" (count (filter #(= :type (:card %)) cs))
                                                                                       "{r}" (count (filter #(= :relation (:card %)) cs))}))})))))
          (p/catch (fn [e] (swap! msgs conj {:who :sys :text (str (t :chat/error-prefix) (ex-message e))})))
          (p/finally #(reset! busy false))))))

;; --- the one door: nothing open → ask first (wiki/Ontology-Graph.md) --------

(defn nothing-open?
  "«Nothing open» (Denis 2026-11-19, «context matters»): the open graph is
   empty, bound to no ontology and describes no types — a fresh «Мой граф»
   or a just-made graph (not a learner's personal one). Anything else takes
   a document as its extension."
  []
  (let [kg (store/kg)]
    (and (empty? (:concepts kg)) (empty? (:proposals kg))
         (not (:ontology-graph? kg)) (nil? (:ontology kg))
         (profile/learning? (fold/profile kg))
         ;; a learner's own graph is built FROM their material: content, by
         ;; the choice that made it (build-from-material) — never asked again
         (not (store/personal?)))))

(defn- triage!
  "The document waits in a card that asks what it is — its guess first."
  [home d]
  (reset! graph home) (reset! doc nil)
  (when (empty? @cards) (reset! decided 0))
  (swap! msgs conj {:who :user :text (str (t :chat/file-tag) (:name d))})
  (swap! cards (fn [cs] (vec (cons {:card :triage
                                    :data {:id (str "triage-" (random-uuid)) :doc d
                                           :what (if (ontology-file? d) :ontology :content)
                                           :ontology "learning"}}
                                   cs)))))

(declare content-intake! resolve-card!)

(defn- doc-title
  "What a document calls itself: its first markdown heading, else its file
   name without the extension («Онтология: priemka.md» named a derived
   ontology after the file — real-model run 2026-11-20)."
  [{:keys [name text]}]
  (or (some->> (re-find #"(?m)^#{1,3}\s+(.+)$" (str text)) second str/trim not-empty)
      (str/replace (str name) #"\.[A-Za-z0-9]{1,5}$" "")))

(defn triage-go!
  "The author's answer: the open (empty) graph becomes an ontology and the
   document is read for types and relations — or the graph takes the chosen
   ontology (built-in learning, or a graph of the set) and the document is
   read as its content."
  [{:keys [data] :as c}]
  (let [{:keys [doc what ontology]} data
        home @store/current]
    (resolve-card! c)
    (cond
      (= :ontology what)
      (when (nil? (store/make-types-graph!)) (ontology-intake! home doc nil))
      ;; derive: an ontology graph is made and the document read there for its
      ;; types and relations; a pinned card then reads it as THIS graph's content
      (= "derive" ontology)
      (when-let [oid (store/new-ontology-graph! (str (t :ui/ontology-of) " " (doc-title doc)))]
        (ontology-intake! oid doc nil)
        (swap! cards conj {:card :derive-next
                           :data {:id (str "derive-" oid) :doc doc :graph home :ontology oid}}))
      (= "learning" ontology) (content-intake! home doc nil)
      :else (if-let [err (store/bind! home ontology)]
              (reset! store/last-error err)
              (content-intake! home doc nil)))))

(defn- content-intake!
  "A document (or typed text) read into the open data graph as topic and
   link cards — the two-step intake."
  [home d text]
  (let [parts (if d
              (fold/text-chunks (:text d) chunk-chars)
              [{:text text}])
      material (fn [{part :text n :n}]
                 (cond-> text
                   ;; i18n-ignore — the material's framing for the model, not shown
                   d (str "\n\nМатериал из файла «" (:name d) "»"
                          ;; i18n-ignore
                          (when (:pages d) " (постранично)")
                          ;; i18n-ignore
                          (when (> n 1) (str ", часть из " n))
                          ":\n" part)))]
  ;; nil text = the triage card already showed this drop
  (when (some? text)
    (swap! msgs conj {:who :user :text (cond-> text
                                         d (str (when (seq text) "\n") (t :chat/file-tag) (:name d)))}))
  (when (empty? @cards) (reset! decided 0))
  (reset! graph home)
  (reset! doc nil)
  (reset! stop? false)
  (reset! busy true)
  ;; a QUEUE of parts: a part whose reply arrives cut at the provider's
  ;; output cap (no valid JSON) is read again in halves — a dense
  ;; process document answered 7.5–9.3k tokens per 20k-char part
  ;; against a 4096 cap and lost every part (business dogfood 2026-10-15)
  (letfn [(run [queue done prior dropped notes]
            (if (or (empty? queue) @stop?)
              (p/resolved {:i done :n (+ done (count queue)) :prior prior :dropped dropped :notes notes})
              (let [n (+ done (count queue))
                    part (assoc (first queue) :i done :n n)]
                (reset! progress (assoc part :step :concepts))
                (-> (intake-part! home d (material part) prior
                                  #(swap! progress assoc :step %))
                    (p/then (fn [r] (run (subvec queue 1) (inc done) (into prior (:concepts r))
                                         (+ dropped (:dropped r)) (conj notes (:note r)))))
                    (p/catch (fn [e]
                               (cond
                                 (:wrong-graph (ex-data e)) (p/rejected e)
                                 (and (instance? js/SyntaxError e) (> (count (:text part)) min-split-chars))
                                 ;; the rest of the queue is cut to the same size up front:
                                 ;; a document that overflowed once overflows again, and every
                                 ;; overflow costs a whole call
                                 (let [cap (quot (inc (count (:text part))) 2)
                                       halves (fold/text-chunks (:text part) cap)
                                       rest (mapcat #(fold/text-chunks (:text %) cap) (subvec queue 1))]
                                   (swap! msgs conj {:who :sys :text (t :chat/part-split {"{part}" (part-label part)})})
                                   (run (into (vec halves) rest) done prior dropped notes))
                                 ;; one unreadable reply costs one part, not the document
                                 :else
                                 (do (swap! msgs conj {:who :sys :text (str (when (> n 1) (str (part-label part) " — "))
                                                                            (t :chat/error-prefix) (ex-message e))})
                                     (run (subvec queue 1) (inc done) prior dropped notes)))))))))]
    (-> (run (vec parts) 0 [] 0 [])
        (p/then (fn [{:keys [i n prior dropped notes]}]
                  (swap! msgs conj
                         {:who :llm
                          :text (cond
                                  (< i n) (t :chat/parts-stopped {"{i}" i "{n}" n "{c}" (count prior)})
                                  (> n 1) (t :chat/parts-done {"{n}" n "{c}" (count prior)})
                                  :else (or (some not-empty notes) (t :chat/proposals-note)))})
                  (when (pos? dropped)
                    (swap! msgs conj {:who :sys :text (str (t :chat/dropped-prefix) dropped)}))
                  (when (and d (= i n) (store/append-to? home)) (add-missing-steps! d prior))))
        (p/catch (fn [e]
                   (swap! msgs conj {:who :sys :text (if (:wrong-graph (ex-data e))
                                                       (t :chat/wrong-graph)
                                                       (str (t :chat/error-prefix) (ex-message e)))})))
        (p/finally #(do (reset! busy false) (reset! progress nil)))))))

(defn send! [text]
  (let [d @doc
        ;; the graph this conversation belongs to: an answer that arrives
        ;; after the author switched graphs must not land in the new one
        home @store/current
        text (str/trim (or text ""))]
    (cond
      (or (not (or (seq text) d)) @busy) nil

      (foreign?)
      (swap! msgs conj {:who :sys :text (t :chat/cards-other-graph {"{title}" (store/graph-title @graph)})})

      ;; an ontology graph takes a document as types and relations (slice 3)
      (:ontology-graph? (store/kg))
      (ontology-intake! home d text)

      ;; nothing open (before the file refusal: an ontology file into an
      ;; empty graph is asked about, its guess «ontology»)
      ;; — an empty graph bound to no ontology: a document is
      ;; asked about first (ontology or content, and on which ontology)
      (and d (nothing-open?))
      (triage! home d)

      ;; a vocabulary file belongs in an ontology graph, not among the data
      (and d (ontology-file? d))
      (do (reset! doc nil) (swap! msgs conj {:who :sys :text (t :chat/vocab-file-elsewhere)}))

      ;; the ontology interview: the answer is a turn of it, documents wait
      @onto
      (do (reset! doc nil)
          (when d (swap! msgs conj {:who :sys :text (t :chat/onto-doc-later)}))
          (when (seq text) (ontology-turn! home text)))

      :else (content-intake! home d text))))

;; --- the drafting bench: the model drafts exercises, the runtime judges -------
;; wiki/Exercises.md «The bench». Every draft must pass the AUTHOR's gate
;; (ex/check-solution!) and must REJECT each plausible wrong answer the model
;; offered with it; only survivors reach the author, as cards. The author
;; still accepts — executed is not the same as pedagogically sound.

(defn- exercises-system [rt seed]
  (str
   ;; i18n-ignore
   "Ты помощник автора курса. Предложи 3 упражнения с запуском кода по теме ниже." "\n"
   (if (= :pglite rt)
     (str
      ;; i18n-ignore
      "Язык: SQL (PostgreSQL). Решение — один запрос SELECT. Проверка check = \"rows\": сравниваются строки результата;" "\n"
      ;; i18n-ignore
      "ordered = true, только если задача требует порядок." "\n"
      (if (seq seed)
        ;; i18n-ignore
        (str "Данные уже заданы, пиши запросы к ним и поле setup оставь пустым:\n" seed "\n")
        ;; i18n-ignore
        "В setup создай небольшие таблицы и данные (create table …; insert …).\n"))
     (str
      ;; i18n-ignore
      "Язык: Python (только стандартная библиотека). Проверка check: \"output\" — сравнивается то, что печатает код," "\n"
      ;; i18n-ignore
      "или \"assert\" — скрытые проверки в поле test (строки assert …), ученик их не видит." "\n"
      ;; i18n-ignore
      "setup — необязательная подготовка (данные), выполняется перед кодом ученика." "\n"))
   ;; i18n-ignore
   "К каждому упражнению дай 1–2 правдоподобных НЕВЕРНЫХ ответа ученика в wrong — типичные ошибки темы." "\n"
   ;; the learners read the stems: the GRAPH's language, not the author's interface
   ;; i18n-ignore
   "Условия (stem) и всё, что видит ученик, пиши на языке: " (store/locale-for-new) "\n"
   ;; i18n-ignore
   "Отвечай ТОЛЬКО валидным JSON без markdown:" "\n"
   "{\"exercises\":[{\"stem\":\"…\",\"setup\":\"…\",\"solution\":\"…\",\"check\":\"output|assert|rows\",\"test\":\"…\",\"ordered\":false,\"wrong\":[\"…\"]}]}"
   (answer-language)))

(defn- topic-brief [kg cid]
  (let [c (get-in kg [:concepts cid])]
    ;; i18n-ignore — the topic as framed for the model, not shown
    (str "Тема: " (:title c) "\nОпределение: " (:definition c)
         (when-let [os (seq (fold/concept-objects kg cid))]
           ;; i18n-ignore
           (str "\nМатериалы:\n" (str/join "\n" (map #(str "- " (fold/plain-text (:text %))) os)))))))

(defn- draft->spec
  "The model's draft as an exercise spec. A graph's SQL seed is the ENGINE's
   to keep: whatever setup the model wrote, the seed wins."
  [rt seed d]
  (let [chk (keyword (or (:check d) ""))]
    (if (= :pglite rt)
      {:runtime :pglite :check :rows :stem (:stem d) :solution (:solution d)
       :setup (if (seq seed) seed (or (:setup d) "")) :ordered? (boolean (:ordered d))}
      (cond-> {:runtime :python :check (if (= :assert chk) :assert :output)
               :stem (:stem d) :setup (or (:setup d) "") :solution (:solution d)}
        (= :assert chk) (assoc :test (or (:test d) ""))))))

(defn- judge
  "Promise of {:spec :rejected n} for a survivor or {:dropped err}."
  [spec wrongs]
  (p/let [gate (ex/check-solution! spec)]
    (if (:error gate)
      {:dropped gate :spec spec}
      (p/let [verdicts (p/all (map #(ex/grade! spec %) wrongs))]
        (if-let [passed (some (fn [[w v]] (when (:correct? v) w)) (map vector wrongs verdicts))]
          {:dropped {:error :accepts-wrong :message passed} :spec spec}
          {:spec (cond-> spec (:schema gate) (assoc :schema (:schema gate)))
           :rejected (count wrongs)})))))

(defn draft-exercises!
  "Ask for exercises on one topic, judge every draft in the runtime, and
   lay the survivors out as cards. One model call per click."
  [cid rt]
  (let [home @store/current
        kg (store/kg)
        seed (when (= :pglite rt) (ex/graph-setup kg :pglite cid))
        title (get-in kg [:concepts cid :title])]
    (cond
      @busy nil
      (foreign?) (refuse-claim!)
      :else
      (do
      (when (empty? @cards) (reset! decided 0))
      (reset! graph home)
      (reset! busy true)
      (swap! msgs conj {:who :user :text (t :chat/draft-exercises-request {"{title}" title})})
      (-> (p/let [raw (llm/chat! (exercises-system rt seed) (topic-brief kg cid))
                  drafts (vec (:exercises (parse-reply raw)))
                  judged (reduce (fn [acc d]
                                   (p/let [done acc
                                           r (judge (draft->spec rt seed d) (vec (map str (:wrong d))))]
                                     (conj done r)))
                                 (p/resolved []) drafts)]
            (if-not (store/append-to? home)
              (p/rejected (ex-info "wrong graph" {:wrong-graph true}))
              (let [kept (remove :dropped judged)]
                (swap! cards into (for [{:keys [spec rejected]} kept]
                                    {:card :exercise
                                     :data {:id (str "x-" (random-uuid)) :concept cid :spec spec :rejected rejected}}))
                (swap! msgs conj {:who :llm :text (t :chat/exercises-result {"{n}" (count judged) "{k}" (count kept)})})
                (doseq [{:keys [dropped spec]} judged :when dropped]
                  (swap! msgs conj {:who :sys :text (str (t :chat/exercise-dropped {"{stem}" (:stem spec)})
                                                         (ex/error-text dropped))})))))
          (p/catch (fn [e]
                     (swap! msgs conj {:who :sys :text (if (:wrong-graph (ex-data e))
                                                         (t :chat/wrong-graph)
                                                         (str (t :chat/error-prefix) (ex-message e)))})))
          (p/finally #(reset! busy false)))))))

;; --- card actions -----------------------------------------------------------

(defn- drop-card! [c] (swap! cards (fn [cs] (vec (remove #(= % c) cs)))))

(defn- resolve-card! [c]
  (drop-card! c)
  (swap! decided inc))

(declare refuse-foreign!)

(defn version-answer!
  "The upload IS a new version of `old` (yes) — or a separate document (no).
   Yes: the topics written from the old version are sorted by the passage
   each quotes (`fold/sort-by-quote`, the old text from the blob store, the
   new from the attachment): a topic whose quote CHANGED gets its card first,
   «the meaning changed» suggested and the old quote shown; the topics whose
   quote is still there share one card («just corrections», all at once, or
   one by one); a topic with no quote to check gets its own card as before.
   Change test 2026-10-23: 207 topics flagged where 5 had changed."
  [c yes?]
  (let [{:keys [new candidates pick]} (:data c)
        old (or pick (ffirst candidates))
        old-title (some (fn [[id title]] (when (= id old) title)) candidates)
        new-text (when (= new (:source-id @doc)) (:text @doc))]
    (resolve-card! c)
    (when yes?
      (let [topics (grounded-topics (store/kg) old)]
        (if (empty? topics)
          (swap! msgs conj {:who :sys :text (t :chat/reversion-none {"{old}" old-title})})
          (p/let [old-text (p/catch (blobs/get-text (str old ".md")) (fn [_] nil))]
            (let [kg (store/kg)
                  {:keys [changed kept unchecked]} (fold/sort-by-quote kg topics old-text new-text)
                  card (fn [cid & [changed?]]
                         {:card :reversion :data (cond-> {:id (str "r-" old "-" cid) :source old :new new :concept cid}
                                                   changed? (assoc :changed? true :quote (get-in kg [:concepts cid :evidence])))})]
              (swap! cards into (concat (map #(card % true) changed)
                                        (when (seq kept)
                                          [{:card :reversion-kept :data {:id (str "rk-" old) :source old :new new :concepts (vec kept)}}])
                                        (map card unchecked)))
              (swap! msgs conj {:who :sys :text (t :chat/reversion-sorted {"{n}" (count topics) "{old}" old-title
                                                                          "{c}" (count changed) "{k}" (count kept)
                                                                          "{u}" (count unchecked)})}))))))))

(defn reversion-answer!
  "Per topic: :patch (the meaning held) or :material (it changed — the
   topic's material from the old version goes stale; pinned learners keep
   the published version until the next cut)."
  [c change]
  (if (foreign?)
    (refuse-foreign!)
    (let [{:keys [source concept]} (:data c)]
      (when-not (store/append! {:ev/type :source-updated :id source :change change :concepts [concept]})
        (resolve-card! c)
        ;; stale is not a dead end: say what clears it
        (when (= :material change)
          (swap! msgs conj {:who :sys :text (t :chat/reversion-next {"{title}" (get-in (store/kg) [:concepts concept :title] concept)})}))))))

(defn kept-answer!
  "The topics whose quoted passage is still in the new version: all marked
   «just corrections» in one event, or laid out one card each."
  [c split?]
  (if (foreign?)
    (refuse-foreign!)
    (let [{:keys [source new concepts]} (:data c)]
      (if split?
        (do (drop-card! c)
            (swap! cards into (for [cid concepts] {:card :reversion :data {:id (str "r-" source "-" cid) :source source :new new :concept cid}})))
        (when-not (store/append! {:ev/type :source-updated :id source :change :patch :concepts (vec concepts)})
          (resolve-card! c))))))

(defn pick-version! [c old]
  (swap! cards (fn [cs] (mapv #(if (= % c) (assoc-in % [:data :pick] old) %) cs))))

;; --- build a course from the learner's own material (Assistant design #4) ----
;; A course needs, per target, 1 object + 3 questions AT ONE level. The intake
;; drafts topics and material; nothing drafted choice questions — this does.
;; Then one step assembles the course from every ready topic and starts it.

(defn- topic-level
  "The level a topic's material is written at — its questions and its course
   target take the same level, so readiness lines up."
  [kg cid locale]
  (or (some #(when (= locale (:locale %)) (:level %)) (fold/concept-objects kg cid)) :novice))

(defn questions-needed
  "Topics with material in this language but fewer than 3 questions at its level."
  [kg locale]
  (->> (keys (:concepts kg)) sort
       (filter (fn [cid] (and (some #(= locale (:locale %)) (fold/concept-objects kg cid))
                              (let [lvl (topic-level kg cid locale)]
                                (< (count (filter #(and (= lvl (:level %)) (= locale (:locale %))) (fold/concept-items kg cid))) 3)))))
       vec))

(defn ready-topics [kg locale]
  (->> (keys (:concepts kg)) sort
       (filter #(fold/publishable-at? kg % (topic-level kg % locale) locale)) vec))

(defn- questions-system []
  (str
   ;; i18n-ignore
   "Ты помощник автора курса. Для каждой темы ниже составь 3 проверочных вопроса с одним верным ответом." "\n"
   ;; i18n-ignore
   "3–4 варианта, неверные — правдоподобные ошибки по теме (не очевидная чепуха); вопрос проверяет понимание, а не память на слова." "\n"
   ;; i18n-ignore
   "Опирайся только на определение и материалы темы. Пиши на языке: " (store/locale-for-new) "." "\n"
   ;; i18n-ignore
   "Отвечай ТОЛЬКО валидным JSON без markdown: {\"questions\":[{\"concept\":\"id\",\"stem\":\"…\",\"options\":[\"…\"],\"key\":0}]}"))

(defn- valid-question?
  "Shape only — a choice question is checked by its structure, not run:
   a topic it was asked about, a stem, 3–4 distinct options, a key that points at one."
  [asked {:keys [concept stem options key]}]
  (and (asked concept) (seq (str/trim (str stem)))
       (sequential? options) (<= 3 (count options) 4)
       (every? #(seq (str/trim (str %))) options)
       (= (count options) (count (distinct (map #(str/lower-case (str/trim (str %))) options))))
       (int? key) (< -1 key (count options))))

(def ^:private question-batch
  ;; topics per reply: 3 questions × 4 topics stays far under the 4096-token
  ;; reply cap — one reply for a whole document was cut off past ~10 topics
  4)

(defn- draft-batch!
  "One batch of topics → promise of [good bad]; its cards are laid out at once."
  [kg locale cids]
  (p/let [raw (llm/chat! (questions-system)
                         (str/join "\n\n" (for [cid cids :let [c (get-in kg [:concepts cid])]]
                                            ;; i18n-ignore
                                            (str "Тема " cid ": " (:title c) "\nОпределение: " (:definition c)
                                                 ;; i18n-ignore
                                                 "\nМатериалы: " (str/join " | " (map #(fold/plain-text (:text %)) (fold/concept-objects kg cid)))))))
          qs (vec (:questions (parse-reply raw)))
          good (filter #(valid-question? (set cids) %) qs)]
    (swap! cards into (for [q good]
                        {:card :item-draft
                         :data (assoc (select-keys q [:concept :stem :options :key])
                                      :id (str "q-" (random-uuid)) :level (topic-level kg (:concept q) locale))}))
    [(count good) (- (count qs) (count good))]))

(defn draft-questions!
  "Draft 3 choice questions per topic in `cids`, `question-batch` topics per
   reply, one reply after another; malformed ones are dropped and counted; a
   failed batch costs its own topics only. The rest arrive as cards for the
   author — or, in a personal graph, the learner — to accept."
  [cids]
  (let [kg (store/kg) locale (store/locale-for-new)
        batches (vec (partition-all question-batch cids))
        n (count batches)]
    (cond
      (or (empty? cids) @busy) nil
      (foreign?) (refuse-foreign!)
      :else
      (do
        (reset! busy true)
        (reset! stop? false)
        (-> ((fn run [i good bad]
               (if (or (= i n) @stop?)
                 (p/resolved [good bad])
                 (do (reset! progress {:i i :n n :step :questions})
                     (-> (draft-batch! kg locale (nth batches i))
                         (p/then (fn [[g b]] (run (inc i) (+ good g) (+ bad b))))
                         (p/catch (fn [e]
                                    (swap! msgs conj {:who :sys :text (str (when (> n 1) (str (t :chat/part-n {"{i}" (inc i) "{n}" n}) " — "))
                                                                           (t :chat/error-prefix) (ex-message e))})
                                    (run (inc i) good bad)))))))
             0 0 0)
            (p/then (fn [[good bad]]
                      (swap! msgs conj {:who :sys :text (t :chat/questions-drafted {"{n}" good "{bad}" bad})})))
            (p/finally #(do (reset! busy false) (reset! progress nil))))))))

(defn accept-question! [c]
  (if (foreign?)
    (refuse-foreign!)
    (let [{:keys [concept level stem options key]} (:data c)
          id (str "i-" (random-uuid))]
      (when-not (or (when-not (get-in (store/kg) [:concepts concept])
                      ;; its topic was discarded since: a question for nothing
                      (swap! msgs conj {:who :sys :text (t :chat/question-topic-gone)}))
                    (store/append! {:ev/type :item-added
                                    :item {:id id :primary-concept-id concept :level level :kind :single-choice
                                           :locale (store/locale-for-new)
                                           :origin (if (store/personal?) :generated :drafted)
                                           :spec {:stem stem :options (vec options) :key key}}})
                    (store/append! {:ev/type :item-accepted :id id}))
        (resolve-card! c)))))

(defn add-build-card!
  "The guided path's next step, kept at the end of the pile until the course exists."
  [goal]
  (swap! cards (fn [cs] (conj (vec (remove #(= :build-next (:card %)) cs))
                              {:card :build-next :data {:id "build-next" :goal goal}}))))

(defn compose-course!
  "Every ready topic becomes a target at its material's level; the course is
   published in this graph and the learner is taken to it."
  [c]
  (let [kg (store/kg) locale (store/locale-for-new)
        goal (str/trim (str (get-in c [:data :goal])))
        ready (ready-topics kg locale)
        id (str "c-" (.toString (js/Date.now) 36))]
    (cond
      (foreign?) (refuse-foreign!)
      (seq ready)
      (when-not (store/append! {:ev/type :course-published
                                :course {:id id :title (if (seq goal) goal (t :chat/build-default-title))
                                         :targets (mapv (fn [cid] [cid (topic-level kg cid locale)]) ready)
                                         :locale locale :pass-threshold 80}})
        (resolve-card! c)
        (set! js/location.hash (str "#/learn/" id))))))

(defn toggle-draft!
  "Tick/untick a draft or question riding on a topic card."
  [c k j]
  (swap! cards (fn [cs] (mapv #(if (= % c) (update-in % [k j :on?] not) %) cs))))

(defn- refuse-foreign! []
  (swap! msgs conj {:who :sys :text (t :chat/cards-other-graph {"{title}" (store/graph-title @graph)})}))

(defn- object-event [data]
  {:ev/type :object-added
   :object (cond-> {:id (str "o-" (random-uuid))
                    :concept-id (:concept data)
                    :kind (keyword (or (:kind data) "explanation"))
                    :level (keyword (or (:level data) "apprentice"))
                    :locale (store/locale-for-new)
                    ;; a curator's accept makes a draft `drafted`; a learner's
                    ;; own graph has no curator — `generated` (ruling 2026-10-02)
                    :origin (if (store/personal?) :generated :drafted)
                    :text (:text data)}
             (:source-id data) (assoc :grounded-in (:source-id data)))})

(defn- question-event [data]
  {:ev/type :question-raised :id (str "q-" (random-uuid))
   :concept-id (:concept data) :question (:question data)})

(defn accept! [{:keys [card data] :as c}]
  (if (foreign?)
    (refuse-foreign!)
    ;; a card from a MERGE FILE (loretile.merge) carries `:ev`, the event to
    ;; append verbatim — the file's ids and actor are its provenance
    (let [err
          (case card
            :concept (store/append! (or (:ev c) {:ev/type :concept-accepted
                                                 :concept (concept-payload data)}))
            :edge (store/append! (or (:ev c)
                                     {:ev/type :edge-accepted
                                      :edge (cond-> {:from (:from data) :to (:to data)
                                                     :type (keyword (or (:type data) (default-relation)))
                                                     :provenance :llm-drafted
                                                     :justification (:why data)}
                                              ;; the card said «inference, not a quote» — so does the graph
                                              (false? (:stated data)) (assoc :inferred? true))}))
            :object (store/append! (or (:ev c) (object-event data)))
            :question (store/append! (or (:ev c) (question-event data)))
            :item (or (store/append! (:ev c)) (store/append! {:ev/type :item-accepted :id (:id data)}))
            :change (store/append! (:ev c))
            (:type :relation) (store/append! (vocab-event c (store/kg)))
            ;; a drafted exercise: the runtime judged it, the author accepts it
            :exercise (let [id (str "i-" (random-uuid))]
                        (or (store/append! {:ev/type :item-added
                                            :item {:id id :primary-concept-id (:concept data)
                                                   :level :apprentice :kind :exercise :locale (store/locale-for-new)
                                                   :origin (if (store/personal?) :generated :drafted) :spec (:spec data)}})
                            (store/append! {:ev/type :item-accepted :id id}))))]
      (if err
        ;; the guard said no (a loop, a misfit…): the banner says why, and the
        ;; card stops offering an accept that can only be refused again —
        ;; discard stays (business dogfood 2026-10-15: a reversed `triggers`
        ;; link sat at the top of the pile, clickable, for ever)
        (swap! cards (fn [cs] (mapv #(if (= % c) (assoc % :refused true) %) cs)))
        (do
          ;; the topic's ticked drafts and questions go in with it
          (when (= :concept card)
            (doseq [o (:objects c) :when (:on? o)] (store/append! (or (:ev o) (object-event o))))
            (doseq [q (:questions c) :when (:on? q)] (store/append! (or (:ev q) (question-event q)))))
          (resolve-card! c))))))

(defn accept-all!
  "Accept these cards one by one, each through the guard (a refusal stays
   as a refused card) — the author's one tap for a kind they trust."
  [cs]
  (doseq [c cs] (accept! c)))

(defn discard! [{:keys [card data] :as c}]
  (if (foreign?)
    (refuse-foreign!)
    (do
      (when (= :concept card)
        (store/append! {:ev/type :concept-discarded :id (:id data)})
        ;; its link cards can never be accepted now — they go with it
        (swap! cards (fn [cs] (vec (remove #(and (= :edge (:card %))
                                                 (#{(get-in % [:data :from]) (get-in % [:data :to])} (:id data)))
                                           cs)))))
      (when (= :edge card)
        (store/append! {:ev/type :edge-discarded
                        :edge {:from (:from data) :to (:to data)
                               ;; the default the proposal was written with, or the
                               ;; discard misses it and the ghost edge stays
                               :type (keyword (or (:type data) (default-relation)))
                               :provenance :llm-drafted}}))
      (resolve-card! c))))

(defn discard-all! [] (doseq [c @cards] (discard! c)))

(defn pending-marks
  "concept-id → :edit | :remove | :other for the undecided change cards of
   the open graph's sitting — the map lights those topics."
  []
  (when (store/append-to? @graph)
    (into {} (for [c @cards :when (= :change (:card c))
                   :let [{:keys [concept kind]} (:data c)] :when concept]
               [concept kind]))))

;; --- views ------------------------------------------------------------------

(defn config-fields
  "The BYOK form body — lives in the settings page (kintale: settings hold
   the key; the chat only points there). Saves in place."
  []
  (let [cfg (r/atom (or (llm/config) (assoc (:anthropic llm/presets) :preset :anthropic :key "")))
        saved (r/atom false)
        check (r/atom nil)   ; nil | :checking | :ok | {:error …}
        save! (fn [] (llm/save-config! @cfg) (reset! saved true))
        check! (fn []
                 (save!) (reset! check :checking)
                 (p/then (llm/check-key!)
                         (fn [r] (reset! check (or r :ok)))))]
    (fn []
      [:div.chat-setup
       [:p.muted (t :chat/byok-note)]
       ;; a newcomer's three questions: where, what it costs, does it work
       (when-let [u (:key-url (get llm/presets (:preset @cfg)))]
         [:p.key-help (t :chat/key-where) " "
          [:a {:href u :target "_blank" :rel "noopener"} (t :chat/key-get {"{p}" (:label (get llm/presets (:preset @cfg)))})]])
       [:p.muted.key-cost (t :chat/key-cost)]
       [:select {:value (name (or (:preset @cfg) :anthropic))
                 :on-change (fn [e]
                              (let [k (keyword (.. e -target -value))]
                                (reset! check nil)
                                (reset! cfg (assoc (get llm/presets k) :preset k
                                                   :key (:key @cfg "")))))}
        (for [[k v] llm/presets] ^{:key k} [:option {:value (name k)} (:label v)])]
       ;; semi-controlled (CLAUDE.md gotcha): controlled, a busy page dropped
       ;; keystrokes from the key as it was typed. Keyed by provider, so a
       ;; switch shows that provider's address and model
       ^{:key (str "url-" (name (or (:preset @cfg) :anthropic)))}
       [:input {:placeholder (t :chat/url-label) :default-value (:url @cfg)
                :on-change #(swap! cfg assoc :url (.. % -target -value))}]
       ^{:key (str "model-" (name (or (:preset @cfg) :anthropic)))}
       [:input {:placeholder (t :chat/model-label) :default-value (:model @cfg)
                :on-change #(swap! cfg assoc :model (.. % -target -value))}]
       [:input {:placeholder (t :chat/api-key-label) :type "password" :default-value (:key @cfg)
                :on-change #(do (reset! check nil) (reset! saved false) (swap! cfg assoc :key (.. % -target -value)))}]
       (when (= :openai-compat (:shape @cfg))
         [:<>
          [:p.muted (t :chat/vectors-note)]
          [:input {:placeholder (t :chat/embed-url-label) :value (or (:embed-url @cfg) "")
                   :on-change #(swap! cfg assoc :embed-url (.. % -target -value))}]
          [:input {:placeholder (t :chat/embed-model-label) :value (or (:embed-model @cfg) "")
                   :on-change #(swap! cfg assoc :embed-model (.. % -target -value))}]])
       [:div.row
        [:button.primary {:on-click (fn [_] (save!))} (t :chat/save)]
        [:button.key-check {:disabled (or (str/blank? (:key @cfg)) (= :checking @check))
                            :on-click (fn [_] (check!))}
         (t :chat/key-check)]
        (when (and @saved (nil? @check)) [:span.muted (t :chat/saved)])]
       (let [c @check]
         (cond
           (= :checking c) [:p.muted.key-status (t :chat/key-checking)]
           (= :ok c) [:p.key-status.ok (t :chat/key-ok)]
           (map? c) [:p.key-status.bad
                     (let [e (:error c) st (:status (ex-data e))]
                       (cond (#{401 403} st) (t :chat/key-rejected)
                             (= 404 st) (t :chat/key-not-found)
                             st (t :chat/key-http {"{s}" st})
                             ;; the budget refusal speaks for itself
                             (seq (ex-message e)) (if (= "Failed to fetch" (ex-message e)) (t :chat/key-unreachable) (ex-message e))
                             :else (t :chat/key-unreachable)))]))])))

;; --- the ontology interview (wiki/Profiles.md §Competency questions) -------
;; Participatory prompting (OntoChat, arXiv 2408.15256): the assistant asks,
;; the author answers — who they are, what the graph is FOR, scenarios, data
;; examples. Out of it: a user story, COMPETENCY QUESTIONS as cards, and the
;; profile derived from the accepted ones — every relation born justified
;; by a question. Gaps are then the fold's to compute, never the model's.

(def ^:private max-asks 4)
(def ^:private min-asks 2)   ; one rich answer is not yet the whole picture

(defn- ontology-instruction [asks]
  (str
   ;; i18n-ignore
   "Ты помогаешь куратору спроектировать онтологию графа знаний (типы узлов и типы связей)." "\n"
   ;; i18n-ignore
   "Метод — вопросы компетенции: граф обязан отвечать на вопросы, которые куратору важны." "\n"
   ;; i18n-ignore
   "Сначала выясни: кто пользователь графа (агент-программист, новички, поддержка…), для чего граф," "\n"
   ;; i18n-ignore
   "2–3 сценария использования и примеры данных. Спрашивай по ОДНОМУ вопросу за раз." "\n"
   ;; business dogfood 2026-10-15: one rich first answer and the model proposed at
   ;; once — the ontology had no type for the systems and documents the author named
   ;; i18n-ignore
   "Прежде чем предлагать, проверь, что словарь покрывает то, чего касается работа: люди и команды," "\n"
   ;; i18n-ignore
   "системы и инструменты, документы и записи, правила и пороги, метрики — о том, чего не знаешь, спроси." "\n"
   ;; i18n-ignore
   "Отвечай ТОЛЬКО валидным JSON без markdown. Пока чего-то не хватает: {\"ask\":\"один вопрос\"}." "\n"
   ;; i18n-ignore
   "Когда достаточно (или тебя просят закончить) — предложи:" "\n"
   ;; i18n-ignore
   "{\"title\":\"название онтологии\",\"story\":\"история пользователя в 3–5 предложениях: кто, зачем, сценарии\"," "\n"
   ;; i18n-ignore
   " \"node_types\":[{\"id\":\"slug\",\"label\":\"как называется\",\"description\":\"одно предложение: что такое узел этого типа\"}]," "\n"
   ;; i18n-ignore
   " \"cqs\":[{\"question\":\"вопрос компетенции, например «Кто выполняет процесс X?»\"," "\n"
   ;; i18n-ignore
   "          \"relation\":{\"id\":\"slug\",\"label\":\"читается как\",\"inverse\":\"обратно читается как\",\"from\":\"id типа\",\"to\":\"id типа\",\"description\":\"одно предложение: что значит связь и в какую сторону читается\"}," "\n"
   ;; i18n-ignore
   "          \"asks_from\":\"id типа узла, О КОТОРОМ спрашивают (X в примере)\"}]," "\n"
   ;; i18n-ignore
   " \"ordering\":\"id связи, задающей порядок (что идёт за чем; from идёт ПОСЛЕ to) — ровно одна, между узлами одного типа\"," "\n"
   ;; i18n-ignore
   " \"note\":\"короткий комментарий\"}" "\n"
   ;; i18n-ignore
   "Каждая связь должна отвечать хотя бы на один вопрос; 5–12 вопросов, 3–8 типов. Не используй id requires, generalizes, is-confused-with, part-of, see-also."
   (cond
     (>= asks max-asks)
     ;; i18n-ignore
     "\nВопросов больше НЕ задавай — предложи онтологию по тому, что уже известно."
     (< asks min-asks)
     ;; i18n-ignore
     (str "\nЗадано вопросов: " asks ". Пока их меньше " min-asks ", не предлагай — спроси (если только тебя не просят закончить)."))))

(defn- transcript []
  (str/join "\n" (for [m @msgs :when (#{:user :llm} (:who m))]
                   (str (if (= :user (:who m)) "user: " "assistant: ") (:text m)))))

(defn start-ontology-interview!
  "A fresh sitting in a graph with no ontology yet: the assistant opens."
  [graph-id]
  (when (new-conversation!)
  (reset! graph graph-id)
  (reset! onto {:graph graph-id :asks 0 :accepted []})
  (swap! msgs conj {:who :llm :text (t :chat/onto-opening)})))

(defn- cq-card [i {:keys [question relation asks_from]}]
  {:card :cq
   :data {:id (str "cq-" (random-uuid) "-" i) :question question
          :relation (cond-> {:id (keyword (:id relation)) :label (or (:label relation) (:id relation))}
                      (seq (:inverse relation)) (assoc :inverse (:inverse relation))
                      (seq (:description relation)) (assoc :description (:description relation))
                      (seq (:from relation)) (assoc :from #{(keyword (:from relation))})
                      (seq (:to relation)) (assoc :to #{(keyword (:to relation))}))
          :asks-from (some-> asks_from keyword)}})

(defn- ontology-turn! [home text]
  (swap! msgs conj {:who :user :text text})
  (reset! busy true)
  (-> (p/let [raw (llm/chat! (str (ontology-instruction (:asks @onto)) (answer-language))
                             ;; i18n-ignore
                             (str "Диалог:\n" (transcript)))]
        (when (store/append-to? home)
          (let [ops (parse-reply raw)]
            (if (and (seq (:ask ops)) (< (:asks @onto) max-asks) (empty? (:cqs ops)))
              (do (swap! onto update :asks inc)
                  (swap! msgs conj {:who :llm :text (:ask ops)}))
              (let [cqs (filter #(and (seq (:question %)) (seq (get-in % [:relation :id]))) (:cqs ops))]
                (swap! onto assoc :story (:story ops) :title (:title ops)
                       :node-types (vec (for [t (:node_types ops) :when (seq (:id t))]
                                          (cond-> {:id (keyword (:id t)) :label (or (:label t) (:id t))}
                                            (seq (:description t)) (assoc :description (:description t)))))
                       :ordering (some-> (:ordering ops) keyword))
                (swap! msgs conj {:who :llm :text (str (:story ops) (when (seq (:note ops)) (str "\n\n" (:note ops))))})
                (when (empty? @cards) (reset! decided 0))
                (swap! cards (fn [cs] (-> (vec (remove #(= :profile-next (:card %)) cs))
                                          (into (map-indexed cq-card cqs))
                                          (conj {:card :profile-next :data {:id "profile-next"}})))))))))
      (p/catch (fn [e] (swap! msgs conj {:who :sys :text (str (t :chat/error-prefix) (ex-message e))})))
      (p/finally #(reset! busy false))))

(defn accept-cq! [c]
  (swap! onto update :accepted conj (:data c))
  (resolve-card! c))

(defn- derive-profile
  "The profile the accepted questions imply: their relations (deduped by
   id), the node types they name, one ordering relation. Labels come from
   the model's node_types list; a type only a relation names gets its id."
  [{:keys [graph title story accepted node-types ordering]}]
  (let [rels (vals (into {} (map (fn [{:keys [relation]}] [(:id relation) relation])) accepted))
        named (into {} (map (juxt :id identity)) node-types)
        type-ids (distinct (concat (map :id node-types) (mapcat #(concat (:from %) (:to %)) rels)))
        ;; the ordering relation is OPTIONAL (2026-11-19): the model's choice when
        ;; it was accepted, else one between nodes of one type, else none — «the
        ;; first relation» made «role performs process» the order, and the map
        ;; stacked every node in one column (real-model run 2026-11-20)
        ord (or (when (some #(= ordering (:id %)) rels) ordering)
                (:id (first (filter #(and (seq (:from %)) (= (:from %) (:to %))) rels))))]
    {:id (str "onto-" graph) :title (or title story "") :brief (or story "")
     :node-types (vec (for [id type-ids] (merge {:id id :label (name id)} (select-keys (named id) [:label :description]))))
     :relations (vec (for [r rels] (cond-> (select-keys r [:id :label :inverse :from :to :description])
                                     (= ord (:id r)) (assoc :ordering? true))))}))

(defn compose-profile!
  "The accepted competency questions become an ONTOLOGY GRAPH (the interview
   door, 2026-11-20): «Онтология: …» with the types and relations they imply
   and one `cq-raised` per question, so every relation is born justified;
   the graph the interview ran in is bound to it, and what the author told
   the interview is then read as that graph's content — node and link cards
   through the usual two-step intake. Refused (shown) when the vocabulary
   is unsound."
  []
  (let [o @onto
        home (:graph o)
        p (derive-profile o)]
    (cond
      (empty? (:accepted o)) (swap! msgs conj {:who :sys :text (t :chat/onto-accept-first)})
      (profile/problems p) (swap! msgs conj {:who :sys :text (str (t :chat/onto-problems) (pr-str (profile/problems p)))})
      (not (store/append-to? home)) (refuse-foreign!)
      :else
      (when-let [oid (store/new-ontology-graph! (str (t :ui/ontology-of) " " (or (not-empty (:title o)) (store/graph-title home))) p)]
        (doseq [{:keys [question relation asks-from]} (:accepted o)]
          (let [inverse? (boolean (and asks-from (contains? (:to relation) asks-from)
                                       (not (contains? (:from relation) asks-from))))]
            (store/append! {:ev/type :cq-raised
                            :cq (cond-> {:id (str "cq-" (random-uuid)) :question question
                                         :pattern (cond-> {:relation (:id relation)}
                                                    asks-from (assoc :from-type asks-from)
                                                    inverse? (assoc :inverse? true))})})))
        (swap! cards (fn [cs] (vec (remove #(#{:profile-next :cq} (:card %)) cs))))
        (store/switch-graph! home)
        (if-let [err (store/bind! home oid)]
          (do (reset! store/last-error err) (reset! onto nil))
          (let [answers (str/join "\n\n" (for [m @msgs :when (= :user (:who m))] (:text m)))]
            (swap! msgs conj {:who :sys :text (t :chat/onto-done {"{title}" (store/graph-title oid)
                                                                   "{t}" (count (:node-types p)) "{r}" (count (:relations p))
                                                                   "{q}" (count (:accepted o))})})
            (reset! onto nil)
            ;; the answers named real things (people, systems, steps): read them as content
            (when (seq answers)
              (content-intake! home {:name (t :chat/interview-answers) :text answers} nil))))))))

(defn- rel-label
  "A relation named on a card: the dictionary's word in learning, the
   profile's own in a custom ontology."
  [type]
  (let [p (store/profile)]
    (if (profile/learning? p) (i18n/enum-label "ename" type) (profile/label p type))))

;; --- gaps the competency questions leave (wiki/Profiles.md §CQ, slice c) ---
;; The fold computes which nodes leave which questions unanswered; the
;; assistant's part is to LOOK for the answer — in the node's source when it
;; has one, else among the nodes that already exist — and propose it as
;; cards. The model never declares a gap closed; an accepted edge does.

(defn- node-source
  "The source id a node is grounded in, if any."
  [kg id]
  (some #(when (and (= id (:from %)) (= :grounded-in (:type %))) (:to %)) (:edges kg)))

(defn- gap-instruction [kg node {:keys [question pattern]} source-text]
  (let [p (fold/profile kg)
        r (profile/relation p (:relation pattern))
        inverse? (:inverse? pattern)
        ;; the type of the node that would answer: the relation's other end
        want (if inverse? (:from r) (:to r))
        cands (for [[id c] (:concepts kg) :when (and (not= id (:id node)) (or (empty? want) (want (:kind c))))]
                (str id " — " (:title c)))]
    (str
     ;; i18n-ignore
     "Ты помощник куратора базы знаний. Онтология: " (:title p) ". Узел «" (:title node) "» (" (name (or (:kind node) "node")) ")"
     ;; i18n-ignore
     " не отвечает на вопрос компетенции: «" question "».\n"
     ;; i18n-ignore
     "Ответ — связь " (name (:id r)) " («" (:label r) "»"
     ;; i18n-ignore
     (when (seq want) (str "; с узлом типа " (str/join "|" (map name want)))) "): "
     (if inverse?
       ;; i18n-ignore
       (str "{from: ОТВЕТ, to: \"" (:id node) "\"}")
       ;; i18n-ignore
       (str "{from: \"" (:id node) "\", to: ОТВЕТ}"))
     "\n"
     ;; i18n-ignore
     "Существующие узлы, которые могут быть ответом (используй их id): " (if (seq cands) (str/join "; " cands) "нет") "\n"
     (if source-text
       ;; i18n-ignore
       "Ниже источник, на котором основан узел. Ответ бери ТОЛЬКО из него: существующий узел — в edges; если ответа в базе нет, а в источнике есть — новый узел в concepts с цитатой-основанием и связь к нему."
       ;; i18n-ignore
       "Источника нет: предлагай ТОЛЬКО существующие узлы; ничего не выдумывай.")
     "\n"
     ;; i18n-ignore
     "Отвечай ТОЛЬКО валидным JSON без markdown:\n"
     "{\"edges\":[{\"from\":\"id\",\"to\":\"id\",\"why\":\"…\",\"stated\":true}],"
     ;; i18n-ignore
     " \"concepts\":[{\"id\":\"slug\",\"type\":\"" (name (or (first want) (:kind node) "node")) "\",\"title\":\"…\",\"definition\":\"…\",\"evidence\":\"цитата\",\"stated\":true}],"
     ;; business dogfood 2026-10-15: half the answers were the model's inference,
     ;; said only in the note — the card must carry it
     ;; i18n-ignore
     "\n stated: true — источник говорит это прямо; false — это твой вывод (тогда объясни в why)."
     ;; i18n-ignore
     " \"note\":\"одна строка: что нашёл или что в источнике ответа нет\"}"
     (when source-text
       ;; i18n-ignore
       (str "\n\nИсточник:\n" (fold/source-window source-text [(:evidence node) (:title node)] 15000))))))

(defn fill-gap!
  "Look for the answer to one unanswered question of one node: in its
   source when it has one, else among existing nodes → proposal cards."
  [node-id {:keys [id question pattern] :as cq}]
  ;; another graph's cards are never claimed for this one (review 2026-10-16 A9)
  (if (foreign?) (refuse-claim!)
  (when-not @busy
    (let [home @store/current
          kg (store/kg)
          node (get-in kg [:concepts node-id])
          src (node-source kg node-id)
          r (profile/relation (fold/profile kg) (:relation pattern))]
      (when (and node r)
        (reset! busy true)
        (reset! graph home)
        (swap! msgs conj {:who :user :text (t :chat/gap-ask {"{node}" (:title node) "{q}" question})})
        (-> (p/let [text (when src (-> (blobs/get-text (str src ".md")) (p/catch (fn [_] nil))))
                    raw (llm/chat! (str (gap-instruction kg node cq text) (content-language) (answer-language))
                                   ;; i18n-ignore
                                   "Найди ответ.")]
              (when (store/append-to? home)
                (let [ops (parse-reply raw)
                      want-type (keyword (name (or (first (if (:inverse? pattern) (:from r) (:to r))) (:kind node) "node")))
                      ;; new nodes: the wanted type, grounded in the source the answer came from
                      ;; the CARD carries the typed, grounded node too: accept
                      ;; builds its payload from the card, and the raw model map
                      ;; was refused untyped or accepted ungrounded (review
                      ;; 2026-10-16 A21)
                      ok-c (filterv #(nil? (store/append!
                                            {:ev/type :concept-proposed
                                             :concept (concept-payload %)
                                             :provenance :llm-drafted}))
                                    (for [c (:concepts ops) :when (seq (:id c))]
                                      (cond-> (assoc c :type (name want-type))
                                        (and src text) (assoc :source-id src))))
                      ;; the edge always has the node at the end the question fixes
                      edges (for [e (:edges ops)
                                  :let [other (if (:inverse? pattern) (:from e) (:to e))]
                                  :when (and (seq other) (not= other node-id))]
                              (cond-> (if (:inverse? pattern)
                                        {:from other :to node-id :type (name (:id r)) :why (:why e)}
                                        {:from node-id :to other :type (name (:id r)) :why (:why e)})
                                (false? (:stated e)) (assoc :stated false)))
                      ok-e (filterv #(nil? (store/append!
                                            {:ev/type :edge-proposed
                                             :edge (cond-> {:from (:from %) :to (:to %) :type (:id r)
                                                            :provenance :llm-drafted :justification (:why %)}
                                                     (false? (:stated %)) (assoc :inferred? true))}))
                                    edges)]
                  (reset! store/last-error nil)
                  (swap! msgs conj {:who :llm :text (or (:note ops) (if (or (seq ok-c) (seq ok-e)) (t :chat/gap-found) (t :chat/gap-nothing)))})
                  (when (empty? @cards) (reset! decided 0))
                  (swap! cards into (concat (ops->cards {:concepts ok-c})
                                            (for [e ok-e] {:card :edge :data e}))))))
            (p/catch (fn [e] (swap! msgs conj {:who :sys :text (str (t :chat/error-prefix) (ex-message e))})))
            (p/finally #(reset! busy false))))))))

(def ^:private gap-rows 6)

(defn- gaps-card
  "Pinned above the pile in a custom graph while any node leaves a question
   unanswered — computed by the fold at render, never stored."
  []
  (let [kg (store/kg)
        gaps (fold/cq-gaps kg)]
    (when (and (seq gaps) (not (profile/learning? (fold/profile kg))))
      (let [rows (for [[nid qs] (sort-by (fn [[nid]] (get-in kg [:concepts nid :title])) gaps) q qs] [nid q])
            n-q (count (distinct (map (comp :id second) rows)))]
        [:div.card.card-build.card-gaps
         [:span.card-tag (t :chat/gaps-tag)]
         [:p (t :chat/gaps-lead {"{n}" (count gaps) "{m}" n-q})]
         (for [[nid q] (take gap-rows rows)]
           ^{:key (str nid (:id q))}
           [:div.gap-row
            [:a {:href (str "#/c/" (js/encodeURIComponent nid))} (get-in kg [:concepts nid :title] nid)]
            [:span.muted " — " (:question q)]
            (when (llm/ready?)
              [:button.gap-find {:disabled (or @busy (foreign?)) :on-click #(fill-gap! nid q)} (t :chat/find-answer)])])
         (when (> (count rows) gap-rows) [:p.muted (t :chat/gaps-more {"{n}" (- (count rows) gap-rows)})])]))))

;; --- adversarial competency questions (wiki/Profiles.md §CQ, slice d) ---
;; VSPO-style: the model is shown the vocabulary, the questions already
;; asked, a sample of nodes and the fold's mechanical pitfalls, and asked
;; for the questions a USER of this graph would ask that it cannot answer
;; yet — each with the relation that would answer it (an existing one, or a
;; new one, which joins the ontology additively on accept). Pitfalls it
;; sees come back as prose for the author; nothing changes by itself.

(defn- probe-instruction [kg]
  (let [p (fold/profile kg)
        by-kind (group-by :kind (vals (:concepts kg)))
        sample (for [t (profile/node-types p) :let [cs (take 5 (get by-kind (:id t)))] :when (seq cs)]
                 (str (name (:id t)) ": " (str/join ", " (map :title cs))))
        asked (map :question (vals (:cqs kg)))
        pits (fold/ontology-pitfalls kg)]
    (str
     ;; i18n-ignore
     "Ты проверяешь онтологию графа знаний на полноту и ошибки моделирования. Онтология: " (:title p)
     (when (seq (:brief p)) (str ". " (:brief p))) "\n"
     ;; i18n-ignore
     "Типы узлов:\n" (profile/node-type-lines p) "\n"
     ;; i18n-ignore
     "Связи (id — как читается; типы от → к):\n" (profile/relation-lines p) "\n"
     ;; i18n-ignore
     "Вопросы компетенции, которые граф уже обязан отвечать: " (if (seq asked) (str/join " | " asked) "нет") "\n"
     ;; i18n-ignore
     "Примеры узлов: " (if (seq sample) (str/join "; " sample) "граф пока пуст") "\n"
     (when (seq pits)
       ;; i18n-ignore
       (str "Что движок уже заметил: " (str/join "; " (map #(str (name (:kind %)) " " (pr-str (:detail %))) pits)) "\n"))
     ;; i18n-ignore
     "Задача: 1) предложи 3–6 НОВЫХ вопросов компетенции, которые пользователь этого графа задал бы, а словарь пока не может ответить" "\n"
     ;; i18n-ignore
     "(или отвечает, но вопрос ещё не записан). Для каждого — связь, которая на него отвечает: существующая (её id) или новая (id, как читается," "\n"
     ;; i18n-ignore
     "обратно, от какого типа к какому; типы — только из списка). 2) назови ошибки моделирования, если видишь: тип, который на деле" "\n"
     ;; i18n-ignore
     "другой тип; связь, которую используют в обе стороны; то, что лучше выразить иначе. Не предлагай id requires, generalizes, is-confused-with, part-of, see-also." "\n"
     ;; i18n-ignore
     "Отвечай ТОЛЬКО валидным JSON без markdown:" "\n"
     ;; i18n-ignore
     "{\"cqs\":[{\"question\":\"…\",\"relation\":{\"id\":\"slug\",\"label\":\"читается как\",\"inverse\":\"обратно\",\"from\":\"id типа\",\"to\":\"id типа\"},\"asks_from\":\"id типа узла, о котором спрашивают\",\"new\":true}]," "\n"
     ;; i18n-ignore
     " \"pitfalls\":[{\"what\":\"что не так\",\"fix\":\"что сделать\"}]," "\n"
     ;; i18n-ignore
     " \"note\":\"одна строка\"}")))

(defn probe-ontology!
  "Ask the model for the questions this ontology cannot answer yet and the
   pitfalls it sees → `:cq-draft` cards and a message. Author's tap only."
  []
  (if (foreign?) (refuse-claim!)
  (when-not @busy
    (let [home @store/current kg (store/kg)]
      (when-not (profile/learning? (fold/profile kg))
        (reset! busy true)
        (reset! graph home)
        (swap! msgs conj {:who :user :text (t :chat/probe-ask)})
        (-> (p/let [raw (llm/chat! (str (probe-instruction kg) (answer-language))
                                   ;; i18n-ignore
                                   "Проверь онтологию.")]
              (when (store/append-to? home)
                (let [ops (parse-reply raw)
                      cqs (filter #(and (seq (:question %)) (seq (get-in % [:relation :id]))) (:cqs ops))
                      pits (filter #(seq (:what %)) (:pitfalls ops))]
                  (swap! msgs conj {:who :llm :text (str (or (:note ops) "")
                                                         (when (seq pits)
                                                           (str "\n" (t :chat/probe-pitfalls) "\n"
                                                                (str/join "\n" (for [{:keys [what fix]} pits] (str "— " what (when (seq fix) (str " → " fix))))))))})
                  (when (empty? @cards) (reset! decided 0))
                  (swap! cards into (map-indexed (fn [i cq] (-> (cq-card i cq) (assoc :card :cq-draft) (assoc-in [:data :new?] (boolean (:new cq))))) cqs)))))
            (p/catch (fn [e] (swap! msgs conj {:who :sys :text (str (t :chat/error-prefix) (ex-message e))})))
            (p/finally #(reset! busy false))))))))

(defn accept-cq-draft!
  "A probed question joins the graph: its relation first when the ontology
   lacks it (and any node type it names), then the question — all through
   the guard, so a bad relation is refused where the author can see it."
  [{:keys [data] :as c}]
  (let [{:keys [question relation asks-from]} data
        p (store/profile)
        rel (profile/relation p (:id relation))
        rel (or rel (do (doseq [tid (concat (:from relation) (:to relation))
                              :when (nil? (profile/node-type-of p tid))]
                          (store/append! {:ev/type :node-type-added :node-type {:id tid :label (name tid)}}))
                        (when (nil? (store/append! {:ev/type :relation-added :relation relation}))
                          relation)))]
    (when rel
      (let [inverse? (boolean (and asks-from (contains? (:to rel) asks-from) (not (contains? (:from rel) asks-from))))]
        (when (nil? (store/append! {:ev/type :cq-raised
                                    :cq {:id (str "cq-" (random-uuid)) :question question
                                         :pattern (cond-> {:relation (:id rel)}
                                                    asks-from (assoc :from-type asks-from)
                                                    inverse? (assoc :inverse? true))}}))
          (resolve-card! c))))))

(defmulti card-view :card)

(defmethod card-view :cq-draft [{:keys [data] :as c}]
  (let [{:keys [question relation asks-from new?]} data
        p (store/profile)
        tname (fn [k] (or (profile/kind-label p k) (some-> k name)))]
    [:div.card.card-cq.card-cq-draft
     [:span.card-tag (str (t :chat/cq-tag) (when new? (str " · " (t :chat/cq-new-relation))))]
     [:b question]
     [:p.muted (str (name (:id relation)) " — " (:label relation)
                    (when (:inverse relation) (str " / " (:inverse relation)))
                    (when (seq (:from relation)) (str " · " (tname (first (:from relation))) " → " (tname (first (:to relation)))))
                    (when asks-from (str " · " (t :chat/cq-asks-from) " " (tname asks-from))))]
     (when-let [q (fold/similar-question (store/kg) question)]
       [:p.card-dup (t :chat/cq-like {"{q}" (:question q)})])
     [:div.row
      [:button.primary.cq-draft-accept {:disabled (foreign?) :on-click #(accept-cq-draft! c)} (t :chat/accept)]
      [:button {:disabled (foreign?) :on-click #(resolve-card! c)} (t :chat/discard)]]]))

(defmethod card-view :cq [{:keys [data] :as c}]
  (let [{:keys [question relation asks-from]} data
        tname (fn [k] (or (some #(when (= k (:id %)) (:label %)) (:node-types @onto)) (some-> k name)))]
    [:div.card.card-cq
     [:span.card-tag (t :chat/cq-tag)]
     [:b question]
     [:p.muted (str (name (:id relation)) " — " (:label relation)
                    (when (:inverse relation) (str " / " (:inverse relation)))
                    (when (seq (:from relation)) (str " · " (tname (first (:from relation))) " → " (tname (first (:to relation)))))
                    (when asks-from (str " · " (t :chat/cq-asks-from) " " (tname asks-from))))]
     [:div.row
      [:button.primary.cq-accept {:disabled (foreign?) :on-click #(accept-cq! c)} (t :chat/accept)]
      [:button {:disabled (foreign?) :on-click #(resolve-card! c)} (t :chat/discard)]]]))

(defmethod card-view :profile-next [_]
  (let [n (count (:accepted @onto))
        ;; composing removes every question card: an undecided one was lost in
        ;; silence — with it the ordering relation (real-model run 2026-11-20)
        waiting (count (filter #(= :cq (:card %)) @cards))]
    [:div.card.card-build.card-profile-next
     [:span.card-tag (t :chat/profile-next-tag)]
     [:p (t :chat/profile-next-lead {"{n}" n})]
     (cond
       (pos? waiting) [:p.muted.cq-waiting (t :chat/onto-decide-rest {"{n}" waiting})]
       (pos? n) [:button.primary.compose-profile {:disabled (or @busy (foreign?)) :on-click compose-profile!} (t :chat/compose-profile)]
       :else [:p.muted (t :chat/onto-accept-first)])]))

(defn derive-next!
  "The derived ontology's cards are decided: back to the graph the document
   was dropped in, bind it to the new ontology, read the document as content."
  [{:keys [data] :as c}]
  (let [{:keys [doc graph ontology]} data]
    (resolve-card! c)
    (when (and (or (= graph @store/current) (store/switch-graph! graph)) (new-conversation!))
      (if-let [err (store/bind! graph ontology)]
        (reset! store/last-error err)
        (content-intake! graph doc nil)))))

(defmethod card-view :derive-next [{:keys [data] :as c}]
  (let [open (count (filter #(#{:type :relation} (:card %)) @cards))
        whole? (store/whole-ontology? (:ontology data))]
    [:div.card.card-build.card-derive-next
     [:span.card-tag (t :chat/derive-tag)]
     [:p (t :chat/derive-lead {"{doc}" (get-in data [:doc :name]) "{graph}" (store/graph-title (:graph data))})]
     (cond (pos? open) [:p.muted (t :chat/derive-wait {"{n}" open})]
           (not whole?) [:p.muted (t :ui/error-no-whole-ontology)])
     [:button.primary.derive-go {:disabled (or @busy (foreign?) (pos? open) (not whole?)) :on-click #(derive-next! c)}
      (t :chat/derive-go)]]))

(defmethod card-view :relation-draft [{:keys [data] :as c}]
  (let [waiting (count (filter #(= (:id data) (:waits-relation %)) @cards))]
    [:div.card.card-relation-draft
     [:span.card-tag (t :chat/relation-draft-tag)]
     [:b (t :chat/relation-draft-q {"{rel}" (:label data) "{onto}" (store/graph-title (:graph data))})]
     (when (seq (:examples data)) [:p.muted (t :chat/type-draft-examples {"{list}" (str/join "; " (:examples data))})])
     (when (pos? waiting) [:p.muted (t :chat/relation-draft-waiting {"{n}" waiting})])
     [:div.row
      [:button.primary.relation-draft-accept {:disabled (or (foreign?) (:refused c)) :on-click #(accept-relation-draft! c)} (t :chat/type-draft-add)]
      [:button {:disabled (foreign?) :on-click #(resolve-card! c)} (t :chat/discard)]]]))

(defmethod card-view :type-draft [{:keys [data] :as c}]
  (let [waiting (count (filter #(= (:id data) (:waits-type %)) @cards))]
    [:div.card.card-type-draft
     [:span.card-tag (t :chat/type-draft-tag)]
     [:b (t :chat/type-draft-q {"{type}" (:title data) "{onto}" (store/graph-title (:graph data))})]
     (when (seq (:examples data)) [:p.muted (t :chat/type-draft-examples {"{list}" (str/join ", " (:examples data))})])
     (when (pos? waiting) [:p.muted (t :chat/type-draft-waiting {"{n}" waiting})])
     [:div.row
      [:button.primary.type-draft-accept {:disabled (or (foreign?) (:refused c)) :on-click #(accept-type-draft! c)} (t :chat/type-draft-add)]
      [:button {:disabled (foreign?) :on-click #(resolve-card! c)} (t :chat/discard)]]]))

(defn- conflict-what
  "«title» of a node, or of a link — what a conflict is about."
  [kg [ent k]]
  (let [nt #(get-in kg [:concepts % :title] %)]
    (case (first ent)
      :node (t :chat/conflict-prop {"{key}" (name k) "{node}" (nt (second ent))})
      :edge (let [[_ a ty b] ent] (t :chat/conflict-prop {"{key}" (name k) "{node}" (str (nt a) " —" (name ty) "→ " (nt b))}))
      :graph (t :chat/conflict-prop {"{key}" (name k) "{node}" (t :chat/conflict-graph)}))))

(defmethod card-view :conflict [{c :data}]
  (let [kg (store/kg)
        nt #(get-in kg [:concepts % :title] %)
        btn (fn [cls choice label] [:button {:class cls :on-click #(sync/resolve! c choice)} label])]
    [:div.card.card-conflict
     [:span.card-tag.tag-conflict (t :chat/conflict-tag)]
     (case (:kind c)
       (:same-property :overridden)
       [:<>
        (for [[ek {:keys [theirs mine]}] (:clash c)]
          ^{:key (pr-str ek)}
          [:div.conflict-row
           [:b (conflict-what kg ek)]
           [:p (t :chat/conflict-theirs) " " [:span.conflict-val (str theirs)]]
           [:p (t :chat/conflict-mine) " " [:span.conflict-val (str mine)]]])
        [:p.muted (t (if (= :overridden (:kind c)) :chat/conflict-overridden-lead :chat/conflict-same-lead))]
        [:div.row
         (btn "primary conflict-mine" :mine (t :chat/conflict-keep-mine))
         (btn "conflict-theirs" :theirs (t :chat/conflict-keep-theirs))]]
       :removed
       [:<>
        [:b (t :chat/conflict-removed {"{what}" (clojure.string/join ", " (for [[kind id] (:gone c)] (if (= :node kind) (nt id) (name kind))))})]
        [:div.row
         (btn "primary conflict-mine" :mine (t :chat/conflict-restore))
         (btn "conflict-theirs" :theirs (t :chat/conflict-accept-removal))]]
       [:<>
        [:b (t :chat/conflict-refused)]
        [:p.muted (pr-str (:error c))]
        [:div.row (btn "conflict-theirs" :theirs (t :chat/conflict-drop-mine))]])]))

(defmethod card-view :triage [{:keys [data] :as c}]
  (let [{:keys [doc what ontology]} data
        choose! (fn [k v] (swap! cards (fn [cs] (mapv #(if (= % c) (assoc-in % [:data k] v) %) cs))))
        choices (concat ["learning"] (map :id (filter #(store/whole-ontology? (:id %)) (store/ontology-graphs))) ["derive"])]
    [:div.card.card-triage
     [:span.card-tag (t :chat/triage-tag)]
     [:b (t :chat/triage-q {"{name}" (:name doc)})]
     [:label.triage-opt [:input.triage-ontology {:type "radio" :checked (= :ontology what) :on-change #(choose! :what :ontology)}]
      " " (t :chat/triage-ontology)]
     [:label.triage-opt [:input.triage-content {:type "radio" :checked (= :content what) :on-change #(choose! :what :content)}]
      " " (t :chat/triage-content)]
     (when (= :content what)
       [:label.triage-onto (t :chat/triage-which)
        [:select.triage-which {:value ontology :on-change #(choose! :ontology (.. % -target -value))}
         (for [o choices]
           ^{:key o} [:option {:value o} (case o "learning" (t :chat/triage-learning) "derive" (t :chat/triage-derive) (store/graph-title o))])]])
     [:div.row
      [:button.primary.triage-go {:disabled (or @busy (foreign?)) :on-click #(triage-go! c)} (t :chat/triage-go)]
      [:button {:disabled (foreign?) :on-click #(resolve-card! c)} (t :chat/discard)]]]))

(defmethod card-view :type [{:keys [data] :as c}]
  [:div.card.card-type
   [:span.card-tag (t :chat/type-tag)]
   [:b (:title data)] [:p (:definition data)]
   (when (seq (:evidence data)) [:p.muted "«" (:evidence data) "»"])
   (when (get-in (store/kg) [:concepts (:id data)]) [:p.card-dup (t :chat/vocab-exists)])
   [:div.row
    [:button.primary.type-accept {:disabled (or (foreign?) (:refused c)) :on-click #(accept! c)} (t :chat/accept)]
    [:button {:disabled (foreign?) :on-click #(resolve-card! c)} (t :chat/discard)]]])

(defmethod card-view :relation [{:keys [data] :as c}]
  [:div.card.card-relation
   [:span.card-tag (t :chat/relation-tag)]
   [:b (str (:from-title data) " —" (:label data) "→ " (:to-title data))]
   (when (seq (:inverse data)) [:p.muted (str (:to-title data) " —" (:inverse data) "→ " (:from-title data))])
   (when (seq (:description data)) [:p (:description data)])
   (when (seq (:evidence data)) [:p.muted "«" (:evidence data) "»"])
   [:div.row
    [:button.primary.relation-accept {:disabled (or (foreign?) (:refused c)) :on-click #(accept! c)} (t :chat/accept)]
    [:button {:disabled (foreign?) :on-click #(resolve-card! c)} (t :chat/discard)]]])

(defmethod card-view :concept [{:keys [data] :as c}]
  [:div.card
   [:span.card-tag (let [p (store/profile)]
                     (if (or (profile/learning? p) (nil? (:type data)))
                       (t :chat/concept-tag)
                       (profile/kind-label p (keyword (:type data)))))]
   (when (false? (:stated data)) [:span.card-tag.tag-inferred (t :chat/inferred)])
   [:b (:title data)] [:p (:definition data)]
   (when (seq (:evidence data)) [:p.muted "«" (:evidence data) "»"])
   (when-let [d (fold/near-duplicate (store/kg) data)]
     [:p.card-dup (t :chat/looks-like {"{title}" (:title d)})])
   (when (or (seq (:objects c)) (seq (:questions c)))
     [:div.card-drafts
      (for [[k tag text] [[:objects #(str (t :chat/object-tag) " · " (i18n/enum-label "okind" (keyword (or (:kind %) "explanation")))) :text]
                          [:questions (constantly (t :chat/question-short)) :question]]
            [j x] (map-indexed vector (get c k))]
        ^{:key (str (name k) j)}
        [:label.draft {:class (when-not (:on? x) "off")}
         [:input {:type "checkbox" :checked (boolean (:on? x))
                  :on-change #(toggle-draft! c k j)}]
         [:span [:span.card-tag (tag x)] (if (= k :objects) (fold/marks->hiccup (text x) true) (text x))]])])
   [:div.row
    [:button.primary {:disabled (or (foreign?) (:refused c)) :on-click #(accept! c)} (t :chat/accept)]
    [:button {:disabled (foreign?) :on-click #(discard! c)} (t :chat/discard)]]])

(defmethod card-view :edge [{:keys [data] :as c}]
  [:div.card
   [:span.card-tag (t :chat/edge-tag)]
   (when (false? (:stated data)) [:span.card-tag.tag-inferred (t :chat/inferred)])
   (let [title #(or (get-in (store/kg) [:concepts % :title]) %)]
     [:b (str (title (:from data)) " —" (rel-label (keyword (or (:type data) (default-relation)))) "→ " (title (:to data)))])
   (when (:why data) [:p (:why data)])
   (when (seq (:signals data)) [:p.muted (str (t :chat/engine-prefix) (str/join "; " (:signals data)))])
   [:div.row
    [:button.primary {:disabled (or (foreign?) (:refused c)) :on-click #(accept! c)} (t :chat/accept)]
    [:button {:disabled (foreign?) :on-click #(discard! c)} (t :chat/discard)]]])

(defn- topic-name
  "A card names its topic by title, never by id («→ cte»): the graph's, else
   a topic proposed in this sitting, else the id."
  [id]
  (or (get-in (store/kg) [:concepts id :title])
      (some #(when (= id (get-in % [:data :id])) (get-in % [:data :title])) @cards)
      id))

(defmethod card-view :object [{:keys [data] :as c}]
  [:div.card
   [:span.card-tag (str (t :chat/object-tag) " → " (topic-name (:concept data)) " · "
                        (i18n/enum-label "okind" (keyword (or (:kind data) "explanation"))))]
   [:p (fold/marks->hiccup (:text data) true)]
   [:div.row
    [:button.primary {:disabled (or (foreign?) (:refused c)) :on-click #(accept! c)} (t :chat/accept-draft)]
    [:button {:disabled (foreign?) :on-click #(discard! c)} (t :chat/discard)]]])

(defmethod card-view :exercise [{:keys [data] :as c}]
  (let [{:keys [spec rejected concept]} data]
    [:div.card.card-ex
     [:span.card-tag (str (t :chat/exercise-tag) " → " (or (get-in (store/kg) [:concepts concept :title]) concept)
                          " · " (if (= :python (ex/runtime spec)) "Python" "SQL")
                          " · " (case (ex/check-kind spec)
                                  :assert (t :ui/exercise-check-assert)
                                  :output (t :ui/exercise-check-output)
                                  (t :chat/check-rows)))]
     [:p (:stem spec)]
     [:pre.ex-code (:solution spec)]
     (when (= :assert (ex/check-kind spec)) [:pre.ex-code.ex-test (:test spec)])
     [:p.muted.verified (t :chat/exercise-verified {"{n}" rejected})]
     [:div.row
      [:button.primary {:disabled (or (foreign?) (:refused c)) :on-click #(accept! c)} (t :chat/accept)]
      [:button {:disabled (foreign?) :on-click #(discard! c)} (t :chat/discard)]]]))

(defmethod card-view :version-of [{:keys [data] :as c}]
  (let [{:keys [new-title candidates pick]} data
        old (or pick (ffirst candidates))
        old-title (some (fn [[id title]] (when (= id old) title)) candidates)]
    [:div.card.card-version
     [:span.card-tag (t :chat/version-tag)]
     [:p (t :chat/version-q {"{new}" new-title "{old}" old-title})]
     (when (> (count candidates) 1)
       [:select.version-pick {:value old :on-change #(pick-version! c (.. % -target -value))}
        (for [[id title] candidates] ^{:key id} [:option {:value id} title])])
     [:div.row
      [:button.primary.version-yes {:disabled (foreign?) :on-click #(version-answer! c true)} (t :chat/version-yes)]
      [:button.version-no {:disabled (foreign?) :on-click #(version-answer! c false)} (t :chat/version-no)]]]))

(defmethod card-view :reversion-kept [{:keys [data] :as c}]
  (let [kg (store/kg) {:keys [concepts]} data]
    [:div.card.card-reversion-kept
     [:span.card-tag (t :chat/reversion-tag)]
     [:p (t :chat/reversion-kept {"{n}" (count concepts)})]
     [:p.muted (str/join " · " (map #(get-in kg [:concepts % :title] %) (take 8 concepts)))
      (when (> (count concepts) 8) " …")]
     [:div.row
      [:button.primary.reversion-kept-patch {:disabled (foreign?) :on-click #(kept-answer! c false)} (t :chat/reversion-kept-patch)]
      [:button.reversion-kept-split {:disabled (foreign?) :on-click #(kept-answer! c true)} (t :chat/reversion-kept-split)]]]))

(defmethod card-view :reversion [{:keys [data] :as c}]
  (let [{:keys [source concept changed? quote]} data
        kg (store/kg)
        objs (filter #(and (= source (:grounded-in %)) (= :active (:status %)) (= concept (:concept-id %))) (vals (:objects kg)))]
    [:div.card.card-reversion {:data-concept concept :class (when changed? "quote-changed")}
     [:span.card-tag (t :chat/reversion-tag)]
     [:b (get-in kg [:concepts concept :title] concept)]
     (when changed?
       [:p.reversion-changed (t :chat/reversion-changed-quote {"{q}" (let [x (str quote)] (if (> (count x) 200) (str (subs x 0 200) "…") x))})])
     (for [o objs] ^{:key (:id o)} [:p.muted (let [x (fold/plain-text (:text o))] (if (> (count x) 160) (str (subs x 0 160) "…") x))])
     [:p (t :chat/reversion-q)]
     [:div.row
      [:button.reversion-patch {:disabled (foreign?) :on-click #(reversion-answer! c :patch)} (t :chat/reversion-patch)]
      [:button.primary.reversion-material {:disabled (foreign?) :on-click #(reversion-answer! c :material)} (t :chat/reversion-material)]]]))

(defmethod card-view :action [{:keys [data] :as c}]
  [:div.card.card-action
   [:span.card-tag (t :chat/action-tag)]
   [:div.row
    [:button.primary.action-do {:disabled (foreign?)
                                :on-click (fn [_]
                                            (resolve-card! c)
                                            (tools/run-action! {:kg (store/kg) :graph-id @store/current} data))}
     (:label data)]
    [:button.action-hide {:on-click #(resolve-card! c)} (t :chat/action-hide)]]])

(defmethod card-view :item-draft [{:keys [data] :as c}]
  (let [{:keys [concept stem options key]} data]
    [:div.card.card-item-draft
     [:span.card-tag (str (t :chat/question-draft-tag) " → " (or (get-in (store/kg) [:concepts concept :title]) concept))]
     [:p [:b stem]]
     [:ol.q-options (for [[i o] (map-indexed vector options)]
                      ^{:key i} [:li {:class (when (= i key) "right")} o (when (= i key) " ✓")])]
     [:div.row
      [:button.primary {:disabled (foreign?) :on-click #(accept-question! c)} (t :chat/accept)]
      [:button {:disabled (foreign?) :on-click #(discard! c)} (t :chat/discard)]]]))

(defmethod card-view :build-next [{:keys [data] :as c}]
  (let [kg (store/kg) locale (store/locale-for-new)
        needed (questions-needed kg locale)
        ready (ready-topics kg locale)]
    [:div.card.card-build
     [:span.card-tag (t :chat/build-tag)]
     [:p (t :chat/build-lead {"{goal}" (:goal data)})]
     ;; a course from the topics that are ready, while the rest get questions
     (if (or (seq needed) (seq ready))
       [:div.row
        (when (seq ready)
          [:button.primary.build-course {:disabled (or @busy (foreign?)) :on-click #(compose-course! c)}
           (t :chat/build-course {"{n}" (count ready)})])
        (when (seq needed)
          [:button.build-questions {:class (when (empty? ready) "primary") :disabled (or @busy (foreign?)) :on-click #(draft-questions! needed)}
           (t :chat/build-questions {"{n}" (count needed)})])]
       [:p.muted (t :chat/build-accept-first)])]))

(defn- reread-steps!
  "Read just the missing steps' sections again, with their list as the
   step context; earlier proposals are named so they are not repeated. The
   document comes from the blob store and the missing steps are recounted
   — another re-read may have filled some."
  [{:keys [data] :as c}]
  (when-not (or @busy (foreign?))
    (let [{:keys [name source-id parent parent-at]} data
          home @store/current
          kg (store/kg)
          prior (filter #(= :concept (:of %)) (vals (:proposals kg)))
          p (store/profile)
          ord (profile/relation p (profile/ordering-relation p))
          kinds (when-not (profile/learning? p) (seq (filter (set (:to ord)) (:from ord))))
          ;; the intake's «не повторяй» won over a plain «дай узел» (A9: the
          ;; model filed 2.B, 4.B and 5 as material on nodes of ANOTHER list,
          ;; and «Final buyer review» on the first buyer review) — a step of
          ;; this list is its own node
          framing (fn [steps]
                    ;; i18n-ignore — the material's framing for the model, not shown
                    (str "Это шаги нумерованного списка «" parent "», у которых в базе нет своего узла-шага: "
                         ;; i18n-ignore
                         (str/join ", " (map step-label steps)) ". Дай КАЖДОМУ шагу отдельный узел"
                         ;; i18n-ignore
                         (when kinds (str " типа " (str/join " или " (map clojure.core/name kinds))))
                         ;; i18n-ignore
                         ", названный по шагу, даже если похожий узел уже есть: тот описывает другое место"
                         ;; i18n-ignore
                         " материала, а шаг этого списка — отдельный узел. Подробности о существующих узлах — в objects."
                         ;; i18n-ignore
                         "\n\nМатериал из файла «" name "», раздел «" parent "»:\n"
                         (str/join "\n" (map :text steps))))
          give-back! (fn [] (swap! cards #(into [c] %)) (swap! decided dec))]
      (resolve-card! c)
      (reset! graph home)
      (reset! busy true)
      (reset! progress {:i 0 :n 1 :step :concepts})
      (-> (p/let [text (blobs/get-text (str source-id ".md"))
                  l (first (filter #(= parent-at (:parent-at %)) (missing-in text prior)))]
            (if-not l
              (swap! msgs conj {:who :sys :text (t :chat/missing-steps-none)})
              (do (swap! msgs conj {:who :sys :text (t :chat/missing-steps-reading {"{steps}" (str/join ", " (map step-label (:steps l)))})})
                  (p/let [r (intake-part! home {:name name :source-id source-id :steps-text (:list-text l)} (framing (:steps l)) prior
                                          #(swap! progress assoc :step %))]
                    (swap! msgs conj {:who :llm :text (t :chat/missing-steps-done {"{c}" (count (:concepts r))})})))))
          (p/catch (fn [e]
                     ;; a failed re-read gives the card back: it was the only
                     ;; way to ask again (review 2026-10-16)
                     (when-not (:wrong-graph (ex-data e)) (give-back!))
                     (swap! msgs conj {:who :sys :text (if (:wrong-graph (ex-data e))
                                                         (t :chat/wrong-graph)
                                                         (str (t :chat/error-prefix) (ex-message e)))})))
          (p/finally #(do (reset! busy false) (reset! progress nil)))))))

(defmethod card-view :missing-steps [{:keys [data] :as c}]
  [:div.card.card-missing-steps
   [:span.card-tag (t :chat/missing-steps-tag)]
   [:p (t :chat/missing-steps-lead {"{list}" (:parent data)})]
   [:ul.missing-steps (for [st (:steps data)] ^{:key (step-label st)} [:li (:title st)])]
   [:div.row
    [:button.primary.missing-steps-read {:disabled (or @busy (foreign?)) :on-click #(reread-steps! c)} (t :chat/missing-steps-read)]
    [:button.missing-steps-skip {:on-click #(resolve-card! c)} (t :chat/missing-steps-skip)]]])
;; --- merge-file cards (loretile.merge): an item of any kind, and a change
;; to something already in the graph — an edit (version), a removal, or any
;; other event, applied verbatim on accept

(defmethod card-view :item [{:keys [data] :as c}]
  (let [{:keys [primary-concept-id kind spec]} data]
    [:div.card.card-item
     [:span.card-tag (str (t :chat/item-tag) " → " (topic-name primary-concept-id)
                          " · " (i18n/enum-label "ikind" kind))]
     [:p [:b (or (:stem spec) (:text spec) (pr-str spec))]]
     (when-let [os (seq (:options spec))]
       [:ol.q-options (for [[i o] (map-indexed vector os)]
                        ^{:key i} [:li {:class (when (= i (:key spec)) "right")} o (when (= i (:key spec)) " ✓")])])
     [:div.row
      [:button.primary {:disabled (or (foreign?) (:refused c)) :on-click #(accept! c)} (t :chat/accept)]
      [:button {:disabled (foreign?) :on-click #(discard! c)} (t :chat/discard)]]]))

(defmethod card-view :change [{:keys [data] :as c}]
  (let [{:keys [kind title change lines concept]} data]
    [:div.card.card-change {:class (str "chg-" (name kind)) :data-concept concept}
     [:span.card-tag (str (t (case kind :edit :chat/change-tag :remove :chat/remove-tag :chat/other-tag))
                          (when change (t (if (= :material change) :chat/change-material :chat/change-patch))))]
     [:b title]
     (for [[i [k old new]] (map-indexed vector lines)]
       ^{:key i}
       [:p.chg-line (when (seq k) [:span.muted (str k ": ")])
        (when (seq old) [:s old]) (when (seq old) " → ") [:ins new]])
     [:div.row
      [:button.primary {:disabled (or (foreign?) (:refused c)) :on-click #(accept! c)} (t :chat/apply)]
      [:button {:disabled (foreign?) :on-click #(discard! c)} (t :chat/discard)]]]))

(defmethod card-view :question [{:keys [data] :as c}]
  [:div.card.card-q
   [:span.card-tag (str (t :chat/question-tag) (topic-name (:concept data)))]
   [:p (:question data)]
   [:div.row
    [:button.primary {:disabled (or (foreign?) (:refused c)) :on-click #(accept! c)} (t :chat/enqueue)]
    [:button {:disabled (foreign?) :on-click #(resolve-card! c)} (t :chat/hide)]]])

(defn grow-textarea
  "One line tall, grows with content up to 200px, shrinks back on send;
   Enter sends, Shift+Enter for a newline (the kintale composer verbatim)."
  [_]
  (let [!el (atom nil)
        fit! (fn []
               (when-let [^js el @!el]
                 (set! (.. el -style -height) "auto")
                 (set! (.. el -style -height)
                       (str (min 200 (.-scrollHeight el)) "px"))))]
    (fn [{:keys [value placeholder on-change on-submit]}]
      ;; semi-controlled: the DOM owns the text while typing (a value fed
      ;; through parent props dropped fast keystrokes — reagent's known
      ;; controlled-input race); the parent only ever *clears* it on send
      (r/after-render (fn []
                        (when-let [^js el @!el]
                          (when (and (= "" value) (not= "" (.-value el)))
                            (set! (.-value el) "")))
                        (fit!)))
      [:textarea {:default-value "" :placeholder placeholder :rows 1
                  :ref #(when % (reset! !el %))
                  :on-change #(on-change (.. % -target -value))
                  :on-key-down (fn [^js e]
                                 (when (and (= "Enter" (.-key e)) (not (.-shiftKey e)))
                                   (.preventDefault e)
                                   (on-submit)))}])))

(def ^:private batch 5)

(defn- reviewable?
  "A link card can only be accepted once both its topics exist; a topic of
   a kind the ontology lacks, once that type joins it."
  [kg {:keys [card data waits-type waits-relation]}]
  (cond
    waits-type (some? (profile/node-type-of (fold/profile kg) (keyword waits-type)))
    waits-relation (and (some? (profile/relation (fold/profile kg) (keyword waits-relation)))
                        (get-in kg [:concepts (:from data)]) (get-in kg [:concepts (:to data)]))
    (not= :edge card) true
    :else (and (get-in kg [:concepts (:from data)]) (get-in kg [:concepts (:to data)]))))

(defn- cards-panel []
  (let [kg (store/kg)
        ;; another graph's topics are not in this fold — show its pile as is,
        ;; the buttons refuse anyway
        ;; the guided «Your course» step is pinned above the pile, never
        ;; buried five-at-a-time behind the drafts it waits for
        build? #(#{:build-next :profile-next :missing-steps :triage :type-draft :relation-draft :derive-next} (:card %))
        pinned (filter build? @cards)
        pile (remove build? @cards)
        ready (if (foreign?) pile (filter #(reviewable? kg %) pile))
        waiting (- (count pile) (count ready))
        total (+ @decided (count @cards))]
    [:div.cards
     ;; the review counter counts cards; a lone conflict needs none
     (when (pos? total)
       [:<>
        [:h3 (t :chat/reviewed {"{d}" @decided "{n}" total})]
        [:div.bar [:div {:style {:width (str (* 100 (/ @decided (max 1 total))) "%")}}]]])
     (when (foreign?)
       [:div.card-foreign
        (t :chat/cards-other-graph {"{title}" (store/graph-title @graph)})
        [:button {:on-click #(store/switch-graph! @graph)} (t :chat/switch-graph)]])
     (when-not @onto [gaps-card])
     ;; a document of 180 topics is not reviewed five at a time: one tap per
     ;; KIND — every topic (with its ticked drafts), then every link whose
     ;; topics exist; each still goes through the guard, a refusal stays as
     ;; a refused card (business dogfood 2026-10-15: 300 cards, 18 minutes)
     (when-not (foreign?)
       ;; a card the author must look at — a possible duplicate, or an answer the
       ;; model itself calls an inference — is never accepted in bulk
       (let [flagged? #(or (false? (get-in % [:data :stated]))
                           (and (= :concept (:card %)) (fold/near-duplicate kg (:data %))))
             kind (fn [k] (filter #(and (= k (:card %)) (not (:refused %))) ready))
             topics (remove flagged? (concat (kind :concept) (kind :type)))
             links (remove flagged? (concat (kind :edge) (kind :relation)))
             held (count (filter flagged? (concat (kind :concept) (kind :edge))))]
         (when (or (> (count topics) 1) (> (count links) 1) (pos? held))
           [:div.accept-all
            (when (> (count topics) 1)
              [:button.accept-all-topics {:on-click #(accept-all! topics)}
               (t (if (:ontology-graph? kg) :chat/accept-all-types :chat/accept-all-topics) {"{n}" (count topics)})])
            (when (> (count links) 1)
              [:button.accept-all-links {:on-click #(accept-all! links)}
               (t (if (:ontology-graph? kg) :chat/accept-all-relations :chat/accept-all-links) {"{n}" (count links)})])
            (when (pos? held) [:span.muted.accept-all-held (t :chat/accept-all-held {"{n}" held})])])))
     ;; a conflict waits for a person before anything else (wiki/Graph-Sync.md)
     (for [c (sync/graph-conflicts @store/current)] ^{:key (:id c)} [card-view {:card :conflict :data c}])
     (for [c pinned] ^{:key (get-in c [:data :id])} [card-view c])
     (for [[i c] (map-indexed vector (take batch ready))]
       ^{:key (str i (get-in c [:data :id]) (get-in c [:data :from]) (get-in c [:data :to]))} [card-view c])
     (when (> (count ready) batch)
       [:p.muted.more (t :chat/more-cards {"{n}" (- (count ready) batch)})])
     (when (pos? waiting)
       [:p.muted.more (t :chat/links-waiting {"{n}" waiting})])]))

(defn- intake-progress []
  (let [{:keys [i n step] :as pr} @progress]
    [:div.intake-progress
     [:div.row
      [:span (str (part-label pr) " · " (t (case step :edges :chat/step-links :questions :chat/step-questions :chat/step-topics))
                  (when (> n 1) (str " · " (inc i) "/" n)))]
      (when (> n 1)
        [:button.stop {:on-click stop! :disabled @stop?} (t :chat/stop)])]
     [:div.bar [:div {:style {:width (str (* 100 (/ (+ i (if (= step :edges) 0.5 0)) n)) "%")}}]]]))

(defn chat-view []
  (let [draft (r/atom "") confirm-new (r/atom false)]
    (fn []
      [:div.chat
       [:div.chat-head
        [:h2 {:class (when (= :min @size) "restore")
              :on-click (when (= :min @size) #(reset! size :normal))} (t :chat/title)]
        ;; a learner's own graph (build from my material): no author's chair
        (cond
          @onto [:span.mode-badge.ontology (t :chat/mode-ontology)]
          (store/personal?) [:span.mode-badge.learner (t :guide/mode)]
          :else [:span.mode-badge.author (t :chat/mode-author)])
        [:span.muted (if (llm/ready?)
                       (str (t :chat/model-prefix) (:model (llm/config)))
                       (t :chat/no-key))]
        ;; kintale .ptools: new / model / max / close, icon-only
        [:span.ptools
         (when (seq @msgs)
           [:button.rbtn.sm.pt-new {:title (t :chat/new-conversation) :aria-label (t :chat/new-conversation)
                                    ;; undecided cards: ask, never shelve them silently
                                    :on-click #(if (seq @cards) (swap! confirm-new not) (new-conversation!))}
            [icons/icon :pencil]])
         [:a.rbtn.sm.pt-model {:title (t :chat/settings) :aria-label (t :chat/settings) :href "#/settings"}
          [icons/icon :gear]]
         [:button.rbtn.sm.pt-min {:title (t :chat/minimize) :aria-label (t :chat/minimize)
                                  :on-click #(swap! size (fn [s] (if (= s :min) :normal :min)))}
          [icons/icon :minus]]
         [:button.rbtn.sm.pt-max {:title (if (= @size :max) (t :chat/restore) (t :chat/maximize))
                                  :aria-label (t :chat/maximize)
                                  :style {:font-size "17px" :line-height 1}
                                  :on-click #(swap! size (fn [s] (if (= s :max) :normal :max)))}
          (if (= @size :max) "\u29c9" "\u26f6")]
         [:button.rbtn.sm.pt-close {:title (t :chat/close) :aria-label (t :chat/close)
                                    :on-click #(set! js/location.hash "#/")}
          [icons/icon :close]]]]
       (when (and @confirm-new (seq @cards))
         [:div.confirm-new
          [:span (t :chat/new-with-cards {"{n}" (count @cards)})]
          [:div.row
           [:button.primary.keep {:on-click #(do (reset! confirm-new false) (new-conversation!))} (t :chat/keep-for-later)]
           [:button.drop-rest {:on-click #(do (reset! confirm-new false) (discard-all!) (new-conversation!))} (t :chat/discard-rest)]
           [:button {:on-click #(reset! confirm-new false)} (t :chat/cancel)]]])
       [:div.msgs
        ;; an empty sitting says what it is for (UX review 2026-10-02 #8)
        (when (and (empty? @msgs) (empty? @cards) (not @busy))
          [:div.msg.msg-llm.chat-lead (t :chat/lead)
           ;; the interview door (2026-11-20): nothing open, no document yet —
           ;; the graph can be built by talking (types, then content)
           (when (and (nothing-open?) (llm/ready?) (not @onto))
             [:div.row
              [:button.primary.interview-door {:on-click #(start-ontology-interview! @store/current)}
               [icons/icon :chat] (t :chat/interview-door)]])])
        (for [[i m] (map-indexed vector @msgs)]
          ^{:key i} [:div {:class (str "msg msg-" (name (:who m)))} (:text m)])
        (when @busy
          (if @progress [intake-progress] [:div.msg.msg-sys (t :chat/thinking)]))]
       (when (or (seq @cards) (seq (fold/cq-gaps (store/kg))) (seq (sync/graph-conflicts @store/current))) [cards-panel])
       (when-let [d @doc]
         [:div.doc-chip
          [icons/icon :clip]
          [:span.card-tag (str (:name d) (when (:pages d) (str " · " (:pages d) (t :chat/pages-suffix))))]
          [:button.btn {:title (t :chat/remove-file) :on-click #(reset! doc nil)} "✕"]])
       ;; ONE rounded box holds the field and its controls, the way
       ;; kintale's composer does — attach beside send, inside the border
       [:div.composer
        [:div.chat-field
         [grow-textarea
          {:value @draft
           :placeholder (t :chat/composer-placeholder)
           :on-change #(reset! draft %)
           ;; what was typed survives Enter while a call runs (the send
           ;; button is disabled then; Enter was not)
           :on-submit (fn [] (when-not @busy (send! @draft) (when-not (foreign?) (reset! draft ""))))}]
         [:label.chat-attach {:title (t :chat/attach-title)}
          [icons/icon :clip]
          [:input {:type "file" :accept accept-types :style {:display "none"}
                   :on-change (fn [ev]
                                (when-let [f (aget (.. ev -target -files) 0)]
                                  (attach-file! f)
                                  (set! (.. ev -target -value) "")))}]]
         [:button.primary.chat-sendbtn
          {:disabled (or @busy (not (llm/ready?)))
           :title (t :chat/send)
           :on-click (fn [_] (send! @draft) (when-not (foreign?) (reset! draft "")))}
          [icons/icon :send]]]]])))
