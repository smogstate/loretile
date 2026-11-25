(ns loretile.ui
  "M0.2 manual editor, kintale UI language throughout: stroke icons,
   physics graph (d3-force, draggable, halos, glyphs-in-nodes), dashed =
   «not yet real» (ghost proposals), command palette, snackbar."
  (:require [clojure.string :as str]
            ["d3-force-3d" :as d3f]
            [promesa.core :as p]
            [reagent.core :as r]
            [loretile.blobs :as blobs]
            [loretile.chat :as chat]
            [loretile.exercise :as ex]
            [loretile.guide :as guide]
            [loretile.fold :as fold]
            [loretile.ops :as ops]
            [loretile.graph-tools :as gt]
            [loretile.i18n :as i18n :refer [t tn]]
            [loretile.icons :as icons]
            [loretile.learn :as learn]
            [loretile.llm :as llm]
            [loretile.profile :as profile]
            [loretile.merge :as mrg]
            [loretile.store :as store]
            [loretile.sync :as sync]
            [loretile.tour :as tour]))

;; --- routing ---------------------------------------------------------------

(declare fly-to!)   ; the route watcher brings an opened topic into view
(declare prop-filter prop-keys undo-asked! redo-asked!)   ; the map's property filter (with the rail, below)
(defonce route (r/atom {:view :map}))

(defn goto! [hash] (set! js/location.hash hash))

(defn- deep-link!
  "#/g/{graph}[/{route}]: open that graph, then the plain route it stands
   for (`/c/{node}`, `/ontology`, `/course/{id}` …), written back with replaceState (Back leaves the link, no loop). A
   graph not on this device gives «not found», never a same-slug node of the
   open graph: slugs mean nothing between graphs."
  [g rest]
  (let [g (js/decodeURIComponent g)]
    (when (and (not= g @store/current) (some #(= g (:id %)) @store/graphs))
      (store/switch-graph! g))
    (let [plain (cond (not= g @store/current) "#/c/"
                      (seq rest) (str "#" rest)
                      :else "#/")]
      (.replaceState js/history nil "" plain)
      plain)))

(defn read-route! []
  (let [h (or js/location.hash "")
        h (if-let [[_ g node] (re-find #"^#/g/([^/]+)(/.*)?$" h)] (deep-link! g node) h)]
    (reset! route
            (cond
              (str/starts-with? h "#/c/") {:view :concept :id (js/decodeURIComponent (subs h 4))}
              (str/starts-with? h "#/src/")
              (let [[id q] (str/split (subs h 6) #"\?" 2)]
                {:view :source :id (js/decodeURIComponent id)
                 :anchor (second (re-find #"a=([^&]+)" (or q "")))})
              (= h "#/course/new") {:view :course :id nil}
              (str/starts-with? h "#/course/") {:view :course :id (js/decodeURIComponent (subs h 9))}
              (str/starts-with? h "#/chat") {:view :chat}
              (= h "#/settings") {:view :settings}
              (= h "#/ontology") {:view :ontology}
              (= h "#/graphs") {:view :graphs}
              (str/starts-with? h "#/learn/") {:view :learn :id (js/decodeURIComponent (subs h 8))}
              (str/starts-with? h "#/courses") {:view :catalog}
              ;; an address that names no screen says so rather than silently
              ;; opening the map (learning review 2026-11-20 B4: #/catalog)
              (not (#{"" "#" "#/"} h)) (do (reset! store/last-error {:error :unknown-route :detail h}) {:view :map})
              :else {:view :map}))
    ;; screen context for the author's assistant (design #2): the topic or
    ;; course last in view — the chat replaces that panel when it opens
    (case (:view @route)
      :concept (do (reset! chat/focus {:kind :topic :id (:id @route)})
                   ;; opened from search or a list: bring it into view (after the panel renders)
                   (let [id (:id @route)] (js/setTimeout #(fly-to! id) 60)))
      :course (reset! chat/focus (when (:id @route) {:kind :course :id (:id @route)}))
      ;; the chat keeps what was in view; any other screen leaves it
      :chat nil
      (reset! chat/focus nil))))

;; --- ephemeral ui state -----------------------------------------------------

(defonce link-mode (r/atom nil))   ; nil | {:from id}
(defonce link-draft (r/atom nil))  ; {:from :to} awaiting type choice
(defonce show-new-concept (r/atom false))
(defonce palette-open (r/atom false))
(defonce drawer-open (r/atom false))
;; window chrome for the right column (kintale gpopup: :min | :normal | :max).
;; Sticky across routes on purpose — a minimized panel stays minimized when
;; the next topic opens, exactly as kintale's popup does.
(defonce win-size (r/atom :normal))

(defn win-tools
  "Windows-style min / max·restore / close for a panel head. `size` is the
   atom that owns the state (panels share win-size, the chat has its own)."
  [size]
  [:span.ptools
   [:button.rbtn.sm.pt-min {:title (t :ui/minimize) :aria-label (t :ui/minimize)
                            :on-click #(swap! size (fn [s] (if (= s :min) :normal :min)))}
    [icons/icon :minus]]
   [:button.rbtn.sm.pt-max {:title (if (= @size :max) (t :ui/restore) (t :ui/maximize))
                            :aria-label (t :ui/maximize)
                            :style {:font-size "17px" :line-height 1}
                            :on-click #(swap! size (fn [s] (if (= s :max) :normal :max)))}
    (if (= @size :max) "\u29c9" "\u26f6")]
   [:a.rbtn.sm.pt-close {:title (t :ui/close) :aria-label (t :ui/close) :href "#/"}
    [icons/icon :close]]])

;; a minimized window is just its title strip — clicking the title restores
(defn- win-title [size text]
  [:h2 {:class (when (= :min @size) "restore")
        :on-click (when (= :min @size) #(reset! size :normal))} text])

;; theme: same localStorage key as the landing, so one choice follows the
;; visitor across both pages; absent = follow the system
(def ^:private theme-key "loretile-theme")
(defonce theme (r/atom (.getItem js/localStorage theme-key)))

(def ^:private pill-icons {:nodes :plus :links :link :props :pencil :removed :trash})

(defn- peer-pills
  "What others did on the last sync (wiki/Graph-Sync.md phase C, vintry's
   peer toasts): equal pills bottom-right, a round icon, a bold count, a
   label; red for what needs a person; gone after 8 s."
  []
  (when-let [{:keys [key items]} @sync/activity]
    ^{:key key}
    [:div.peer-pills
     (for [{:keys [kind n hot?]} items]
       ^{:key kind}
       [:div.peer-pill {:class [(name kind) (when hot? "hot")]}
        [:span.peer-pill-ico [icons/icon (pill-icons kind)]]
        [:span.peer-pill-n n]
        [:span.peer-pill-label (t (i18n/plural n (mapv #(keyword "ui" (str "peer-" (name kind) "-" %)) ["1" "2" "5"])))]])]))

(defn apply-theme! []
  (if-let [t @theme]
    (set! (.. js/document -documentElement -dataset -theme) t)
    (js-delete (.. js/document -documentElement -dataset) "theme")))
(defn- dark? []
  (or (= "dark" @theme)
      (and (nil? @theme) (.-matches (js/matchMedia "(prefers-color-scheme: dark)")))))
(defn- set-theme! [t]
  (reset! theme t)
  (if t (.setItem js/localStorage theme-key t) (.removeItem js/localStorage theme-key))
  (apply-theme!))
(defn- toggle-theme! []
  (let [next (if (dark?) "light" "dark")]
    (reset! theme next)
    (.setItem js/localStorage theme-key next)
    (apply-theme!)))
(defonce snack (r/atom nil))

(defonce ^:private snack-n (atom 0))

(defn snack! [text]
  ;; each message owns its timer: the same text twice in a row was hidden
  ;; early by the FIRST one's timer (undo-check, 2026-11-20)
  (let [n (swap! snack-n inc)]
    (reset! snack text)
    (js/setTimeout #(when (= @snack-n n) (reset! snack nil)) 2500)))

;; --- physics (kintale graph.cljs pattern: d3-force driven by us) -------------

(defonce sim-state (atom {:sim nil :sig nil}))
(declare vb fit-all!)   ; the viewport and framing, defined with the pan/zoom code below
;; id -> {:x :y :node} — what renders subscribe to. AT SCALE (2026-11-20): a
;; tick no longer re-renders the map through React (500 nodes ran at 6 fps):
;; it writes the moved nodes and edges straight into the svg (`draw-live!`),
;; every render reads the LIVE spot off the sim's node object (`pos`), and
;; this atom is republished every tick only for small graphs — past
;; `live-only` nodes only when the layout settles (measured 2026-11-20,
;; release build, 1000 nodes: 2 → 15 fps, a tap 1 s → 0.1 s).
(defonce positions (r/atom {}))
(def ^:private live-only 150)
(defonce ^:private node-els (js/Map.))   ; id -> the node's <g> (and a ghost's)
(defonce ^:private edge-els (js/Map.))   ; key -> #js [el from to kind] kind: "line" | "loop" | "text"
(defonce drag-id (atom nil))

(defn- live [{:keys [node] :as p}]
  (if node {:x (.-x ^js node) :y (.-y ^js node) :node node} p))

(defn- pos
  "Where node `id` is now (subscribes the render to `positions`)."
  [id]
  (some-> (get @positions id) live))

(def R 27)                                ; node radius
(defonce canvas-w (atom 900))            ; the svg's width in px, for the zoom level

;; layout: :layers (prerequisites flow left → right, read like a syllabus) or
;; :free (the physics alone). Remembered on the device (graph review 2026-10-03)
(def ^:private layout-key "loretile-layout")
(defonce layout-mode (r/atom (if (= "free" (try (.getItem js/localStorage layout-key) (catch :default _ nil))) :free :layers)))
(def ^:private col-w 200)

(defonce ^:private depths-cache (atom [nil nil]))

(defn- depths
  "id -> length of its longest :requires chain down to a foundation (0 = needs
   nothing). The fold refuses cycles; `seen` guards anyway."
  [kg ids]
  (let [ord (profile/ordering-relation (fold/profile kg))
        ;; kept per edge set: depths only change when an edge does
        [k cached] @depths-cache
        {:keys [req memo]} (if (= k [(:edges kg) ord])
                             cached
                             (let [c {:req (reduce (fn [m {:keys [from to type]}] (if (= ord type) (update m from (fnil conj []) to) m))
                                                   {} (:edges kg))
                                      :memo (atom {})}]
                               (reset! depths-cache [[(:edges kg) ord] c])
                               c))]
    (letfn [(d [id seen]
              (or (@memo id)
                  (let [v (if (seen id) 0
                              (inc (apply max -1 (map #(d % (conj seen id)) (req id)))))]
                    (swap! memo assoc id v) v)))]
      (into {} (map (fn [id] [id (d id #{})]) ids)))))

(defn- edge-geom
  "The shape of an edge between spots `p` (its `to`) and `q` (its `from`):
   {:d} for a loop (a type related to itself), else {:x1 :y1 :x2 :y2}
   shortened to the node rims."
  [p q loop?]
  (if loop?
    (let [{:keys [x y]} p]
      {:d (str "M" (- x 7) "," (- y R) " C" (- x 28) "," (- y R 38) " "
               (+ x 28) "," (- y R 38) " " (+ x 7) "," (- y R))})
    (let [dx (- (:x q) (:x p)) dy (- (:y q) (:y p))
          d (max 1 (js/Math.sqrt (+ (* dx dx) (* dy dy))))
          ux (/ dx d) uy (/ dy d)
          r1 (+ R 2) r2 (+ R 6)]
      {:x1 (+ (:x p) (* ux r1)) :y1 (+ (:y p) (* uy r1))
       :x2 (- (:x q) (* ux r2)) :y2 (- (:y q) (* uy r2))})))

(defn- text-spot [p q loop?]
  (if loop? [(:x p) (- (:y p) R 34)] [(/ (+ (:x p) (:x q)) 2) (/ (+ (:y p) (:y q)) 2)]))

(defn- draw-live!
  "One tick: move the drawn nodes and edges to the sim's spots in the svg
   itself — React is not asked (it re-renders only when the graph changes
   or `positions` is published)."
  [by-id]
  (let [at (fn [id] (when-let [^js n (get by-id id)] {:x (.-x n) :y (.-y n)}))]
    (.forEach node-els (fn [^js el id] (when-let [{:keys [x y]} (at id)]
                                          (.setAttribute el "transform" (str "translate(" x "," y ")")))))
    (.forEach edge-els
              (fn [^js v _]
                (let [[^js el from to kind] v
                      p (at to) q (at from)]
                  (when (and p q)
                    (case kind
                      "sel" (do (.setAttribute el "x" (:x p)) (.setAttribute el "y" (+ (:y p) R 16)))
                      "text" (let [[x y] (text-spot p q (= from to))]
                               (.setAttribute el "x" x) (.setAttribute el "y" (- y 4)))
                      "loop" (.setAttribute el "d" (:d (edge-geom p q true)))
                      (let [{:keys [x1 y1 x2 y2]} (edge-geom p q false)]
                        (.setAttribute el "x1" x1) (.setAttribute el "y1" y1)
                        (.setAttribute el "x2" x2) (.setAttribute el "y2" y2)))))))))

(defn- el-ref
  "A ref callback keeping `k`'s element in `m` (nil — React 19 treats a
   returned function as the cleanup)."
  [^js m k & [v]]
  (fn [el] (if el (.set m k (if v (to-array (cons el v)) el)) (.delete m k)) nil))

(declare hood big? edges-among)

(defn- build-sim!
  "(Re)build the simulation when the node/edge signature changes; keep
   surviving nodes at their old spots so the graph doesn't reshuffle.
   A tick writes the svg directly (`draw-live!`, see `positions`).
   ponytail: one svg element per node and edge — past ~1000 nodes a canvas
   or level-of-detail culling is the next step."
  [kg w h]
  (let [ids (concat (or (:ids (hood kg)) (keys (:concepts kg)))
                    (map key (filter #(= :concept (:of (val %))) (:proposals kg))))
        in? (set ids)
        links (if (big? kg)
                (edges-among kg in?)
                (->> (:edges kg)
                     (remove #(= :grounded-in (:type %)))
                     (filter #(and (in? (:from %)) (in? (:to %))))))
        sig [(set ids) (set (map (juxt :from :to :type) links)) @layout-mode (boolean (some #(= (profile/ordering-relation (fold/profile kg)) (:type %)) (:edges kg)))]
        ;; layers only where there is something to column by: a graph with no
        ;; ordering relation (any ontology graph, a fresh data graph) is free
        ;; physics — one column of piled nodes hid every link (UX 2026-11-19)
        ord (profile/ordering-relation (fold/profile kg))
        layers? (and (= :layers @layout-mode) (some #(= ord (:type %)) (:edges kg)))
        dep (when layers? (depths kg (filter (:concepts kg) ids)))
        maxd (apply max 0 (vals dep))
        col-x (fn [id] (when-let [d (get dep id)] (+ (/ w 2) (* (- d (/ maxd 2)) col-w))))]
    (when (not= sig (:sig @sim-state))
      (some-> (:sim @sim-state) (.stop))
      (let [old @positions
            ;; a big graph re-centred: a topic joining the view starts beside a
            ;; neighbour already there, not at a random spot — less to settle
            near (when (big? kg)
                   (fn [id] (some (fn [e] (let [o (if (= id (:from e)) (:to e) (:from e))]
                                            (when-let [p (get old o)]
                                              {:x (+ (:x (live p)) (- (rand-int 60) 30)) :y (+ (:y (live p)) (- (rand-int 60) 30))})))
                                  (fold/edges-at kg id))))
            nodes (clj->js (map (fn [id] (merge {:id id}
                                                (or (get old id) (when near (near id))
                                                    ;; a new node lands where the author is looking:
                                                    ;; the canvas centre put proposals half off a panned map
                                                    (let [{:keys [cx cy]} (or @vb {:cx (/ w 2) :cy (/ h 2)})]
                                                      {:x (or (col-x id) (+ cx (- (rand-int 160) 80)))
                                                       :y (+ cy (- (rand-int 160) 80))}))
                                                ;; layers: the column is PINNED (fx); physics only orders
                                                ;; each column vertically — a pull alone lost to 100
                                                ;; nodes' repulsion and the long links (2026-10-03)
                                                (when-let [cx (col-x id)] {:col cx :x cx :fx cx})))
                                ids))
            jlinks (clj->js (map (fn [e] {:source (:to e) :target (:from e)
                                          :type (name (:type e))}) links))
            sim (-> (d3f/forceSimulation nodes 2)
                    (.force "link" (-> (d3f/forceLink jlinks)
                                       (.id (fn [n] (.-id n)))
                                       (.distance 150) (.strength (if layers? 0.15 0.5))))
                    (.force "charge" (-> (d3f/forceManyBody) (.strength (if layers? -160 -520))))
                    (.force "center" (d3f/forceCenter (/ w 2) (/ h 2)))
                    ;; weak gravity keeps disconnected components on screen
                    ;; layers: each topic pulled to its depth's column; free: weak gravity
                    (.force "x" (-> (d3f/forceX (fn [^js n] (or (.-col n) (/ w 2))))
                                    (.strength (fn [^js n] (if (.-col n) 0.7 0.06)))))
                    (.force "y" (-> (d3f/forceY (/ h 2)) (.strength 0.08)))
                    (.force "collide" (d3f/forceCollide (if layers? (+ R 16) (+ R 34)))))]
        (let [publish! #(reset! positions
                                ;; no clamp to the canvas any more: the map pans and
                                ;; zooms, and a clamp flattened the ring layout's
                                ;; outer rings into one row at the bottom edge
                                (into {} (map (fn [n] [(.-id n) {:x (.-x n) :y (.-y n) :node n}])
                                              nodes)))
              by-id (into {} (map (fn [n] [(.-id n) n]) nodes))
              small? (<= (count ids) live-only)]
          (.on sim "tick"
               (fn []
                 (draw-live! by-id)
                 ;; a publish re-renders every node and edge: ~1 s at 1000
                 ;; nodes — a big graph publishes only once it has settled
                 (when small? (publish!))))
          (.on sim "end.publish" publish!)
          (publish!))
        ;; a big graph's neighbourhood is laid out OFF SCREEN: the ticks run at
        ;; once, nothing drawn between them, then the settled map is drawn and a
        ;; short tail finishes it. Animated, 300 nodes were 4 s of frames and
        ;; paint at 40k. The burst waits for the next paint: run here, the
        ;; topic's page (same commit) waited ~0.5 s more behind it.
        (when (big? kg)
          (let [kept (count (filter #(contains? old %) ids))]
            (.stop sim)
            (js/requestAnimationFrame
             #(js/setTimeout
               (fn []
                 (when (identical? sim (:sim @sim-state))
                   (.tick sim (if (> kept (* 0.6 (count ids))) 30 120))
                   (-> sim (.alpha 0.02) (.alphaDecay 0.1) (.restart))
                   ;; d3's own decay back once the tail ends: a later drag settles as before
                   (.on sim "end.tail" (fn [] (.on sim "end.tail" nil)
                                         (.alphaDecay sim (- 1 (js/Math.pow 0.001 (/ 1 300))))))))
               0))))
        (reset! sim-state {:sim sim :sig sig})
        ;; a graph seen for the first time (nothing survives from the old
        ;; positions) opens framed whole, not at 1:1 on its middle
        ;; framed once the layout has SETTLED — a timer framed a layout
        ;; still spreading, and the frame came out too small
        (when (and (seq ids) (not-any? #(contains? old %) ids))
          (.on sim "end.fit" (fn [] (.on sim "end.fit" nil) (fit-all! 90))))))))

(defn- reheat! [] (some-> (:sim @sim-state) (.alphaTarget 0.3) (.restart)))
(defn- cool! [] (some-> (:sim @sim-state) (.alphaTarget 0)))

(def status-class
  {:minimum-viable "tile-ready" :accepted "tile-accepted"
   :stale "tile-stale" :deprecated "tile-deprecated" :draft "tile-draft"})

;; --- map --------------------------------------------------------------------

(def ^:private type-palette ["#4f7cff" "#2fa36b" "#c94f7c" "#d9822b" "#7c5cd6" "#1f9bb0" "#b08a1f" "#5b6b7c"])

(defn- type-style
  "A type's style as its ontology gives it: `:color` (else a palette colour
   by its place in the vocabulary — the same in the ontology and in every
   graph that uses it) and `:icon`."
  [p kind]
  (when-not (profile/learning? p)
    (let [ts (profile/node-types p)
          i (first (keep-indexed #(when (= kind (:id %2)) %1) ts))]
      (when i
        (let [t (nth ts i)]
          {:color (or (:color t) (nth type-palette (mod i (count type-palette)))) :icon (:icon t)})))))

(defn graph-type-style
  "A type's style in this graph: the graph's own `:type-style` overrides
   (its meta) over the ontology's defaults."
  [kg kind]
  (let [onto? (:ontology-graph? kg)
        base (type-style (if onto? (fold/defined kg) (fold/profile kg)) kind)
        own (when-not onto? (get-in kg [:graph :type-style kind]))]
    (merge base (into {} (remove (comp nil? val)) own))))

(defn node-style
  "How a node is drawn, most specific first (Denis 2026-11-19): the node's
   own `:color` / `:icon`, else its graph's style for its type, else the
   ontology's, else the palette."
  [kg c]
  (let [kind (if (:ontology-graph? kg) (keyword (:id c)) (:kind c))
        t (graph-type-style kg kind)]
    {:color (or (:color c) (:color t)) :icon (or (:icon c) (:icon t))}))

(defn type-color [p kind] (:color (type-style p kind)))

(def ^:private type-icons
  [:person :pin :clock :notes :link :photo :chat :graph :rows :mic :camera :inbox :info :gear :question :bug :clip :filter :locate :lineage])

(defn- icon-picker
  "The app's icons as a row of buttons; «—» clears."
  [current on-pick]
  [:div.icon-picker
   (for [ic type-icons]
     ^{:key ic}
     [:button.icon-pick {:class (when (= (name ic) current) "on") :title (name ic) :aria-label (name ic)
                         :on-click #(on-pick (name ic))}
      [icons/icon ic]])
   [:button.icon-pick.icon-none {:title (t :ui/icon-none) :aria-label (t :ui/icon-none) :on-click #(on-pick nil)} "—"]])

(defn- style-controls
  "Colour and icon for a node or a type. `style` is what is drawn now,
   `own` what is set at this level (a reset × clears it back to inherited)."
  [_ _ _]
  (let [open? (r/atom false)]
    (fn [style own put!]
      [:div.style-controls
       [:label.style-color
        ;; uncontrolled, keyed by the colour drawn now: a controlled picker
        ;; would snap back mid-drag. The browser's own `change` fires once,
        ;; when a colour is chosen (React's onChange fires on every step)
        ^{:key (str "c" (:color style))}
        [:input.prop-color {:type "color" :default-value (or (:color style) "#8a8a8a")
                            :ref (fn [el]
                                   (when el (set! (.-onchange el) #(put! {:color (.. % -target -value)})))
                                   ;; React 19 runs a function returned by a ref as its cleanup
                                   nil)}]
        (t :ui/prop-color)]
       (when (:color own)
         [:button.style-reset {:title (t :ui/style-reset) :aria-label (t :ui/style-reset) :on-click #(put! {:color nil})}
          [icons/icon :close]])
       [:button.icon-current {:class (when @open? "on") :title (t :ui/prop-icon) :on-click #(swap! open? not)}
        (if-let [ic (:icon style)] [icons/icon (keyword ic)] "—")
        [:span (t :ui/prop-icon)]]
       (when (:icon own)
         [:button.style-reset {:title (t :ui/style-reset) :aria-label (t :ui/style-reset) :on-click #(put! {:icon nil})}
          [icons/icon :close]])
       (when @open? [icon-picker (:icon style) #(do (put! {:icon %}) (reset! open? false))])])))

(defn edge-line
  "Arrow points prerequisite → dependent (learning direction).
   from = dependent, to = prerequisite; shortened to the node rims."
  [{:keys [from to type ghost?]} dim? & [reads?]]
  (let [p (pos to) q (pos from)]
    (cond
      (not (and p q)) nil
      ;; a relation from a type to itself (an ontology graph: process
      ;; triggers process) — a loop over the node, a line would be invisible
      (= from to)
      [:path {:d (:d (edge-geom p q true))
              :ref (el-ref edge-els [from type to ghost?] [from to "loop"])
              :fill "none"
              :class (str "edge edge-loop edge-" (name type) (when dim? " dim"))}]
      :else
      (let [{:keys [x1 y1 x2 y2]} (edge-geom p q false)]
        [:line {:x1 x1 :y1 y1 :x2 x2 :y2 y2
                :ref (el-ref edge-els [from type to ghost?] [from to "line"])
                :class (str "edge edge-" (name type) (when dim? " dim") (when ghost? " edge-ghost"))
                :marker-end (when (= :requires type) "url(#arrow)")
                ;; outside learning an arrow shows the reading direction:
                ;; role —performs→ process points at the process (the `to` end)
                :marker-start (when reads? "url(#arrow)")}]))))

(declare edge-open)   ; the edge whose properties are open (below, with the popup)

(defn- edge-text
  "A relation's name at the middle of its edge (above the loop of a type →
   same type)."
  [{:keys [from to type]} label]
  (let [p (pos to) q (pos from)]
    (when (and p q (seq label))
      (let [[x y] (text-spot p q (= from to))]
        ;; tapping it opens the edge's properties in its source node's popup
        [:text.edge-text {:x x :y (- y 4) :text-anchor "middle"
                          :ref (el-ref edge-els [:text from type to] [from to "text"])
                          :on-pointer-down #(.stopPropagation %)
                          :on-click #(do (reset! edge-open [from type to]) (goto! (str "#/c/" (js/encodeURIComponent from))))}
         label]))))

;; kintale tap semantics: a single tap SELECTS (halo) or feeds link mode;
;; a second tap on the selected node opens the concept as a popup window
;; over the map (Denis 2026-09-08)
(defonce graph-sel (r/atom nil))

(defn- node-click [id]
  ;; «double» = tapping the node that is already selected — no clock, so
  ;; a thumb gets there too (Denis 2026-09-08: «double tapping on Handy is
  ;; hard»); a mouse double-click is the same thing twice, fast
  (let [double? (= id @graph-sel)]
    (cond
      (and @link-mode (nil? (:from @link-mode)))
      (reset! link-mode {:from id})

      (and @link-mode (= id (:from @link-mode)))
      (reset! link-mode nil)

      @link-mode
      (do (reset! link-draft {:from (:from @link-mode) :to id})
          (reset! link-mode nil))

      double? (goto! (str "#/c/" (js/encodeURIComponent id)))

      :else (reset! graph-sel id))))

(defn- svg-point
  "Screen → graph coordinates, through the svg's CTM so the viewBox
   (pan/zoom) is honoured."
  [^js svg-el e]
  (let [pt (.createSVGPoint svg-el)]
    (set! (.-x pt) (.-clientX e)) (set! (.-y pt) (.-clientY e))
    (let [w (.matrixTransform pt (.inverse (.getScreenCTM svg-el)))]
      [(.-x w) (.-y w)])))

;; --- pan / zoom (kintale graph.cljs's viewBox-driven vb, reduced) ---------
;; {:cx :cy :w :h} in graph units; nil until the map has a size
(defonce vb (r/atom nil))

;; another graph opened: its map starts fresh. The old viewport and spots
;; stayed, so a new graph's first nodes landed where the LAST graph was
;; being looked at — half off a phone's screen (UX walk 2026-11-19)
(defonce ^:private view-reset
  (add-watch store/current ::view-reset
             (fn [_ _ old new] (when (not= old new) (reset! vb nil) (reset! positions {})))))
(defonce ^:private pointers (atom {}))   ; pointer-id -> [clientX clientY]
(def ^:private min-w 200)
(def ^:private max-w 8000)

(defn- zoom-at
  "vb scaled by `factor` (>1 out, <1 in), the graph point (px,py) fixed."
  [{:keys [cx cy w h]} px py factor]
  (let [new-w (max min-w (min max-w (* w factor)))
        scale (/ new-w w)
        new-h (* h scale)
        fx (/ (- px (- cx (/ w 2))) w)
        fy (/ (- py (- cy (/ h 2))) h)]
    {:cx (- px (* (- fx 0.5) new-w)) :cy (- py (* (- fy 0.5) new-h)) :w new-w :h new-h}))

(defn- px->world
  "How many graph units one screen pixel is at the current zoom."
  [^js svg-el]
  (/ (:w @vb) (max 1 (.-clientWidth svg-el))))

(defn- wheel! [^js e]
  ;; a native listener ({passive false}), not React's — React marks wheel
  ;; passive and preventDefault is ignored, so the page scrolled instead
  (.preventDefault e)
  (when @vb
    (let [svg (.-currentTarget e)
          [px py] (svg-point svg e)
          factor (js/Math.pow 1.0015 (.-deltaY e))]
      (swap! vb zoom-at px py factor))))

(defn- bg-down! [^js e]
  (.setPointerCapture (.-currentTarget e) (.-pointerId e))
  (swap! pointers assoc (.-pointerId e) [(.-clientX e) (.-clientY e)]))

(defn- bg-move! [^js e]
  (when (and @vb (contains? @pointers (.-pointerId e)))
    (let [svg (.closest (.-currentTarget e) "svg")
          before @pointers
          after (assoc before (.-pointerId e) [(.-clientX e) (.-clientY e)])
          k (px->world svg)]
      (reset! pointers after)
      (case (count after)
        ;; one finger / mouse: pan
        1 (let [[[x0 y0]] (vals before) [[x1 y1]] (vals after)]
            (swap! vb #(assoc % :cx (- (:cx %) (* k (- x1 x0))) :cy (- (:cy %) (* k (- y1 y0))))))
        ;; two fingers: pinch about the midpoint, and the midpoint's drift pans
        2 (let [[[ax0 ay0] [bx0 by0]] (vals before) [[ax1 ay1] [bx1 by1]] (vals after)
                d0 (max 1 (js/Math.hypot (- bx0 ax0) (- by0 ay0)))
                d1 (max 1 (js/Math.hypot (- bx1 ax1) (- by1 ay1)))
                mid #js {:clientX (/ (+ ax1 bx1) 2) :clientY (/ (+ ay1 by1) 2)}
                [px py] (svg-point svg mid)
                mdx (- (/ (+ ax1 bx1) 2) (/ (+ ax0 bx0) 2))
                mdy (- (/ (+ ay1 by1) 2) (/ (+ ay0 by0) 2))]
            (swap! vb #(-> % (zoom-at px py (/ d0 d1))
                           (update :cx - (* k mdx)) (update :cy - (* k mdy)))))
        nil))))

(defn- bg-up! [^js e]
  (swap! pointers dissoc (.-pointerId e)))

(defn- glyph
  "Stroke icon inside the node, kintale style: drawn in the node's paper
   color so shape, not color alone, tells the status."
  ([ic stroke] (glyph ic stroke R))
  ([ic stroke size]
   (when-let [ps (icons/icon-paths ic)]
     [:g {:style {:pointer-events "none"}
          :transform (str "scale(" (/ size 24) ") translate(-12,-12)")}
      (for [[j d] (map-indexed vector ps)]
        ^{:key j} [:path {:d d :fill "none" :stroke stroke
                          :stroke-width 2.2 :stroke-linecap "round"
                          :stroke-linejoin "round"}])])))

;; --- delayed removal (kintale rel-del): × arms a countdown, nothing is
;; written until the ring closes, ↺ inside the ring takes it back

(def ^:private del-ms 6000)
(defonce del-pending (r/atom {}))   ; key -> timeout id

(defn- del-toggle!
  "Arm or cancel the removal `ev` under key `k`. A removal the guard would
   refuse (a course target) says so at once instead of after the ring.
   `done` = the snack once written (default «removed»)."
  [k ev & [done]]
  (if-let [tid (get @del-pending k)]
    (do (js/clearTimeout tid) (swap! del-pending dissoc k))
    (if-let [err (fold/guard (store/kg) (assoc ev :ev/id "probe" :ev/ns "" :ev/actor "" :ev/ts 0))]
      (reset! store/last-error err)
      (swap! del-pending assoc k
             (let [home @store/current]
             (js/setTimeout (fn []
                              (swap! del-pending dissoc k)
                              ;; the ring belongs to the graph it was armed in:
                              ;; after a switch it removed the same id from the
                              ;; graph open by then (review 2026-10-16 A7)
                              (when (and (store/append-to? home) (nil? (store/append! ev)))
                                (when (= (:id ev) @graph-sel) (reset! graph-sel nil))
                                (snack! (t (or done :ui/removed)))))
                            del-ms))))))

(defn- rm-btn
  "Panel-row removal: trash, or the revert ring while armed."
  [k ev]
  (let [pending? (contains? @del-pending k)]
    [:button.rbtn.sm.pt-rm {:title (t (if pending? :ui/cancel :ui/remove))
                            :aria-label (t (if pending? :ui/cancel :ui/remove))
                            :class (when pending? "pending")
                            :on-click #(del-toggle! k ev)}
     (if pending?
       [:svg {:width 20 :height 20 :viewBox "0 0 20 20"}
        [:circle.rel-ring {:cx 10 :cy 10 :r 8.5 :fill "none" :stroke "var(--danger, #d03b3b)"
                           :stroke-width 2 :transform "rotate(-90 10 10)" :pathLength 100}]
        [:text {:x 10 :y 10.5 :text-anchor "middle" :dominant-baseline "middle"
                :font-size 10 :fill "currentColor"} "↺"]]
       [icons/icon :trash])]))

(defn- orbit-chip
  "Kintale orbit chip: a small round button on the selected node's rim."
  [cls ang title on-click body]
  (let [a (* (/ js/Math.PI 180) ang) d (+ R 16)]
    [:g {:class (str "orbit " cls)
         :transform (str "translate(" (* d (js/Math.cos a)) "," (* d (js/Math.sin a)) ")")
         ;; the node under the chip must not start a drag or a select
         :on-pointer-down #(.stopPropagation %)
         :on-pointer-up #(.stopPropagation %)
         :on-click (fn [^js e] (.stopPropagation e) (on-click))}
     [:title title]
     [:circle {:r 11}]
     ;; the visible chip stays small; the target is thumb-sized (44 px)
     [:circle.orbit-hit {:r 22}]
     body]))

;; --- ego focus + rearrange (kintale graph tools nav-ego / nav-tidy) -------

(defonce ego-mode (r/atom false))

(defonce ^:private adjacency-cache (atom [nil nil]))

(defn- adjacency
  "id -> neighbour set over every edge but grounded-in, both directions.
   Kept for the last graph value: the big map asks on every render."
  [kg]
  (let [[k v] @adjacency-cache]
    (if (identical? k (:edges kg))
      v
      (let [v (persistent!
               (reduce (fn [m {:keys [from to]}] (-> m (assoc! from (conj (get m from #{}) to)) (assoc! to (conj (get m to #{}) from))))
                       (transient {}) (remove #(= :grounded-in (:type %)) (:edges kg))))]
        (reset! adjacency-cache [(:edges kg) v])
        v))))

(defn- edges-among
  "The edges (but grounded-in) between topics of the set `shown` — off the
   fold's per-node index, never a scan of every edge."
  [kg shown]
  (distinct (for [id shown, e (fold/edges-at kg id)
                  :when (and (not= :grounded-in (:type e)) (shown (:from e)) (shown (:to e)))]
              e)))

;; --- a big graph's map: a neighbourhood (wiki/Graph-Store.md, wiki/UX.md «no
;; global hairball») — past `big-at` topics the map shows one topic and what
;; lies 1–2 links away, nearest first, at most `hood-cap`; tapping a topic
;; re-centres it, the palette jumps anywhere. Below, the whole graph as ever.
(def ^:private big-at 500)
(def ^:private hood-cap 300)
(defonce ^:private hood-centre (atom nil))

(declare focus-root)

(defn- big? [kg] (> (count (:concepts kg)) big-at))

(defn- hood
  "{:centre id :ids [id …]} of a big graph's map, or nil for a small one."
  [kg]
  (when (big? kg)
    (let [known? #(contains? (:concepts kg) %)
          sel (focus-root)
          _ (when (known? sel) (reset! hood-centre sel))
          centre (if (known? @hood-centre)
                   @hood-centre
                   ;; nothing chosen yet: where work last happened
                   (:id (apply max-key #(or (:changed-at %) 0) (vals (:concepts kg)))))
          ;; neighbours off the fold's per-node index (no whole-graph pass)
          adj (fn [id _] (keep #(when (not= :grounded-in (:type %)) (if (= id (:from %)) (:to %) (:from %)))
                               (fold/edges-at kg id)))]
      ;; 1–2 links out; further while the map would show fewer than 40
      (loop [out [centre] seen #{centre} frontier [centre] d 0]
        (if (or (and (>= d 2) (>= (count out) 40)) (empty? frontier) (>= (count out) hood-cap))
          {:centre centre :ids (vec (take hood-cap out))}
          (let [nxt (->> frontier (mapcat #(sort (adj % #{}))) (remove seen) distinct
                         (filter known?) (take (- hood-cap (count out))) vec)]
            (recur (into out nxt) (into seen nxt) nxt (inc d))))))))

(defn- bfs-rings
  "id -> hop distance from root; unreachable ids get (inc max-found)."
  [root adj ids]
  (loop [ring {root 0} frontier #{root} d 1]
    (let [nxt (set (remove ring (mapcat #(adj % #{}) frontier)))]
      (if (seq nxt)
        (recur (into ring (map #(vector % d) nxt)) nxt (inc d))
        (let [far (inc (apply max 0 (vals ring)))]
          (into ring (map #(vector % far) (remove ring ids))))))))

(defonce ^:private hub-cache (atom nil))
(defn- hubs
  "Topics with three or more links: they keep their titles when zoomed far
   out. Cached per graph value — the map re-renders every physics tick."
  [kg]
  (let [{c-kg :kg ids :ids} @hub-cache]
    (if (identical? c-kg (:edges kg))
      ids
      ;; off the fold's per-node edge index: no adjacency of the whole graph
      (let [ids (set (keep (fn [[id ks]] (when (>= (count (remove #(= :grounded-in (nth % 2)) ks)) 3) id))
                           (fold/neighbour-index kg)))]
        (reset! hub-cache {:kg (:edges kg) :ids ids})
        ids))))

(defn- focus-root []
  (or @graph-sel (when (= :concept (:view @route)) (:id @route))))

(defn- ego-focus
  "Ego mode: the selected node and its depth-1 neighbourhood; the map dims
   the rest. Computed at render, so re-selecting re-focuses by itself."
  [kg]
  (when @ego-mode
    (when-let [root (focus-root)]
      {:ids (conj (get (adjacency kg) root #{}) root)})))

(defn- fit-all!
  "Frame every node (kintale fit-to-ids): the viewport follows a rearrange."
  [pad]
  (let [ps (map live (vals @positions))]
    (when (seq ps)
      (let [xs (map :x ps) ys (map :y ps)
            x0 (- (apply min xs) pad) x1 (+ (apply max xs) pad)
            y0 (- (apply min ys) pad) y1 (+ (apply max ys) pad)
            {:keys [w h]} @vb
            aspect (/ w (max 1 h))
            bw (max 300 (- x1 x0)) bh (max 200 (- y1 y0))
            ;; never zoomed in past 1:1 — a small graph framed to its box filled the screen 4×
            fw (cond-> (max bw (* bh aspect) (or @canvas-w 0))
                 ;; a phone shows a readable part rather than an unreadable whole
                 (< (.-innerWidth js/window) 700) (min (* 1.6 (or @canvas-w 400))))]
        (reset! vb {:cx (/ (+ x0 x1) 2) :cy (/ (+ y0 y1) 2) :w fw :h (/ fw aspect)})))))

(defn- rearrange!
  "Ring layout by BFS depth from the selected node (or the best-connected
   one): ring d at radius d·step, members spread evenly. The rings stay
   PINNED (kintale relayout-pinned) — released on a timer the springs
   pulled them apart again; dragging a node releases that node."
  [kg]
  (let [adj (adjacency kg)
        ids (keys (:concepts kg))
        root (or (focus-root)
                 (first (sort-by (fn [id] [(- (count (adj id #{}))) id]) ids)))
        rings (bfs-rings root adj ids)
        by-ring (group-by val rings)
        ^js hub (:node (pos root))
        hx (if hub (.-x hub) 0) hy (if hub (.-y hub) 0)
        step 170]
    (doseq [[d members] by-ring
            :let [ms (sort (map key members)) n (count ms)]
            [i id] (map-indexed vector ms)]
      (when-let [^js nd (:node (pos id))]
        (let [a (+ (* 2 js/Math.PI (/ i (max n 1))) (* d 0.4))
              r (* d step)
              x (+ hx (* r (js/Math.cos a))) y (+ hy (* r (js/Math.sin a)))]
          (set! (.-x nd) x) (set! (.-y nd) y)
          (set! (.-fx nd) x) (set! (.-fy nd) y))))
    (reheat!)
    (js/setTimeout (fn [] (cool!) (fit-all! 90)) 250)))

(defn- course-focus
  "When a course is open: its targets and their requires-closure — the map
   dims everything else (the gap view's shape, curator side)."
  [kg]
  (when-let [c (and (= :course (:view @route)) (get-in kg [:courses (:id @route)]))]
    (let [targets (set (map first (:targets c)))]
      {:targets targets :closure (fold/requires-closure kg targets)})))

(defn node [kg id focus & [mark]]
  ;; `mark` = :edit | :remove | :other — a merge-file change waiting on its card
  (let [c (get-in kg [:concepts id])
        p (pos id)
        ;; status, glyph and material counts are LEARNING marks: a type or a
        ;; business node is just a node (UX 2026-11-19)
        learnable? (profile/learnable? (fold/profile kg))
        status (if learnable? (fold/concept-status kg id) :plain)
        n-lo (count (fold/concept-objects kg id))
        n-it (count (fold/concept-items kg id))
        empty? (and learnable? (= :accepted status) (zero? n-lo) (zero? n-it))
        selected? (or (= id @graph-sel) (= id (:id @route)))
        linking? (= id (:from @link-mode))
        focus-class (str (when focus
                           (cond ((:ids focus #{}) id) ""
                                 ((:targets focus #{}) id) " in-target"
                                 ((:closure focus #{}) id) " in-closure"
                                 :else " dim"))
                         ;; the property filter (a map tool) dims the rest
                         (when-let [{fk :k fv :v} @prop-filter]
                           (when (and fk (not= (str (get c fk)) fv)) " dim filtered-out")))
        map-prop (get-in kg [:graph :map-prop])]
    (when p
      [:g {:class (str "node " (status-class status) (when empty? " node-empty") focus-class
                       (when selected? " selected")
                       (when mark (str " pending-" (name mark)))
                       ;; well-connected topics keep their titles when zoomed far out
                       (when ((hubs kg) id) " hub"))
           :transform (str "translate(" (:x p) "," (:y p) ")")
           :ref (el-ref node-els id)
           :tab-index 0
           :on-pointer-down (fn [^js e]
                              (.stopPropagation e)
                              (.setPointerCapture (.-currentTarget e) (.-pointerId e))
                              ;; the map counts this finger too: a pinch that
                              ;; starts on a node is still a pinch
                              (swap! pointers assoc (.-pointerId e) [(.-clientX e) (.-clientY e)])
                              (reset! drag-id id)
                              (let [n ^js (:node p)]
                                (set! (.-dragged n) false)
                                (set! (.-down n) #js [(.-clientX e) (.-clientY e)])))
           ;; a finger never holds still: travel under 6px is a TAP, not a
           ;; drag (kintale's threshold) — before this every touch «moved»
           :on-pointer-move (fn [^js e]
                              (if (> (count @pointers) 1)
                                ;; second finger down → this is a pinch, not a drag
                                (do (set! (.-dragged ^js (:node (pos id))) true)
                                    (bg-move! e))
                              (when (= @drag-id id)
                                (let [n ^js (:node (pos id))
                                      [x0 y0] (.-down n)
                                      far? (or (.-dragged n)
                                               (> (js/Math.hypot (- (.-clientX e) x0) (- (.-clientY e) y0)) 6))]
                                  (when far?
                                    (let [svg (.closest (.-currentTarget e) "svg")
                                          [x y] (svg-point svg e)]
                                      ;; the physics wake up on the first real
                                      ;; drag movement, not on the touch
                                      (when-not (.-dragged n) (reheat!))
                                      (set! (.-dragged n) true)
                                      (set! (.-fx n) x) (set! (.-fy n) y)))))))
           :on-pointer-up (fn [^js e]
                            (swap! pointers dissoc (.-pointerId e))
                            (let [n ^js (:node (pos id))]
                              (reset! drag-id nil)
                              ;; a TAP leaves the node exactly as it was — a
                              ;; rearranged (pinned) node stayed pinned; only
                              ;; a drag releases it (Denis 2026-09-08: «selecting
                              ;; nodes shoots them to the moon»)
                              (if (.-dragged n)
                                (do (cool!)
                                    ;; in a rearranged (pinned) layout the dropped
                                    ;; node stays where it was dropped — freed
                                    ;; alone among pinned neighbours it was the one
                                    ;; the springs could throw
                                    (when-not (some (fn [[oid {o :node}]] (and (not= oid id) (some? (.-fx ^js o))))
                                                    @positions)
                                      (set! (.-fx n) nil) (set! (.-fy n) nil)))
                                (node-click id))))}
       ;; selection halo — kintale: a ring offset from the node, in the node's
       ;; own colour (Denis 2026-11-19: the accent brown looked foreign on a
       ;; blueprint map and around coloured types); accent when it has none
       (when (or selected? linking?)
         [:circle.halo {:r (+ R 8) :fill "none" :stroke (or (:color (node-style kg c)) "var(--accent)")
                   :stroke-width 2 :opacity 0.7
                   :stroke-dasharray (when linking? "6 4")}])
       [:circle.node-body (if-let [color (:color (node-style kg c))]
                            {:r R :style {:fill color}}
                            {:r R})]
       ;; a merge-file edit or removal waiting on its card: a dashed halo,
       ;; the same ring the selection uses, coloured by what is coming
       (when mark
         [:circle.pending-ring {:r (+ R 7) :fill "none" :stroke-width 2.2 :stroke-dasharray "5 4"}])
       (when (and selected? (not @link-mode) (nil? @store/as-of))
         (let [pending? (contains? @del-pending id)]
           [:<>
            [orbit-chip "orbit-link" -35 (t :ui/link) #(reset! link-mode {:from id})
             [glyph :link "currentColor" 13]]
            [orbit-chip (str "orbit-del" (when pending? " pending")) 35
             (t (if pending? :ui/cancel :ui/remove))
             #(del-toggle! id (ops/discard-node id (fold/material-ids kg id)))
             (if pending?
               [:<>
                [:circle.rel-ring {:r 11 :fill "none" :transform "rotate(-90)" :pathLength 100}]
                [:text {:y 0.5 :text-anchor "middle" :dominant-baseline "middle" :font-size 11} "↺"]]
               [glyph :trash "currentColor" 13])]]))
       ;; a style's icon draws in the node; else learning's status glyph
       (if-let [ic (:icon (node-style kg c))]
         [glyph (keyword ic) "var(--card)"]
         (when learnable?
           [glyph (case status
                    :minimum-viable :notes :stale :clock :deprecated :minus
                    (if empty? :plus :pencil))
            "var(--card)"]))
       ;; the selected node shows its whole title; every node tells it on hover
       [:title (:title c)]
       [:text.node-label {:y (+ R 16)}
        ;; the selected one is drawn whole in the top layer (map-view)
        (when-not selected? (let [t (:title c)] (if (> (count t) 20) (str (subs t 0 19) "…") t)))]
       ;; the graph's chosen property, under the title (2026-11-20)
       (when-let [v (and map-prop (get c map-prop))]
         [:text.node-prop {:y (+ R (if learnable? 44 30))} (let [v (str v)] (if (> (count v) 22) (str (subs v 0 21) "…") v))])
       (when learnable?
         [:text.node-sub {:y (+ R 30)}
          (str (t :ui/node-sub-counts {"{n}" n-lo "{m}" n-it})
               (when (fold/stale? kg (:id c)) (t :ui/node-stale-suffix)))])])))

(defn ghost-node
  "Chat proposal on the map before accept: kintale's dashed-ring ghost."
  [[id prop]]
  (let [p (pos id)]
    (when p
      [:g.node.node-ghost {:transform (str "translate(" (:x p) "," (:y p) ")")
                           :ref (el-ref node-els id)
                           ;; the map captures the pointer on press, which ate this
                           ;; click — a ghost never opened anything
                           :on-pointer-down #(.stopPropagation %)
                           ;; the sitting that proposed it, even a shelved one
                           :on-click #(do (chat/open-proposal! id) (goto! "#/chat"))}
       [:circle {:r R :fill "var(--bg)" :opacity 0.6}]
       [:circle {:r (+ R 4) :fill "none" :stroke "var(--accent)"
                 :stroke-width 1.6 :stroke-dasharray "5 4" :opacity 0.9}]
       [glyph :question "var(--muted)"]
       [:text.node-label {:y (+ R 16) :opacity 0.75} (:title prop)]
       [:text.node-sub {:y (+ R 30)} (t :ui/ghost-hint)]])))

(declare rail fullscreen-btn legend-btn layout-btn demos demos-on? pick-demo! merge-badge asof-btn color-scheme node-words edge-label edge-open filter-btn prop-filter)

(defn- mount-map!
  "Size the sim to the svg, seed the viewport once, bind the wheel once.
   Runs on mount AND update: the svg only exists once the graph has a
   node (the empty state has none), so mount alone would miss it."
  [kg]
  (let [^js el (js/document.querySelector ".map-wrap svg.graph")
        w (if el (.-clientWidth el) 900)
        h (if el (max 400 (.-clientHeight el)) 560)]
    (when el
      (when (seq (:concepts kg)) (tour/maybe-map-tour!))
      (reset! canvas-w w)
      (when (nil? @vb) (reset! vb {:cx (/ w 2) :cy (/ h 2) :w w :h h}))
      (when-not (.-wheelBound el)
        (set! (.-wheelBound el) true)
        (.addEventListener el "wheel" wheel! #js {:passive false})))
    (build-sim! kg w h)))

(defn map-view [kg]
  (r/create-class
   {:component-did-mount
    (fn [_] (mount-map! kg))
    :component-did-update
    (fn [this _] (mount-map! (second (r/argv this))))
    :reagent-render
    (fn [kg]
      ;; the map's colour scheme is a graph property (Denis 2026-11-19); a
      ;; graph describing types defaults to the blueprint look
      [:div.map-wrap {:class (str "scheme-" (color-scheme kg))}
       [:div.map-tools [legend-btn] [layout-btn] [fullscreen-btn] [asof-btn] [filter-btn] [merge-badge]]
       ;; a big graph says what part of it the map shows, and how to move
       (when-let [{:keys [centre ids]} (hood kg)]
         [:div.hood-note (t :ui/hood-note {"{n}" (count ids) "{m}" (count (:concepts kg))
                                           "{title}" (get-in kg [:concepts centre :title])})])
       ;; the past is for looking at: no tools that write
       (when (nil? @store/as-of) [rail])
       (if (and (empty? (:concepts kg)) (empty? (:proposals kg)))
         [:div.empty
          [icons/icon :graph "empty-ico"]
          ;; the ONLY screen with no tour (it needs a graph to point at), so
          ;; it has to explain itself in a sentence
          (cond
            (:ontology-graph? kg) [:<> [:h2.empty-t (t :ui/empty-onto-title)] [:p (t :ui/empty-onto-text)]]
            (profile/learning? (fold/profile kg)) [:<> [:h2.empty-t (t :ui/empty-title)] [:p (t :ui/empty-map-text)]]
            :else [:<> [:h2.empty-t (t :ui/empty-graph-title)] [:p (t :ui/empty-graph-text)]])
          [:div.btnrow
           [:button.primary {:on-click #(reset! show-new-concept true)}
            [icons/icon :plus] (t (:button (node-words kg)))]
           [:a.btn {:href "#/chat"} [icons/icon :chat] (t :ui/assistant)]]
          ;; kintale's «load demo» — three subjects, one per pitch (not on an
          ;; ontology's map: a demo is content, never a vocabulary)
          (when (and demos-on? (not (:ontology-graph? kg)))
            [:<>
             [:p.muted.empty-or (t :ui/empty-or)]
             [:div.demo-pick
              (for [[slug kt kb] demos]
                ^{:key slug}
                [:button.primary.demo-row {:class (when (= slug :sql) "load-demo")
                                           :on-click #(pick-demo! slug)}
                 [:b (t kt)] [:span (t kb)]])]])]
         [:svg.graph {:width "100%" :height "calc(var(--vh) - 55px)"
                      ;; level of detail: counts only when zoomed in (or on hover /
                      ;; selection); far out, only the well-connected keep titles
                      :class (when-let [{:keys [w]} @vb]
                               (let [k (/ w (max 1 @canvas-w))]
                                 (cond (> k 2.2) "lod-far" (> k 0.85) "lod-mid" :else "lod-near")))
                      :viewBox (when-let [{:keys [cx cy w h]} @vb]
                                 (str (- cx (/ w 2)) " " (- cy (/ h 2)) " " w " " h))
                      :on-pointer-down bg-down! :on-pointer-move bg-move!
                      :on-pointer-up bg-up! :on-pointer-cancel bg-up!}
          [:defs
           [:marker#arrow {:viewBox "0 0 10 10" :refX 8 :refY 5
                           :markerWidth 7 :markerHeight 7 :orient "auto-start-reverse"}
            [:path {:d "M 0 1 L 9 5 L 0 9 z"}]]]
          @positions   ; deref: a publish re-renders (a tick only moves elements — draw-live!)
          (let [focus (or (ego-focus kg) (course-focus kg))
                lit (or (:ids focus) (some-> (:closure focus) set))
                marks (chat/pending-marks)
                ;; a big graph: only the neighbourhood is drawn
                shown (some-> (hood kg) :ids set)
                edges (if shown
                        (edges-among kg shown)
                        (remove #(= :grounded-in (:type %)) (:edges kg)))]
            [:<>
             (for [e edges]
               ^{:key (str e)} [edge-line e (and lit (not (and (lit (:from e)) (lit (:to e)))))
                                (not (profile/learning? (fold/profile kg)))])
             ;; outside learning the relations ARE the content: name them on the map
             (when-not (profile/learning? (fold/profile kg))
               (for [e edges]
                 ^{:key (str "lbl" e)} [edge-text e (edge-label kg (:type e))]))
             ;; a proposed link, dashed like a ghost topic (a merge file's or the chat's)
             (for [[k p] (:proposals kg) :when (= :edge (:of p))]
               ^{:key (str k)} [edge-line (assoc p :ghost? true) false])
             (for [id (if shown (filter shown (keys (:concepts kg))) (keys (:concepts kg)))] ^{:key id} [node kg id focus (get marks id)])
             ;; the selected node's whole title, drawn on top of its neighbours'
             ;; labels — re-ordering the nodes instead moved the tapped one mid-tap
             ;; and swallowed the click that completes a link
             (let [sel (focus-root)]
               (when-let [p (and sel (pos sel))]
                 [:text.node-label.sel-label {:x (:x p) :y (+ (:y p) R 16) :pointer-events "none"
                                              :ref (el-ref edge-els :sel-label [sel sel "sel"])}
                  (get-in kg [:concepts sel :title])]))])
          (for [g (filter #(= :concept (:of (val %))) (:proposals kg))]
            ^{:key (key g)} [ghost-node g])])])}))

;; --- forms ------------------------------------------------------------------

(defn- slug [s]
  ;; the letters fold/tokens keeps (Latin-1 + Latin Extended, Greek,
  ;; Cyrillic): «[a-zа-яё]» dropped ä ö ü ß — «Größe» and «Grüße» were one
  ;; id, «gr-e» (review 2026-10-16 A6). No `u` flag in CLJS regex literals.
  (-> s str/lower-case
      (str/replace #"[^0-9a-z\u00C0-\u00D6\u00D8-\u00F6\u00F8-\u024F\u0370-\u03FF\u0400-\u04FF]+" "-")
      (str/replace #"(^-|-$)" "")))

(defn- fresh-id
  "`(slug title)`, or `prefix-<time>` when the title has no letters slug
   keeps; suffixed -2, -3 … past the ids in `taken`."
  [title prefix taken]
  (let [base (not-empty (slug title))
        base (or base (str prefix "-" (.toString (js/Date.now) 36)))]
    (first (remove taken (cons base (map #(str base "-" %) (iterate inc 2)))))))

(defn- focus! [el] (when el (.focus el)))

(defn- node-type-select
  "A custom ontology names its node types: the author picks one (the
   learning profile has one type and no select)."
  [kind]
  (let [p (store/profile)]
    (when-not (profile/learning? p)
      [:select.node-type {:value (name (or @kind (:id (first (profile/node-types p)))))
                          :on-change #(reset! kind (keyword (.. % -target -value)))}
       (for [nt (profile/node-types p)]
         ^{:key (:id nt)} [:option {:value (name (:id nt))} (:label nt)])])))

(defn- default-kind
  "The kind a new node gets: the first node type of a custom profile,
   none (the Data Model's optional tag) in learning."
  []
  (let [p (store/profile)]
    (when-not (profile/learning? p) (:id (first (profile/node-types p))))))

(defn- node-words
  "The add dialog speaks the graph's language: a type in an ontology graph,
   a node in a graph on a custom ontology, a topic in learning."
  [kg]
  (cond (:ontology-graph? kg) {:heading :ui/new-type-heading :name :ui/type-name-placeholder :def :ui/type-def-placeholder :button :ui/type-word}
        (profile/learning? (fold/profile kg)) {:heading :ui/new-topic-heading :name :ui/topic-name-placeholder :def :ui/definition-placeholder :button :ui/topic}
        :else {:heading :ui/new-node-heading :name :ui/node-name-placeholder :def :ui/node-def-placeholder :button :ui/node-word}))

(defn new-concept-form []
  (let [title (r/atom "") definition (r/atom "") kind (r/atom (default-kind))]
    (fn []
      [:div.form
       [:h3 [icons/icon :plus] (t (:heading (node-words (store/kg))))]
       (when-not (:ontology-graph? (store/kg)) [node-type-select kind])
       ;; semi-controlled (CLAUDE.md gotcha): the map redraws every physics tick,
       ;; and a controlled field dropped keystrokes («Фильтры WHERE» → «иe»);
       ;; the form unmounts on save, so a reopen starts empty
       [:input {:placeholder (t (:name (node-words (store/kg)))) :default-value @title :ref focus!
                :on-change #(reset! title (.. % -target -value))}]
       [:textarea {:placeholder (t (:def (node-words (store/kg)))) :default-value @definition
                   :on-change #(reset! definition (.. % -target -value))}]
       [:div.row
        [:button.primary
         {:on-click (fn [_]
                      ;; «added» only when it was: a refused id or a full
                      ;; storage keeps the form and what was typed in it
                      (when (and (seq @title)
                                 (nil? (store/append! (ops/accept-node (cond-> {:id (fresh-id @title "t" #{}) :title @title
                                                                                :definition @definition}
                                                                         @kind (assoc :kind @kind))))))
                        (snack! (t :ui/topic-added {"{title}" @title}))
                        (reset! show-new-concept false)))}
         (t :ui/add)]
        [:button {:on-click #(reset! show-new-concept false)} (t :ui/cancel)]]])))

;; --- editing (kintale's pencil): every entity edits IN PLACE and writes a
;; version-created event — never a re-accept (the guard refuses that)

(defn- version! [entity id change payload]
  (when (nil? (store/append! (ops/edit id payload change)))
    (snack! (t :ui/saved))))

(defn- edit-btn [open]
  [:button.rbtn.sm.pt-edit {:title (t :ui/edit) :aria-label (t :ui/edit)
                            :on-click #(swap! open not)}
   [icons/icon :pencil]])

(defn- material-check
  "A change of MEANING versus a typo — the editor declares it (Data Model:
   declared, not computed). A material change waits for the next publication;
   a patch reaches pinned learners at once."
  [material]
  [:label.check [:input.material {:type "checkbox" :checked @material
                                  :on-change #(reset! material (.. % -target -checked))}]
   " " (t :ui/material-change)])

(defn- concept-edit-form [c open]
  (let [d (r/atom (select-keys c [:title :definition :kind]))
        material (r/atom false)]
    (fn [c open]
      [:div.form.edit
       ;; semi-controlled (the gotcha in CLAUDE.md): nothing else writes these
       [:input {:default-value (:title @d) :placeholder (t :ui/topic-name-placeholder) :ref focus!
                :on-change #(swap! d assoc :title (.. % -target -value))}]
       [:textarea {:default-value (:definition @d) :placeholder (t :ui/definition-placeholder)
                   :on-change #(swap! d assoc :definition (.. % -target -value))}]
       (let [p (store/profile)]
         (if (profile/learning? p)
           [:select {:value (name (or (:kind @d) :definition))
                     :on-change #(swap! d assoc :kind (keyword (.. % -target -value)))}
            (for [k [:fact :definition :procedure :principle :judgment]]
              ^{:key k} [:option {:value (name k)} (i18n/enum-label "ckind" k)])]
           [:select.node-type {:value (name (or (:kind @d) (:id (first (profile/node-types p)))))
                               :on-change #(swap! d assoc :kind (keyword (.. % -target -value)))}
            (for [nt (profile/node-types p)]
              ^{:key (:id nt)} [:option {:value (name (:id nt))} (:label nt)])]))
       ;; the compliance payoff: a change of MEANING flags the topic for
       ;; review downstream; a typo does not
       [:label.check [:input {:type "checkbox" :checked @material
                              :on-change #(reset! material (.. % -target -checked))}]
        " " (t :ui/material-change)]
       [:div.row
        [:button.primary {:on-click (fn [_]
                                      (when (seq (:title @d))
                                        (version! :concept (:id c) (if @material :material :patch) @d)
                                        (reset! open false)))}
         (t :ui/save)]
        [:button {:on-click #(reset! open false)} (t :ui/cancel)]]])))

(defn- object-edit-form [o open]
  (let [d (r/atom (select-keys o [:text :kind :level])) material (r/atom false)]
    (fn [o open]
      [:div.form.edit
       [:textarea {:default-value (:text @d) :ref focus!
                   :on-change #(swap! d assoc :text (.. % -target -value))}]
       [:p.muted.marks-hint (t :ui/marks-hint)]
       [:div.row
        [:select {:value (name (:kind @d)) :on-change #(swap! d assoc :kind (keyword (.. % -target -value)))}
         (for [k [:explanation :worked-example :diagram :video :audio :interactive :reading]]
           ^{:key k} [:option {:value (name k)} (i18n/enum-label "okind" k)])]
        [:select {:value (name (:level @d)) :on-change #(swap! d assoc :level (keyword (.. % -target -value)))}
         (for [l [:novice :apprentice :professional]]
           ^{:key l} [:option {:value (name l)} (i18n/enum-label "level" l)])]]
       [material-check material]
       [:div.row
        [:button.primary {:on-click (fn [_] (version! :object (:id o) (if @material :material :patch) @d) (reset! open false))}
         (t :ui/save)]
        [:button {:on-click #(reset! open false)} (t :ui/cancel)]]])))

(defn- exercise-error-view [err]
  (when err
    [:pre.ex-out.err {:class (when (ex/prose-error? err) "prose")} (ex/error-text err)]))

(def ^:private runtimes
  ;; language names are names, not copy: the same in every locale
  [[:python "Python"] [:pglite "SQL (Postgres)"]])

(defn- exercise-edit-form
  "An exercise's pencil edits what an author fixes in place — the task text
   and the reference solution. Setup and grading options ride along
   untouched; the choice-question form below would have erased them."
  [i open]
  (let [spec (:spec i) stem (r/atom (:stem spec)) sol (r/atom (:solution spec)) test (r/atom (:test spec))
        checking (r/atom false) err (r/atom nil) material (r/atom false)]
    (fn [i open]
      [:div.form.edit
       [:input {:default-value @stem :ref focus! :on-change #(reset! stem (.. % -target -value))}]
       [:textarea.ex-code {:default-value @sol :rows 4 :spell-check false :placeholder (t :ui/exercise-solution)
                           :on-change #(reset! sol (.. % -target -value))}]
       (when (= :assert (ex/check-kind spec))
         [:textarea.ex-code.ex-test {:default-value @test :rows 3 :spell-check false
                                     :placeholder (t :ui/exercise-test-placeholder)
                                     :on-change #(reset! test (.. % -target -value))}])
       [exercise-error-view @err]
       [material-check material]
       [:div.row
        [:button.primary {:disabled @checking
                          :on-click (fn [_]
                                      (reset! checking true) (reset! err nil)
                                      (let [spec' (cond-> (assoc spec :stem @stem :solution @sol)
                                                    (= :assert (ex/check-kind spec)) (assoc :test @test))]
                                        (-> (ex/check-solution! spec')
                                            (p/then (fn [{:keys [error schema] :as res}]
                                                      (if error
                                                        (reset! err res)
                                                        (do (version! :item (:id i) (if @material :material :patch)
                                                                      {:spec (cond-> spec' schema (assoc :schema schema))})
                                                            (reset! open false)))))
                                            (p/finally #(reset! checking false)))))}
         (if @checking (t :ui/exercise-checking) (t :ui/save))]
        [:button {:on-click #(reset! open false)} (t :ui/cancel)]]])))

(defn- new-rows [options] (mapv (fn [s] {:id (str (random-uuid)) :text s}) options))

(defn- rows->choice
  "Option rows → {:options :key}, or nil while the question cannot be
   answered: at least two options, all different, the marked one filled."
  [rows right]
  (let [rs (keep (fn [{:keys [id text]}] (let [s (str/trim (str text))] (when (seq s) [id s]))) rows)
        opts (mapv second rs)
        k (first (keep-indexed (fn [i [id _]] (when (= id right) i)) rs))]
    (when (and (>= (count opts) 2) (apply distinct? opts) k)
      {:options opts :key k})))

(defn- option-rows
  "A choice question's options as rows: the radio marks the right one, ×
   removes a row, «+ вариант» adds one (UX review 2026-10-02 #2 — options
   typed with «;» and the answer as a number saved wrong keys silently).
   `rows` [{:id :text}], `right` the right row's :id. Semi-controlled
   inputs, keyed by row id (a controlled input here drops keystrokes)."
  [rows right]
  (let [grp (str "opts-" (random-uuid))]
    (fn [rows right]
      [:div.opt-rows
       (for [{:keys [id text]} @rows]
         ^{:key id}
         [:div.opt-row
          [:input.opt-right {:type "radio" :name grp :checked (= id @right)
                             :title (t :ui/option-right) :aria-label (t :ui/option-right)
                             :on-change #(reset! right id)}]
          [:input.opt-text {:default-value text :placeholder (t :ui/option-placeholder)
                            :on-change (fn [e] (let [v (.. e -target -value)]
                                                 (swap! rows (fn [rs] (mapv #(if (= id (:id %)) (assoc % :text v) %) rs)))))}]
          (when (> (count @rows) 2)
            [:button.opt-x {:title (t :ui/remove) :aria-label (t :ui/remove)
                            :on-click (fn [_] (swap! rows (fn [rs] (vec (remove #(= id (:id %)) rs))))
                                        (when (= id @right) (reset! right nil)))} "×"])])
       [:button.opt-add {:on-click #(swap! rows conj {:id (str (random-uuid)) :text ""})}
        [icons/icon :plus] (t :ui/option-add)]
       (when-not (rows->choice @rows @right) [:p.muted.opt-hint (t :ui/option-hint)])])))

(defn- choice-edit-form [i open]
  (let [cloze? (= :cloze (:kind i))
        spec (:spec i)
        stem (r/atom (or (:stem spec) (:text spec) ""))
        rows (r/atom (new-rows (:options spec)))
        right (r/atom (:id (get @rows (or (:key spec) 0))))
        key* (r/atom (str (:key spec)))
        material (r/atom false)]
    (fn [i open]
      [:div.form.edit
       [:input {:default-value @stem :ref focus! :on-change #(reset! stem (.. % -target -value))}]
       (if cloze?
         [:input {:default-value @key* :placeholder (t :ui/answer-placeholder)
                  :on-change #(reset! key* (.. % -target -value))}]
         [option-rows rows right])
       [material-check material]
       [:div.row
        [:button.primary
         {:disabled (and (not cloze?) (nil? (rows->choice @rows @right)))
          :on-click (fn [_]
                      (version! :item (:id i) (if @material :material :patch)
                                {:spec (if cloze?
                                         {:text @stem :key @key*}
                                         (merge {:stem @stem} (rows->choice @rows @right)))})
                      (reset! open false))}
         (t :ui/save)]
        [:button {:on-click #(reset! open false)} (t :ui/cancel)]]])))

(defn- item-edit-form [i open]
  (if (= :exercise (:kind i))
    [exercise-edit-form i open]
    [choice-edit-form i open]))

(defn- editable
  "Row wrapper: the pencil toggles the row's edit form in place."
  [form entity]
  (let [open (r/atom false)]
    (fn [form entity & body]
      (into [:<>
             (when @open [form entity open])]
            (when-not @open
              (conj (vec body) [edit-btn open]))))))

(defn edge-labels
  ;; a fn, not a def: a def froze the labels in the load-time locale, and
  ;; switching the language left the chooser half-translated
  []
  {:requires (t :ui/edge-requires)
   :generalizes (t :ui/edge-generalizes)
   :is-confused-with (t :ui/edge-confused)
   :part-of (t :ui/edge-part-of)
   :see-also (t :ui/edge-see-also)})

(defn edge-label
  "A relation's label in the UI: the dictionary for the learning profile,
   the profile's own words for a custom ontology; `inverse?` = read from
   the receiving end."
  ([kg type] (edge-label kg type false))
  ([kg type inverse?]
   (let [p (fold/profile kg)]
     (cond
       ;; the one relation every profile shares is the dictionary's, not the file's
       (= :grounded-in type) (t (if inverse? :ui/edge-grounds :ui/edge-grounded-in))
       (profile/learning? p) (if (and inverse? (= :requires type)) (t :ui/edge-unlocks) (i18n/enum-label "ename" type))
       :else (profile/label p type inverse?)))))

(defn- offered-relations
  "What the chooser lists for from → to: the learning five, or the custom
   relations whose node types fit these two endpoints."
  [kg from to]
  (let [p (fold/profile kg)]
    (if (profile/learning? p)
      [:requires :generalizes :is-confused-with :part-of :see-also]
      (let [kind-of #(get-in kg [:concepts % :kind])]
        (for [r (profile/relations p)
              :when (and (not= :grounded-in (:id r))
                         (profile/fits? p (:id r) (kind-of from) (kind-of to)))]
          (:id r))))))

(defn- new-relation-row
  "In an ontology graph: draw type A → type B and name a new relation; its
   words live on the edge (wiki/Property-Graph.md)."
  [from to]
  (let [label (r/atom "") inverse (r/atom "")]
    (fn [from to]
      [:div.new-relation
       [:input.new-relation-label {:placeholder (t :ui/new-relation-placeholder) :default-value ""
                                   :on-change #(reset! label (.. % -target -value))}]
       [:input.new-relation-inverse {:placeholder (t :ui/rel-inverse-placeholder) :default-value ""
                                     :on-change #(reset! inverse (.. % -target -value))}]
       [:button.primary.new-relation-add
        {:on-click (fn [_]
                     (let [l (str/trim @label) id (keyword (slug l))]
                       (when (seq (name id))
                         (when-not (store/append!
                                    {:ev/type :change
                                     :ops [[:edge-set from id to (cond-> {:label l}
                                                                   (seq (str/trim @inverse)) (assoc :inverse (str/trim @inverse)))]]})
                           (snack! (t :ui/link-added)))
                         (reset! link-draft nil))))}
        (t :ui/new-relation)]])))

(defn edge-type-chooser [kg]
  (when-let [{:keys [from to]} @link-draft]
    (let [t* (fn [id] (get-in kg [:concepts id :title] id))
          offered (offered-relations kg from to)]
      [:div.modal {:on-click #(reset! link-draft nil)}
       [:div.form {:on-click #(.stopPropagation %)}
        [:h3 [icons/icon :link] (str " «" (t* from) "» → «" (t* to) "»")]
        (when (and (empty? offered) (not (:ontology-graph? kg))) [:p.muted (t :ui/no-relation-fits)])
        ;; an ontology graph names its relations as they are drawn
        (when (:ontology-graph? kg) [new-relation-row from to])
        (for [edge-t offered]
          ^{:key edge-t}
          [:button.edge-btn
           {:on-click (fn [_]
                        (when-not (store/append! (ops/accept-edge {:from from :to to :type edge-t :provenance :authored}))
                          (snack! (t :ui/link-added)))
                        (reset! link-draft nil))}
           (if (profile/learning? (fold/profile kg)) ((edge-labels) edge-t) (edge-label kg edge-t))])
        [:button {:on-click #(reset! link-draft nil)} (t :ui/cancel)]]])))

(defn- parse-tags [s]
  (set (remove str/blank? (map str/trim (str/split (or s "") #"[,;\s]+")))))

(defn- known-audiences [kg]
  (sort (into #{} (mapcat :audience) (vals (:objects kg)))))

(defn src-href [src-id anchor]
  (str "#/src/" (js/encodeURIComponent src-id) (when (seq anchor) (str "?a=" anchor))))

(defn- object-label [o]
  (let [s (fold/plain-text (or (:text o) ""))]
    (str (i18n/enum-label "okind" (:kind o)) " · " (if (> (count s) 40) (str (subs s 0 40) "…") s))))

(defn- object-links
  "Links from this object to others on its topic (2026-10-07, after Онто's
   typed relations): «example of», «simpler version of», «read after». The
   learner's topic card reads in that order. Chips with ×; + opens a form."
  [kg o]
  (let [open (r/atom false) typ (r/atom :example-of) target (r/atom nil)]
    (fn [kg o]
      (let [mine (filter #(= (:id o) (:from %)) (:object-links kg))
            others (remove #(= (:id %) (:id o)) (fold/concept-objects kg (:concept-id o)))]
        [:div.lo-links
         (for [l (sort-by (juxt :type :to) mine)
               :let [to (get-in kg [:objects (:to l)])]
               :when (= :active (:status to))]
           ^{:key (str (name (:type l)) (:to l))}
           [:span.chip.lo-link (i18n/enum-label "olink" (:type l)) ": " (object-label to)
            [:button.chip-x {:title (t :ui/remove) :aria-label (t :ui/remove)
                             :on-click #(store/append! (ops/unlink-objects l))} "×"]])
         (when (seq others)
           (if-not @open
             [:button.lo-link-add {:on-click #(do (reset! target (:id (first others))) (reset! open true))}
              [icons/icon :link] (t :ui/link-add)]
             [:div.row.lo-link-form
              [:select.lo-link-type {:value (name @typ) :on-change #(reset! typ (keyword (.. % -target -value)))}
               (for [k [:example-of :simpler-of :follows]]
                 ^{:key k} [:option {:value (name k)} (i18n/enum-label "olink" k)])]
              [:select.lo-link-target {:value (or @target "") :on-change #(reset! target (.. % -target -value))}
               (for [x others] ^{:key (:id x)} [:option {:value (:id x)} (object-label x)])]
              [:button.primary {:on-click (fn [_]
                                            (when (nil? (store/append! (ops/link-objects {:from (:id o) :to @target :type @typ})))
                                              (reset! open false)))}
               (t :ui/link-save)]
              [:button {:on-click #(reset! open false)} (t :ui/cancel)]]))]))))

(defn add-object-form [kg concept-id]
  (let [open (r/atom false) text (r/atom "") kind (r/atom :explanation) level (r/atom :apprentice)
        audience (r/atom "") source (r/atom "") anchor (r/atom "")]
    (fn [kg _]
      (if-not @open
        [:button.addbtn {:on-click #(reset! open true)} [icons/icon :plus] (t :ui/learning-object)]
        [:div.form
         [:div.row
          [:select {:value (name @kind) :on-change #(reset! kind (keyword (.. % -target -value)))}
           ;; object kinds, not item kinds: "ikind" showed raw English tokens
           (for [k [:explanation :worked-example :diagram :reading]]
             ^{:key k} [:option {:value (name k)} (i18n/enum-label "okind" k)])]
          [:select {:value (name @level) :on-change #(reset! level (keyword (.. % -target -value)))}
           (for [l [:novice :apprentice :professional]]
             ^{:key l} [:option {:value (name l)} (i18n/enum-label "level" l)])]]
         ;; for whom — open role tags; datalist offers what the namespace already uses
         ;; semi-controlled (CLAUDE.md gotcha): controlled, fast typing dropped
         ;; characters; the form closes on save, so a reopen starts empty
         [:input {:placeholder (t :ui/audience-placeholder)
                  :default-value @audience :list "audience-list"
                  :on-change #(reset! audience (.. % -target -value))}]
         [:datalist#audience-list
          (for [a (known-audiences kg)] ^{:key a} [:option {:value a}])]
         ;; where it comes from: a stored source and a place in it
         (when (seq (:sources kg))
           [:div.row
            [:select {:value @source :on-change #(reset! source (.. % -target -value))}
             [:option {:value ""} (t :ui/no-source)]
             (for [[sid s] (sort-by (comp :title val) (:sources kg))]
               ^{:key sid} [:option {:value sid} (:title s)])]
            (when (seq @source)
              [:input.short {:placeholder (t :ui/anchor-placeholder) :default-value @anchor
                             :on-change #(reset! anchor (.. % -target -value))}])])
         [:textarea {:placeholder (t :ui/object-text-placeholder) :default-value @text :ref focus!
                     :on-change #(reset! text (.. % -target -value))}]
         [:p.muted.marks-hint (t :ui/marks-hint)]
         [:div.row
          [:button.primary
           {:on-click (fn [_]
                        (when (seq @text)
                          (store/append! (ops/add-object
                                          (cond-> {:id (str "o-" (random-uuid))
                                                           :concept-id concept-id :kind @kind
                                                           :level @level :locale (store/locale-for-new)
                                                           :origin :authored :text @text}
                                                    (seq (parse-tags @audience))
                                                    (assoc :audience (parse-tags @audience))
                                                    (seq @source) (assoc :grounded-in @source)
                                                    (and (seq @source) (seq @anchor)) (assoc :anchor @anchor))))
                          (snack! (t :ui/lo-added))
                          (reset! text "") (reset! audience "") (reset! anchor "") (reset! open false)))}
           (t :ui/add-lo)]
          [:button {:on-click #(reset! open false)} (t :ui/cancel)]]]))))

(defn- draft-exercises-row
  "The drafting bench's door: the model drafts exercises for this topic,
   the runtime judges them, survivors arrive as cards in the assistant.
   The runtime defaults to the one this graph's exercises already use."
  [kg concept-id]
  (let [rt (r/atom (ex/graph-runtime kg))]
    (fn [kg concept-id]
      (when (llm/ready?)
        [:div.row.draft-ex
         [:select.ex-runtime {:value (name @rt) :on-change #(reset! rt (keyword (.. % -target -value)))}
          (for [[k label] runtimes] ^{:key k} [:option {:value (name k)} label])]
         [:button.draft-ex-btn {:on-click #(do (chat/draft-exercises! concept-id @rt) (goto! "#/chat"))}
          [icons/icon :chat] (t :chat/draft-exercises)]]))))

(defn add-item-form [kg concept-id]
  (let [open (r/atom false) kind (r/atom :single-choice) level (r/atom :apprentice)
        stem (r/atom "") rows (r/atom (new-rows ["" "" ""])) right (r/atom nil) key* (r/atom "")
        rt (r/atom :python) chk (r/atom :output) test (r/atom "")
        setup (r/atom nil) sol (r/atom "") ordered? (r/atom false) checking (r/atom false) err (r/atom nil)]
    (fn [kg _]
      (if-not @open
        [:button.addbtn {:on-click #(reset! open true)} [icons/icon :plus] (t :ui/question)]
        [:div.form
         [:div.row
          [:select {:value (name @kind) :on-change #(do (reset! kind (keyword (.. % -target -value))) (reset! err nil))}
           [:option {:value "single-choice"} (t :ui/single-choice-option)]
           [:option {:value "cloze"} (t :ui/cloze-option)]
           [:option {:value "exercise"} (i18n/enum-label "ikind" :exercise)]]
          ;; a course target at any level needs questions AT that level
          [:select.item-level {:value (name @level) :on-change #(reset! level (keyword (.. % -target -value)))}
           (for [l [:novice :apprentice :professional]]
             ^{:key l} [:option {:value (name l)} (i18n/enum-label "level" l)])]]
         [:input {:placeholder (case @kind :cloze (t :ui/cloze-text-placeholder)
                                     :exercise (t :ui/exercise-task-placeholder)
                                     (t :ui/question-placeholder))
                  ;; semi-controlled (CLAUDE.md gotcha): :value here dropped fast keystrokes
                  :default-value @stem :ref focus!
                  :on-change #(reset! stem (.. % -target -value))}]
         (when (= @kind :exercise)
           (when (nil? @setup) (reset! setup (ex/graph-setup kg @rt concept-id)))
           (let [sql? (= :pglite @rt)]
             ;; semi-controlled: a controlled textarea in this panel drops fast
             ;; keystrokes. Keyed by runtime so a switch shows that runtime's seed.
             [:<>
              [:div.row
               [:select.ex-runtime {:value (name @rt)
                                    :on-change #(let [v (keyword (.. % -target -value))]
                                                  (reset! rt v) (reset! err nil)
                                                  (reset! setup (ex/graph-setup kg v concept-id)))}
                (for [[k label] runtimes] ^{:key k} [:option {:value (name k)} label])]
               (when-not sql?
                 [:select.ex-check {:value (name @chk) :on-change #(do (reset! chk (keyword (.. % -target -value))) (reset! err nil))}
                  [:option {:value "output"} (t :ui/exercise-check-output)]
                  [:option {:value "assert"} (t :ui/exercise-check-assert)]])]
              ^{:key (str "setup-" (name @rt))}
              [:textarea.ex-code.ex-setup {:default-value @setup :rows (if sql? 5 3) :spell-check false
                                           :placeholder (if sql? (t :ui/exercise-setup-placeholder)
                                                            (t :ui/exercise-py-setup-placeholder))
                                           :on-change #(reset! setup (.. % -target -value))}]
              [:textarea.ex-code.ex-solution {:default-value "" :rows 3 :spell-check false
                                              :placeholder (t :ui/exercise-solution)
                                              :on-change #(reset! sol (.. % -target -value))}]
              (when (and (not sql?) (= :assert @chk))
                [:textarea.ex-code.ex-test {:default-value "" :rows 3 :spell-check false
                                            :placeholder (t :ui/exercise-test-placeholder)
                                            :on-change #(reset! test (.. % -target -value))}])
              (when sql?
                [:label.ex-ordered
                 [:input {:type "checkbox" :checked @ordered? :on-change #(swap! ordered? not)}]
                 (t :ui/exercise-ordered)])
              [exercise-error-view @err]]))
         (when (= @kind :single-choice) [option-rows rows right])
         (when (= @kind :cloze)
           [:input {:placeholder (t :ui/answer-placeholder)
                    :default-value @key* :on-change #(reset! key* (.. % -target -value))}])
         [:div.row
          [:button.primary
           {:disabled (or @checking (and (= @kind :single-choice) (nil? (rows->choice @rows @right))))
            :on-click
            (fn [_]
              (if (= @kind :exercise)
                ;; the runtime is the judge: nothing is saved until the
                ;; reference solution has passed its own check in it
                (when (and (seq @stem) (seq (str/trim @sol)) (not @checking))
                  (reset! checking true) (reset! err nil)
                  (let [sql? (= :pglite @rt)
                        spec (cond-> {:runtime @rt :check (if sql? :rows @chk)
                                      :stem @stem :setup @setup :solution @sol}
                               sql? (assoc :ordered? @ordered?)
                               (and (not sql?) (= :assert @chk)) (assoc :test @test))]
                  (-> (ex/check-solution! spec)
                      (p/then (fn [{:keys [error schema] :as res}]
                                (if error
                                  (reset! err res)
                                  (let [id (str "i-" (random-uuid))]
                                    (store/append! (ops/add-item {:id id :primary-concept-id concept-id :level @level
                                                                  :kind :exercise :locale (store/locale-for-new)
                                                                  :spec (cond-> spec schema (assoc :schema schema))}
                                                                 :active))
                                    (snack! (t :ui/question-added))
                                    (reset! stem "") (reset! sol "") (reset! test "") (reset! setup nil)
                                    (reset! ordered? false) (reset! open false)))))
                      (p/finally #(reset! checking false)))))
              (when (seq @stem)
                (let [id (str "i-" (random-uuid))
                      spec (if (= @kind :cloze)
                             {:text @stem :key @key*}
                             (merge {:stem @stem} (rows->choice @rows @right)))]
                  (store/append! (ops/add-item {:id id :primary-concept-id concept-id
                                                :level @level :kind @kind :locale (store/locale-for-new) :spec spec}
                                               :active))
                  (snack! (t :ui/question-added))
                  (reset! stem "") (reset! rows (new-rows ["" "" ""])) (reset! right nil) (reset! key* "") (reset! open false)))))}
           (if @checking (t :ui/exercise-checking) (t :ui/add-question))]
          [:button {:on-click #(reset! open false)} (t :ui/cancel)]]]))))

;; --- concept panel (deep-linked mini page) ----------------------------------

(defn- edge-x
  "A link is one tap to redraw, so it goes at once."
  [e]
  [:button.chip-x {:title (t :ui/remove) :aria-label (t :ui/remove)
                   :on-click (fn [^js ev]
                               (.stopPropagation ev)
                               (when (nil? (store/append! (ops/discard-edge e)))
                                 (snack! (t :ui/removed))))}
   [icons/icon :close]])

(defn- removed-material
  "Material taken out of use (object-demoted) stays in the log; it can be
   put back — `object-promoted`. Collapsed: it is the exception."
  [kg id]
  (let [gone (sort-by :id (filter #(and (= id (:concept-id %)) (= :demoted (:status %))) (vals (:objects kg))))]
    (when (seq gone)
      [:details.removed-material
       [:summary (t :ui/removed-material {"{n}" (count gone)})]
       (for [o gone]
         ^{:key (:id o)}
         [:div.lo.lo-removed
          [:span.lo-kind (i18n/enum-label "okind" (:kind o)) " · " (i18n/enum-label "level" (:level o))]
          [:p.muted (let [x (fold/plain-text (str (:text o)))] (if (> (count x) 160) (str (subs x 0 159) "…") x))]
          [:button.restore {:on-click #(when (nil? (store/append! (ops/object-status (:id o) :active)))
                                         (snack! (t :ui/restored)))}
           (t :ui/restore-material)]])])))

(defn- answer-form
  "One open expert question: the answer, and optionally the same text as
   material on the topic (an expert's answer is often the missing paragraph)."
  [_ _]
  (let [answer (r/atom "") as-material (r/atom false)]
    (fn [kg {:keys [id concept-id]}]
      [:div.answer-form
       [:textarea {:placeholder (t :ui/expert-answer-placeholder) :default-value @answer
                   :on-change #(reset! answer (.. % -target -value))}]
       [:label.check [:input {:type "checkbox" :checked @as-material
                              :on-change #(reset! as-material (.. % -target -checked))}]
        " " (t :ui/answer-as-material)]
       [:button.primary.answer-save
        {:disabled (str/blank? @answer)
         :on-click (fn [_]
                     (when (nil? (store/append! (ops/answer-question id (str/trim @answer))))
                       (when @as-material
                         (store/append! (ops/add-object {:id (str "o-" (random-uuid)) :concept-id concept-id
                                                         :kind :explanation :level :apprentice
                                                         :locale (store/locale-for-new) :origin :authored
                                                         :text (str/trim @answer)})))
                       (snack! (t :ui/answered))))}
        (t :ui/answer-save)]])))

(defn- expert-questions
  "What the intake could not settle from the material and asked the expert
   (question-raised) — answered here (question-answered). Answered ones stay
   visible: the assistant and the MCP server read them too."
  [kg id]
  (let [qs (sort-by :question (keep (fn [[qid q]] (when (= id (:concept-id q)) (assoc q :id qid))) (:questions kg)))
        open (remove :answer qs)]
    (when (seq qs)
      [:div.expert-questions
       [:h3 [icons/icon :question] (t :ui/expert-heading {"{n}" (count open)})]
       (for [q qs]
         ^{:key (:id q)}
         [:div.expert-q {:class (if (:answer q) "answered" "open")}
          [:p.q (:question q)]
          (if (:answer q)
            [:p.a.muted "→ " (:answer q)]
            [answer-form kg q])])])))

(defn- split-form
  "One topic becomes several (concept-split): new titles and definitions,
   and where each piece of material and each question goes. Learners keep
   their record — attempts follow their item. The old topic's links are
   dropped by the fold; they are listed here so the author can redraw them."
  [_ c done]
  (let [rows (r/atom [{:title (:title c) :definition (:definition c)} {:title "" :definition ""}])
        assign (r/atom {})]                          ; item/object id -> row index
    (fn [kg c done]
      (let [id (:id c)
            items (sort-by :id (filter #(= id (:primary-concept-id %)) (vals (:items kg))))
            objs (sort-by :id (filter #(= id (:concept-id %)) (vals (:objects kg))))
            live-items (filter #(= :active (:status %)) items)
            live-objs (filter #(= :active (:status %)) objs)
            ;; the first part keeps the topic's id: links and bookmarks to it still land
            ids (assoc (mapv #(slug (:title %)) @rows) 0 id)
            filled (filter (comp seq str/trim :title) @rows)
            taken (some #(and (seq %) (not= id %) (get-in kg [:concepts %])) ids)
            dup (not= (count (remove str/blank? ids)) (count (distinct (remove str/blank? ids))))
            ok? (and (= (count filled) (count @rows)) (>= (count @rows) 2) (not taken) (not dup))
            row-of (fn [k] (get @assign k 0))
            edges (filter #(and (not= :grounded-in (:type %)) (or (= id (:from %)) (= id (:to %)))) (:edges kg))
            select (fn [k]
                     [:select.split-to {:value (str (row-of k))
                                        :on-change #(swap! assign assoc k (js/parseInt (.. % -target -value)))}
                      (for [[i r] (map-indexed vector @rows)]
                        ^{:key i} [:option {:value (str i)} (if (str/blank? (:title r)) (str "#" (inc i)) (:title r))])])]
        [:div.form.split-form
         [:h3 (t :ui/split-heading)]
         [:p.muted (t :ui/split-hint)]
         (for [[i r] (map-indexed vector @rows)]
           ^{:key i}
           [:div.split-row
            [:input {:placeholder (t :ui/topic-name-placeholder) :default-value (:title r)
                     :on-change #(swap! rows assoc-in [i :title] (.. % -target -value))}]
            [:textarea {:placeholder (t :ui/definition-placeholder) :default-value (:definition r)
                        :on-change #(swap! rows assoc-in [i :definition] (.. % -target -value))}]])
         [:button.split-add {:on-click #(swap! rows conj {:title "" :definition ""})} (t :ui/split-add-row)]
         (when (or (seq live-objs) (seq live-items))
           [:div.split-assign
            [:h4 (t :ui/split-assign)]
            (for [o live-objs]
              ^{:key (:id o)}
              [:div.split-piece [:span.muted (i18n/enum-label "okind" (:kind o)) " · "]
               (let [x (fold/plain-text (str (:text o)))] (if (> (count x) 70) (str (subs x 0 69) "…") x))
               (select (:id o))])
            (for [i live-items]
              ^{:key (:id i)}
              [:div.split-piece [:span.muted (i18n/enum-label "ikind" (:kind i)) " · "]
               (or (get-in i [:spec :stem]) (get-in i [:spec :text]))
               (select (:id i))])])
         (when (or (not= (count items) (count live-items)) (not= (count objs) (count live-objs)))
           [:p.muted (t :ui/split-inactive-first)])
         (when (seq edges)
           [:p.muted.split-edges (t :ui/split-edges-dropped)
            (str/join ", " (for [e edges] (str (edge-label kg (:type e)) " "
                                               (get-in kg [:concepts (if (= id (:from e)) (:to e) (:from e)) :title]))))])
         (when taken [:p.error-inline (t :ui/split-id-taken {"{id}" taken})])
         (when dup [:p.error-inline (t :ui/split-dup)])
         [:div.row
          [:button.primary.split-save
           {:disabled (not ok?)
            :on-click (fn [_]
                        (let [parts (vec (for [[i r] (map-indexed vector @rows)]
                                           (cond-> {:id (nth ids i) :title (str/trim (:title r))
                                                    :definition (str/trim (str (:definition r)))}
                                             (:kind c) (assoc :kind (:kind c)))))
                              remap (fn [xs] (into {} (for [x xs] [(:id x) (:id (nth parts (row-of (:id x))))])))]
                          (when (nil? (store/append! (ops/split id parts (remap items) (remap objs))))
                            (snack! (t :ui/split-done {"{n}" (count parts)}))
                            (reset! done false)
                            (goto! (str "#/c/" (js/encodeURIComponent (:id (first parts))))))))}
           (t :ui/split-save)]
          [:button {:on-click #(reset! done false)} (t :ui/cancel)]]]))))

(defn- topic-actions
  "The topic's rare, structural moves: split it, or mark it deprecated —
   kept on the map and in history, never published again (one-way, behind
   the same 6 s ring as a removal)."
  [_ _]
  (let [splitting (r/atom false)]
    (fn [kg c]
      (let [id (:id c)
            dep? (= :deprecated (:status c))
            k (str "dep-" id)
            pending? (contains? @del-pending k)]
        [:div.topic-actions
         (when @splitting [split-form kg c splitting])
         (when-not @splitting
           [:div.row
            ;; a type in an ontology graph is not split; its relations are its edges
            (when-not (:ontology-graph? kg)
              [:button.split-open {:on-click #(reset! splitting true)} (t :ui/split-open)])
            (if dep?
              [:span.muted.deprecated-note (t :ui/deprecated-note)]
              [:button.deprecate {:class (when pending? "pending")
                                  :on-click #(del-toggle! k (ops/deprecate-node id) :ui/deprecated-done)}
               (t (if pending? :ui/cancel :ui/deprecate))])])]))))

;; --- node properties (wiki/Property-Graph.md) --------------------------------

(def ^:private core-props fold/node-core)

(defn- kv-editor
  "Properties as an aligned grid (Cloudscape's attribute-editor shape): key
   and value side by side, × at the row's end, the last row adds (+, or
   Enter). `put!` takes a property map — an emptied value removes (nil), a
   renamed key moves its value. Inputs are semi-controlled — the map redraws
   every tick and a controlled input drops keys there."
  [_ _ _ _]
  (let [new-k (r/atom "") new-v (r/atom "") gen (r/atom 0)]
    (fn [rkey props put! core]
      (let [add! (fn []
                   (let [k (keyword (slug @new-k)) v (str/trim @new-v)]
                     (when (and (seq (name k)) (seq v) (not (core k)) (nil? (put! {k v})))
                       (reset! new-k "") (reset! new-v "") (swap! gen inc))))
            enter-blurs #(when (= "Enter" (.-key %)) (.blur (.-target %)))]
        [:div.kv-grid
         (for [[k v] props]
           ^{:key (str rkey k v)}
           [:div.kv-row.prop-row
            [:input.prop-key {:default-value (name k) :aria-label (t :ui/prop-key)
                              :on-key-down enter-blurs
                              :on-blur #(let [nk (keyword (slug (.. % -target -value)))]
                                          (when (and (seq (name nk)) (not= nk k) (not (core nk)))
                                            (put! {k nil nk v})))}]
            [:input.prop-value {:default-value (str v) :aria-label (t :ui/prop-value)
                                :on-key-down enter-blurs
                                :on-blur #(let [x (str/trim (.. % -target -value))]
                                            (when (not= x (str v)) (put! {k (when (seq x) x)})))}]
            [:button.icon-btn.prop-x {:title (t :ui/delete) :aria-label (t :ui/delete) :on-click #(put! {k nil})}
             [icons/icon :close]]])
         ^{:key (str "add-" rkey "-" @gen)}
         [:div.kv-row.prop-add
          [:input.prop-new-key {:placeholder (t :ui/prop-key) :default-value ""
                                :on-change #(reset! new-k (.. % -target -value))}]
          [:input.prop-new-value {:placeholder (t :ui/prop-value) :default-value ""
                                  :on-change #(reset! new-v (.. % -target -value))
                                  :on-key-down #(when (= "Enter" (.-key %)) (add!))}]
          [:button.icon-btn.prop-add-btn {:title (t :ui/add) :aria-label (t :ui/add) :on-click #(add!)}
           [icons/icon :plus]]]]))))

(defn- node-props
  "A node's properties in the popup: style first — colour and icon,
   overriding its graph's and ontology's (in an ontology graph they are the
   type's defaults, plus «learnable») — then its own properties. Every edit
   is one `node-set` op."
  [kg c]
  (let [id (:id c)
        put! (fn [props] (store/append! {:ev/type :change :ops [[:node-set id props]]}))]
    [:section.node-props
     [:h3 (t :ui/properties)]
     [style-controls (node-style kg c) (select-keys c [:color :icon]) put!]
     (when (:ontology-graph? kg)
       [:label.check.learnable-flag
        [:input.prop-learnable {:type "checkbox" :checked (true? (:learnable? c))
                                :on-change #(put! {:learnable? (when (.. % -target -checked) true)})}]
        " " (t :ui/learnable)])
     [kv-editor id (fold/node-props c) put! fold/node-core]]))

;; the edge whose properties are open in its source node's popup: [from type to]
(defonce edge-open (r/atom nil))

(defn- edge-props-panel
  "An edge's own properties (2026-11-19): the same grid as a node's, under
   the link chip; every edit is one `edge-set` op."
  [kg {:keys [from to type] :as e}]
  [:div.edge-props
   [:p.muted.edge-props-head (str (get-in kg [:concepts from :title] from) " — " (edge-label kg type) " → "
                                  (get-in kg [:concepts to :title] to))]
   [kv-editor (str from type to) (fold/edge-props e)
    #(store/append! {:ev/type :change :ops [[:edge-set from type to %]]})
    fold/edge-core]])

(defn- relation-props
  "In an ontology graph, the relations touching this type, edited in place:
   their words and flags are properties of every edge of the relation, so
   one edit writes them all in one `change`."
  [kg id]
  (let [mine (into #{} (comp (filter #(or (= id (:from %)) (= id (:to %))))
                             (map :type) (remove #{:grounded-in}))
                   (:edges kg))]
    (when (seq mine)
      [:section.relation-props
       [:h3 (t :ui/relations)]
       (for [rt (sort-by str mine)
             :let [all (filter #(= rt (:type %)) (:edges kg))
                   w (apply merge (map #(select-keys % [:label :inverse :ordering? :acyclic?]) all))
                   put! (fn [props] (store/append! {:ev/type :change
                                                    :ops (vec (for [e all] [:edge-set (:from e) rt (:to e) props]))}))
                   text (fn [cls k ph]
                          [:input {:class cls :placeholder ph :default-value (or (get w k) "")
                                   :on-key-down #(when (= "Enter" (.-key %)) (.blur (.-target %)))
                                   :on-blur #(let [v (str/trim (.. % -target -value))]
                                               (when (not= v (or (get w k) "")) (put! {k (when (seq v) v)})))}])
                   flag (fn [cls k label]
                          [:label.check [:input {:class cls :type "checkbox" :checked (true? (get w k))
                                                 :on-change #(put! {k (when (.. % -target -checked) true)})}]
                           " " label])]]
         ^{:key (str rt (:label w) (:inverse w))}
         [:div.relation-row
          [:div.relation-head
           ;; its id (what exports and the agent read): renamed here, the links
           ;; of every graph using this ontology follow (store/rename-relation!)
           [:input.rel-id {:default-value (name rt) :aria-label (t :ui/rel-id) :title (t :ui/rel-id)
                           :spell-check false
                           :on-key-down #(when (= "Enter" (.-key %)) (.blur (.-target %)))
                           :on-blur #(store/rename-relation! rt (.. % -target -value))}]
           ;; the whole relation: every edge of it, in one change
           [:button.icon-btn.rel-delete
            {:title (t :ui/delete) :aria-label (t :ui/delete)
             :on-click #(when (js/confirm (t :ui/rel-delete-confirm {"{label}" (or (:label w) (name rt))}))
                          (store/append! {:ev/type :change
                                          :ops (vec (for [e all] [:edge-remove (:from e) rt (:to e)]))}))}
            [icons/icon :trash]]]
          (text "rel-label" :label (t :ui/rel-label-placeholder))
          (text "rel-inverse" :inverse (t :ui/rel-inverse-placeholder))
          (flag "rel-ordering" :ordering? (t :ui/rel-ordering))
          (flag "rel-acyclic" :acyclic? (t :ui/rel-acyclic))])])))

(defn concept-panel [kg id]
  (let [c (get-in kg [:concepts id])]
    (if-not c
      [:div.panel [:p (t :ui/topic-not-found)] [:a {:href "#/"} (t :ui/back-to-map)]]
      (let [status (fold/concept-status kg id)
            p (fold/profile kg)
            learnable? (profile/learnable? p)]
        [:div.panel
         [:div.panel-head
          [win-title win-size (:title c)]
          ;; a custom ontology names the node's type where learning shows status
          (cond
            (:ontology-graph? kg) [:span.badge.node-type-badge (t :ui/ontology-type)]
            (not (profile/learning? p)) [:span.badge.node-type-badge (profile/kind-label p (:kind c))])
          (when-not (:ontology-graph? kg)
            [:span.badge {:class (status-class status)}
             (t ({:minimum-viable :ui/status-ready :accepted :ui/status-accepted
                  :stale :ui/status-stale :deprecated :ui/status-deprecated :draft :ui/status-draft} status))])
          [win-tools win-size]]
         [:div.def-row
          [editable concept-edit-form c
           [:p.def (:definition c)]]]
         ^{:key (str "props-" id)} [node-props kg c]
         (when (:ontology-graph? kg) [relation-props kg id])
         ;; the competency questions this node leaves unanswered (wiki/Profiles.md)
         (when-let [qs (seq (get (fold/cq-gaps kg) id))]
           [:p.node-gaps (t :ui/node-gaps) ": " (str/join " · " (map :question qs))])
         ;; who added and changed it — once anyone in this graph has a name
         ;; (an anonymous device id alone is noise, not an answer)
         (when (and (:added-by c) (seq (:authors kg)))
           [:p.muted.authorship
            (t :ui/authorship {"{a}" (fold/author-name kg (:added-by c))
                               "{b}" (fold/author-name kg (:changed-by c))
                               "{d}" (or (gt/day (:changed-at c)) "")})])
         [:div.links
          (for [{:keys [to type] :as e} (filter #(and (= id (:from %)) (not= :grounded-in (:type %)))
                                                (fold/edges-at kg id))]
            ^{:key (str to type)}
            [:span.chip {:class (when (= @edge-open [id type to]) "on") :on-click #(goto! (str "#/c/" to))}
             (str (edge-label kg type) " → " (get-in kg [:concepts to :title] to))
             (when-let [n (not-empty (fold/edge-props e))] [:span.chip-n (count n)])
             [:button.chip-props {:title (t :ui/properties) :aria-label (t :ui/properties)
                                  :on-click #(do (.stopPropagation %)
                                                 (swap! edge-open (fn [o] (when (not= o [id type to]) [id type to]))))}
              [icons/icon :rows]]
             [edge-x e]])
          ;; incoming: learning shows what this topic unlocks; a custom
          ;; ontology reads every relation back with its inverse label — even
          ;; one whose node type is learnable: it can never use `requires`,
          ;; and the filter showed it nothing (review 2026-10-16)
          (for [{:keys [from type] :as e} (filter #(and (= id (:to %))
                                                        (if (profile/learning? p) (= :requires (:type %)) (not= :grounded-in (:type %))))
                                                  (fold/edges-at kg id))]
            ^{:key (str "in" from type)}
            [:span.chip.chip-in {:class (when (= @edge-open [from type id]) "on") :on-click #(goto! (str "#/c/" from))}
             (if (profile/learning? p)
               (t :ui/opens-arrow {"{title}" (get-in kg [:concepts from :title] from)})
               (str (edge-label kg type true) " ← " (get-in kg [:concepts from :title] from)))
             (when-let [n (not-empty (fold/edge-props e))] [:span.chip-n (count n)])
             [:button.chip-props {:title (t :ui/properties) :aria-label (t :ui/properties)
                                  :on-click #(do (.stopPropagation %)
                                                 (swap! edge-open (fn [o] (when (not= o [from type id]) [from type id]))))}
              [icons/icon :rows]]
             [edge-x e]])]
         ;; the open edge's own properties, under the chips
         (when-let [[f ty to] @edge-open]
           (when-let [e (first (filter #(= [f ty to] [(:from %) (:type %) (:to %)]) (:edges kg)))]
             (when (#{f to} id) ^{:key (str "ep" f ty to)} [edge-props-panel kg e])))
         ;; a type in an ontology graph carries no learning material
         (when-not (:ontology-graph? kg)
          [:<>
         [:h3 [icons/icon :notes] (t :ui/lo-heading {"{n}" (count (fold/concept-objects kg id))})]
         (for [o (fold/concept-objects kg id)]
           ^{:key (:id o)}
           [:div.lo
            [editable object-edit-form o
             [:span.lo-kind (i18n/enum-label "okind" (:kind o)) " · " (i18n/enum-label "level" (:level o))
                     (when (seq (:audience o)) (str (t :ui/audience-prefix) (str/join ", " (sort (:audience o)))))
                     (case (:origin o)
                       :drafted (t :ui/origin-drafted)
                       :generated (t :ui/origin-generated)
                       nil)
                     (when-let [src (:grounded-in o)]
                       [:<> " · " [:a.srclink {:href (src-href src (:anchor o))}
                                   (str (t :ui/source-word) (when (:anchor o) (str " · " (:anchor o))))]])]
             ;; the author always sees the marks: they are the author's to check
             [:p (fold/marks->hiccup (:text o) true)]]
            [rm-btn (:id o) (ops/object-status (:id o) :demoted)]
            ^{:key (str "links-" (:id o))} [object-links kg o]])
         ^{:key (str "removed-" id)} [removed-material kg id]
         ;; keyed by topic: the panel swaps topics in place, and an unkeyed form
         ;; kept the FIRST topic it was opened on — a new question went there
         ^{:key (str "lo-" id)} [add-object-form kg id]])
         (let [srcs (for [e (:edges kg) :when (and (= id (:from e)) (= :grounded-in (:type e)))] (:to e))]
           (when (seq srcs)
             [:<>
              [:h3 [icons/icon :clip] (t :ui/sources-heading)]
              (for [sid (distinct srcs)]
                ^{:key sid} [:a.chip {:href (src-href sid (:anchor (get-in kg [:concepts id])))}
                             (get-in kg [:sources sid :title] sid)])]))
         ;; questions and the bench exist for LEARNABLE nodes only — a
         ;; business graph has nothing to examine (wiki/Profiles.md)
         (when learnable?
           [:<>
            [:h3 [icons/icon :question] (t :ui/questions-heading {"{n}" (count (fold/concept-items kg id))})]
            (for [i (fold/concept-items kg id)]
              ^{:key (:id i)}
              [:div.item
               [editable item-edit-form i
                [:span.lo-kind (i18n/enum-label "ikind" (:kind i))]
                [:p (or (get-in i [:spec :stem]) (get-in i [:spec :text]))]]
               [rm-btn (:id i) (ops/item-status (:id i) :retired)]])
            ^{:key (str "item-" id)} [add-item-form kg id]
            ^{:key (str "bench-" id)} [draft-exercises-row kg id]])
         ^{:key (str "expert-" id)} [expert-questions kg id]
         ^{:key (str "actions-" id)} [topic-actions kg c]]))))

;; --- source viewer: the stored extraction, page-marked, plus who cites it ----

(defn- pages
  "Split page-marked text into [n text]; unmarked text is one page 0."
  [text]
  ;; i18n-ignore: "[стр. N]" is the ingestion's stored page-marker format, not UI copy
  (let [parts (str/split text #"(?=\[стр\. \d+\])")]
    (for [p parts :when (seq (str/trim p))]
      ;; i18n-ignore: same page-marker format, matched back out of the text
      [(or (some-> (re-find #"^\[стр\. (\d+)\]" p) second js/parseInt) 0) p])))

(defn source-panel [_ _ _]
  (let [text (r/atom nil) url (r/atom nil)
        load! (fn [id ref]
                (p/let [t (blobs/get-text (str id ".md"))
                        f (when ref (blobs/get-file ref))]
                  (reset! text (or t ""))
                  (reset! url (when f (js/URL.createObjectURL f)))))
        jump! (fn [anchor]
                (when-let [el (and (seq anchor) (js/document.getElementById (str "src-" anchor)))]
                  (.scrollIntoView el #js {:block "start"})))]
    (r/create-class
     {:component-did-mount
      (fn [this] (let [[_ kg id] (r/argv this)] (load! id (get-in kg [:sources id :ref]))))
      :component-did-update
      (fn [this] (let [[_ _ _ anchor] (r/argv this)] (when @text (jump! anchor))))
      :reagent-render
      (fn [kg id anchor]
        (if-let [s (get-in kg [:sources id])]
          (let [concepts (distinct (for [e (fold/edges-at kg id) :when (and (= id (:to e)) (= :grounded-in (:type e)))] (:from e)))
                objects (filter #(= id (:grounded-in %)) (vals (:objects kg)))
                ;; a big source has thousands drafted from it: 52 000 chips held this page 5 s at 40k
                shown 200
                more (fn [xs] (when (> (count xs) shown) [:span.muted (t :ui/chips-more {"{n}" (- (count xs) shown)})]))]
            [:div.panel.source
             [:div.panel-head
              [win-title win-size (:title s)]
              [win-tools win-size]]
             [:p.muted (str (t :ui/version-word) " " (or (:version s) 1) " · " id
                            (when @text (str " · " (count @text) (t :ui/chars-suffix))))
              (when @url [:<> " · " [:a {:href @url :download (:ref s)} (t :ui/download-original)]])]
             (when (seq concepts)
               [:<> [:h3 [icons/icon :notes] (t :ui/topics-on-source-heading)]
                (for [cid (take shown concepts)]
                  ^{:key cid} [:span.chip {:on-click #(goto! (str "#/c/" cid))}
                               (get-in kg [:concepts cid :title] cid)])
                (more concepts)])
             (when (seq objects)
               [:<> [:h3 [icons/icon :notes] (t :ui/lo-heading-plain)]
                (for [o (take shown objects)]
                  ^{:key (:id o)} [:span.chip {:on-click #(goto! (str "#/c/" (:concept-id o)))}
                                   (str (get-in kg [:concepts (:concept-id o) :title]) " · " (i18n/enum-label "okind" (:kind o))
                                        (when (:anchor o) (str " · " (:anchor o))))])
                (more objects)])
             [:h3 [icons/icon :clip] (t :ui/text-heading)]
             (cond
               (nil? @text) [:p.muted (t :ui/loading-text)]
               (= "" @text) [:p.muted (t :ui/no-extracted-text)]
               :else (for [[n p] (pages @text)]
                       ^{:key n} [:pre.srcpage {:id (str "src-p" n)
                                                :class (when (= anchor (str "p" n)) "hit")} p]))])
          [:div.panel [:p (t :ui/source-not-found)] [:a {:href "#/"} (t :ui/back-to-map)]]))})))

;; --- course panel: compose (new) or inspect (existing) -----------------------

(defn level-labels [l] (i18n/enum-label "level" l))

(defn- ready-badge [{:keys [ready? stale?]}]
  [:span.badge {:class (cond ready? "tile-ready" stale? "tile-stale" :else "tile-empty")}
   (cond ready? (t :ui/ready) stale? (t :ui/status-stale) :else (t :ui/not-ready))])

(defn new-course-form [kg]
  ;; the course's language defaults to the graph's: a course deals only items
  ;; in its own language, so "ru" on an English graph dealt nothing
  (let [title (r/atom "") threshold (r/atom 80) locale (r/atom (store/locale-for-new))
        picked chat/course-draft]                   ; concept-id -> level, shared with the intake
    (fn [kg]
      (let [concepts (sort-by (comp :title val) (:concepts kg))]
        [:div.panel
         [:div.panel-head
          [win-title win-size (t :ui/new-course)]
          [win-tools win-size]]
         ;; uncontrolled: nothing else writes the title, and a controlled input
         ;; here dropped keystrokes while the (heavier) panel re-rendered
         [:input {:placeholder (t :ui/course-name-placeholder) :default-value "" :ref focus!
                  :on-change #(reset! title (.. % -target -value))}]
         [:div.row
          [:label.pair [:span.muted (t :ui/pass-threshold-label)]
           [:input.short {:type "number" :min 1 :max 100 :value @threshold
                          :on-change #(reset! threshold (js/parseInt (.. % -target -value)))}]]
          [:label.pair [:span.muted (t :ui/language-label)]
          [:select {:value @locale :on-change #(reset! locale (.. % -target -value))}
           ;; the graph's content languages first, then the interface ones
           (for [l (distinct (concat (sort (distinct (keep :locale (concat (vals (:items kg)) (vals (:objects kg)) (vals (:courses kg))))))
                                     (map name i18n/locales)))]
             ^{:key l} [:option {:value l} (i18n/lang-name l)])]]]
         ;; the assistant door: describe the course, accept target cards
         [chat/course-composer kg]
         [:h3 [icons/icon :notes] (t :ui/course-goals-heading)]
         (if (empty? concepts)
           [:p.muted (t :ui/add-topics-first)]
           (for [[id c] concepts]
             (let [lvl (get @picked id)]
               ^{:key id}
               [:div.crow {:data-id id}
                [:input {:type "checkbox" :checked (some? lvl)
                         :on-change #(swap! picked (fn [m] (if lvl (dissoc m id) (assoc m id :apprentice))))}]
                [:span.crow-title {:title (:definition c)} (:title c)]
                [:select {:value (name (or lvl :apprentice)) :disabled (nil? lvl)
                          :on-change #(swap! picked assoc id (keyword (.. % -target -value)))}
                 (for [l [:novice :apprentice :professional]]
                   ^{:key l} [:option {:value (name l)} (level-labels l)])]
                (when lvl [ready-badge (first (fold/course-readiness kg {:targets [[id lvl]] :locale @locale}))])])))
         ;; only picks whose topic is in THIS graph: the draft is shared and
         ;; outlives a graph switch or a removed topic, and an invisible pick
         ;; (no row to untick) blocked publishing (review 2026-10-16)
         (let [targets (vec (for [[id lvl] @picked :when (contains? (:concepts kg) id)] [id lvl]))
               plan (fold/plan-readiness kg {:targets targets :locale @locale})
               ready (count (filter :ready? (filter :target? plan)))
               prereqs (remove :target? plan)]
           [:div.course-summary
            (when (seq targets)
              [:p.muted (str (t :ui/course-summary {"{n}" (count targets) "{m}" ready})
                             (when (seq prereqs)
                               (t :ui/course-summary-prereqs
                                  {"{n}" (count prereqs) "{m}" (count (filter :ready? prereqs))})))])
            ;; PUBLISHING IS GATED ON READINESS: a target with no question at
            ;; its level cannot be examined, so a course carrying one can only
            ;; issue an incomplete verdict. Name the blockers rather than just
            ;; greying the button out.
            (let [blocking (remove :ready? (filter :target? plan))
                  names #(str/join ", " (map (fn [r] (get-in kg [:concepts (:concept r) :title] (:concept r))) %))
                  ;; a deprecated PREREQUISITE blocks too: its learners were
                  ;; taught a retired topic with no word (review 2026-10-16 A14)
                  dep (filter :deprecated? plan)
                  {stale true thin false} (group-by (comp boolean :stale?) (remove :deprecated? blocking))]
              [:<>
               (when (seq dep) [:p.muted.untested.deprecated-block (t :ui/publish-blocked-deprecated {"{list}" (names dep)})])
               (when (seq thin) [:p.muted.untested (t :ui/publish-blocked {"{list}" (names thin)})])
               (when (seq stale) [:p.muted.untested.stale-block (t :ui/publish-blocked-stale {"{list}" (names stale)})])])
            [:div.row
             [:button.primary
              {:disabled (or (str/blank? @title) (empty? targets)
                             (seq (remove :ready? (filter :target? plan)))
                             (some :deprecated? plan))
               :on-click (fn [_]
                           ;; a NEW course never takes an existing id: course-published
                           ;; folds as an assoc, and «SQL: основы» replaced «SQL — основы»
                           ;; with everyone's runs and progress (review 2026-10-16 A6)
                           (let [id (fresh-id @title "c" (set (keys (:courses kg))))]
                             (when-not (store/append!
                                        (ops/publish-course {:id id :title @title :targets targets
                                                             :locale @locale :pass-threshold @threshold}))
                               (snack! (t :ui/course-published {"{title}" @title}))
                               (reset! picked {})
                               (reset! chat/target-cards [])
                               (goto! (str "#/course/" (js/encodeURIComponent id))))))}
              (t :ui/publish-course)]
             [:a.btn {:href "#/"} (t :ui/cancel)]]])]))))

(defn course-panel [kg id]
  (if (nil? id)
    [new-course-form kg]
    (if-let [course (get-in kg [:courses id])]
      (let [plan (fold/plan-readiness kg course)
            targets (filter :target? plan)
            prereqs (remove :target? plan)
            ready (count (filter :ready? targets))
            row (fn [{:keys [concept level] :as r}]
                  ^{:key concept}
                  [:div.crow
                   [:span.crow-title {:on-click #(goto! (str "#/c/" concept))}
                    (get-in kg [:concepts concept :title] concept)]
                   [:span.lo-kind (level-labels level)]
                   [ready-badge r]])]
        [:div.panel
         [:div.panel-head
          [win-title win-size (:title course)]
          [:span.badge {:class (if (= ready (count targets)) "tile-ready" "tile-empty")}
           (t :ui/course-ready-count {"{n}" ready "{m}" (count targets)})]
          [win-tools win-size]]
         ;; the learner's door — the course is only real once someone can take it
         [:div.row
          [:a.btn.primary.take-course {:href (str "#/learn/" id)} [icons/icon :graph] (t :ui/take-course)]
          ;; the other half of publish-and-pin: without this the cut can never
          ;; advance, so edits made after publishing reach NOBODY, ever
          (let [head (store/graph-events @store/current)
                cut (fold/publication-cut head id)
                changed (fold/course-changes kg head cut course)]
            [:button.btn.republish
             {:disabled (or (zero? changed) (not (every? :ready? targets)) (some :deprecated? prereqs))
              :title (if (and (every? :ready? targets) (not-any? :deprecated? prereqs))
                       (t :ui/republish-hint {"{n}" changed})
                       (t :ui/republish-blocked))
              :on-click (fn [_]
                          (when (nil? (store/append!
                                       (ops/publish-course (select-keys course [:id :title :targets :locale
                                                                                :pass-threshold :diagnostic-items
                                                                                :final-items :series]))))
                            (snack! (t :ui/republished {"{n}" changed}))))}
             [icons/icon :open-ext]
             (if (zero? changed) (t :ui/republish-none) (t :ui/republish {"{n}" changed}))])]
         [:p.muted (t :ui/course-meta {"{n}" (or (:pass-threshold course) "—")
                                        "{lang}" (if (:locale course) (i18n/lang-name (:locale course)) (t :ui/any))})]
         [:h3 [icons/icon :notes] (t :ui/goals-heading)]
         (map row targets)
         (when (seq prereqs)
           [:<>
            [:h3 [icons/icon :link] (t :ui/prereqs-heading {"{n}" (count prereqs)})]
            [:p.muted (t :ui/prereqs-note)]
            (map row prereqs)])])
      [:div.panel [:p (t :ui/course-not-found)] [:a {:href "#/"} (t :ui/back-to-map)]])))

;; --- command palette (kintale cmd-*) -----------------------------------------

;; One action makes any graph: a title and its ontology (Denis 2026-11-19 —
;; no kind of graph is special). The built-in learning ontology, the
;; built-in one for describing types, or any whole graph of the set that
;; describes types.
(defonce new-graph-form (r/atom nil))   ; nil | {:ontology id}

(defn- new-graph-asked!
  ([] (new-graph-asked! "learning"))
  ([ontology] (reset! new-graph-form {:ontology ontology})))

(defn- new-ontology-asked! [] (new-graph-asked! fold/types-ontology))

(defn- ontology-name
  ([oid] (ontology-name oid false))
  ([oid choice?]
   (cond (or (nil? oid) (= "learning" oid)) (t :ui/ontology-learning-builtin)
         (= fold/types-ontology oid) (t (if choice? :ui/ontology-types-choice :ui/ontology-types-builtin))
         :else (store/graph-title oid))))

(defn new-graph-modal []
  (when-let [{:keys [ontology]} @new-graph-form]
    (let [title (r/atom "")]
      (fn []
        (when-let [{:keys [ontology]} @new-graph-form]
          ;; read on every render: an ontology may have become whole since mount
          (let [choices (concat ["learning" fold/types-ontology]
                                (map :id (filter #(store/whole-ontology? (:id %)) (store/ontology-graphs))))]
          [:div.modal {:on-click #(reset! new-graph-form nil)}
           [:div.form.new-graph-form {:on-click #(.stopPropagation %)}
            [:h3 [icons/icon :graph] " " (t :ui/new-graph)]
            [:input.new-graph-title {:placeholder (t :ui/new-graph-prompt) :default-value "" :auto-focus true
                                     :on-change #(reset! title (.. % -target -value))}]
            [:label (t :ui/ontology) " "
             [:select.new-graph-ontology {:value ontology
                                          :on-change #(swap! new-graph-form assoc :ontology (.. % -target -value))}
              (for [o choices] ^{:key o} [:option {:value o} (ontology-name o true)])]]
            [:div.row
             [:button.primary.new-graph-create
              {:on-click (fn [_]
                           (let [tt (str/trim @title)]
                             (when (seq tt)
                               (reset! new-graph-form nil)
                               (cond
                                 (= "learning" ontology) (do (store/new-graph! tt) (goto! "#/"))
                                 (= fold/types-ontology ontology) (do (store/new-ontology-graph! tt) (goto! "#/ontology"))
                                 :else (when-let [gid (store/new-graph! tt)]
                                         (when-let [err (store/bind! gid ontology)] (reset! store/last-error err))
                                         (goto! "#/"))))))}
              (t :ui/create)]
             [:button {:on-click #(reset! new-graph-form nil)} (t :ui/cancel)]]]]))))))

(defn- learner-palette
  "On the learner's screens the palette is the learner's (learning review
   2026-11-20 D2: it led with new topic, new graph, undo…): every course on
   this device, past runs, and the way back to the author's map."
  [kg]
  (concat
   [{:label (t :ui/courses) :icon :notes :run #(goto! "#/courses")}]
   (for [g (store/graph-kgs) [cid c] (get-in g [:kg :courses])]
     {:label (:title c) :icon :notes :hint (t :ui/course-word)
      :run #(goto! (str "#/learn/" (js/encodeURIComponent cid)))})
   (for [s (reverse (learn/past-runs kg))]
     {:label (str (:title s) " · " (t :learn/round {"{n}" (:round s)}))
      :icon :notes
      :group (t :ui/hat-learn)
      :hint (str (t :learn/session-word) " · "
                 (t (case (:status s) :passed :learn/session-status-passed :failed :learn/session-status-failed :learn/session-status-open))
                 (when (:current? s) (str " · " (t :ui/session-now))))
      :run #(do (learn/open-run! s) (goto! (str "#/learn/" (:course s))))})
   [{:label (t :ui/palette-to-author) :icon :graph :run #(goto! "#/")}]))

(declare author-palette)

(declare topic-rows)

(defn palette-actions [kg]
  (if (#{:catalog :learn} (:view @route))
    (learner-palette kg)
    (author-palette kg)))

(defn- author-palette [kg]
  (concat
   [{:label (t :ui/map) :icon :graph :run #(goto! "#/")}
    {:label (t :ui/courses) :icon :notes :run #(goto! "#/courses")}
    {:label (t :ui/assistant-intake) :icon :chat :run #(goto! "#/chat")}
    {:label (t :ui/new-topic) :icon :plus :run #(reset! show-new-concept true)}
    {:label (t :ui/new-link) :icon :link :run #(reset! link-mode {:from nil})}
    {:label (t :ui/new-course) :icon :plus :run #(goto! "#/course/new")}
    {:label (t :ui/new-graph) :icon :graph :run #(new-graph-asked!)}
    {:label (t :ui/undo) :icon :back :hint "Ctrl+Z" :run #(undo-asked!)}
    {:label (t :ui/redo) :icon :back :hint "Ctrl+Shift+Z" :run #(redo-asked!)}
    {:label (t :ui/new-ontology) :icon :lineage :run new-ontology-asked!}
    {:label (t :ui/export-log) :icon :open-ext :run store/export-edn!}
    ;; blobs never go to git: copy them to a folder of the curator's choosing
    {:label (t :ui/save-sources-to-folder) :icon :inbox
     :run #(-> (blobs/save-all-to-folder!)
               (p/then (fn [n] (snack! (t :ui/files-saved {"{n}" n}))))
               (p/catch (fn [_] nil)))}]
   (for [[id s] (:sources kg)]
     {:label (:title s) :icon :clip :hint (t :ui/source-word)
      :run #(goto! (src-href id nil))})
   (for [[id c] (:courses kg)]
     {:label (:title c) :icon :notes :hint (t :ui/course-word)
      :run #(goto! (str "#/course/" id))})
   ;; sessions are grouped by HAT, not interleaved: learning runs belong to
   ;; the learner, assistant sittings to the author
   (for [s (reverse (learn/past-runs kg))]
     {:label (str (:title s) " · " (t :learn/round {"{n}" (:round s)}))
      :icon :notes
      :group (t :ui/hat-learn)
      :hint (str (t :learn/session-word) " · "
                 (t (case (:status s) :passed :learn/session-status-passed :failed :learn/session-status-failed :learn/session-status-open))
                 (when (:current? s) (str " · " (t :ui/session-now))))
      :run #(do (learn/open-run! s) (goto! (str "#/learn/" (:course s))))})
   (for [s (reverse (chat/past-sessions))]
     {:label (:title s) :icon :chat
      :group (t :ui/hat-author)
      :hint (if (:current? s) (t :ui/session-now) (t :ui/session-word))
      :run #(do (chat/open-past! s) (goto! "#/chat"))})
   (for [[k v] (when-not llm/backend llm/presets)]
     {:label (t :ui/model-label {"{label}" (:label v)})
      :icon :gear
      :hint (when (= k (:preset (llm/config))) (t :ui/current-hint))
      :run #(do (llm/save-config! (merge (llm/config) v {:preset k}))
                (snack! (t :ui/model-label {"{label}" (:label v)})))})
   (topic-rows kg)))

;; every topic as a palette row, built once per graph value — rebuilt per
;; keystroke it cost seconds at 40k topics
(defonce ^:private topic-rows-cache (atom [nil nil]))

(defn- topic-rows [kg]
  (let [[k v] @topic-rows-cache]
    (if (identical? k (:concepts kg))
      v
      (let [v (vec (for [[id c] (:concepts kg)
                         :let [props (vec (for [[pk pv] (fold/node-props c)] (str (name pk) ": " pv)))]]
                     {:label (:title c) :icon :notes :hint (t :ui/topic-lower)
                      ;; a node is found by its properties too (2026-11-20)
                      :props props :lc (str/lower-case (str (:title c))) :lprops (mapv str/lower-case props)
                      :run #(goto! (str "#/c/" id))}))]
        (reset! topic-rows-cache [(:concepts kg) v])
        v))))

(defn palette [kg]
  (let [q (r/atom "") sel (r/atom 0)]
    (fn [_]
      (let [lq (str/lower-case @q)
            ;; a title match, else a property «key: value» that holds the query —
            ;; shown as the row's hint, so the person sees why it matched
            ;; at most the 12 rows shown: the scan stops once it has them
            acts (vec (take 12 (keep (fn [a]
                                       (cond
                                         (str/includes? (or (:lc a) (str/lower-case (:label a))) lq) a
                                         (seq lq) (when-let [i (first (keep-indexed (fn [i p] (when (str/includes? p lq) i))
                                                                                    (or (:lprops a) (map str/lower-case (:props a)))))]
                                                    (assoc a :hint (nth (:props a) i)))))
                                     (palette-actions kg))))
            run! (fn [a] (reset! palette-open false) ((:run a)))]
        [:div.cmd-scrim {:on-click #(reset! palette-open false)}
         [:div.cmd-palette {:on-click #(.stopPropagation %)}
          [:div.cmd-title (t :ui/palette-title)]
          ;; semi-controlled (grow-textarea's reason): nothing else writes q,
          ;; and a controlled input here dropped fast keystrokes — a truncated
          ;; query then matched the wrong row (session-check, 2026-09-08)
          [:input.cmd-input
           {:default-value "" :ref focus! :placeholder (t :ui/palette-placeholder)
            :on-change #(do (reset! q (.. % -target -value)) (reset! sel 0))
            :on-key-down
            (fn [e]
              (case (.-key e)
                "ArrowDown" (do (.preventDefault e) (swap! sel #(min (inc %) (max 0 (dec (count acts))))))
                "ArrowUp" (do (.preventDefault e) (swap! sel #(max (dec %) 0)))
                "Enter" (when-let [a (nth acts @sel nil)] (run! a))
                "Escape" (reset! palette-open false)
                nil))}]
          (if (empty? acts)
            [:p.cmd-empty (t :ui/nothing-found)]
            [:ul.cmd-list
             (for [[i a] (map-indexed vector (take 12 acts))]
               ^{:key (:label a)}
               [:li {:class (when (= i @sel) "sel")
                     :on-click #(run! a)}
                [icons/icon (:icon a) "cmd-ico"] [:span.cmd-label (:label a)]
                (when (:group a) [:span.cmd-group (:group a)])
                (when (:hint a) [:span.muted (str "  " (:hint a))])])])]]))))

;; --- shell -------------------------------------------------------------------

(defonce ^:private tracker-cache (atom [nil nil]))

(defn tracker [kg]
  (let [ids (keys (:concepts kg))
        ;; counted once per graph value: per render it cost 0.7 s at 40k topics
        ready (let [[k v] @tracker-cache]
                (if (identical? k kg) v
                    (let [v (count (filter #(and (fold/publishable? kg %) (not (fold/stale? kg %))) ids))]
                      (reset! tracker-cache [kg v]) v)))]
    (r/with-let [open (r/atom false)]
      ;; the list only when it is open: sorting 40k titles on every render cost 0.5 s an edit
      (let [missing (when @open
                      (sort-by :title (keep #(when-not (and (fold/publishable? kg %) (not (fold/stale? kg %)))
                                               (get-in kg [:concepts %])) ids)))]
        [:span.tracker-wrap
         [:button.tracker {:aria-expanded @open :title (t :ui/tracker-open)
                           :on-click #(swap! open not)}
          (t :ui/tracker {"{n}" ready "{m}" (count ids)})]
         (when (and @open (seq missing))
           [:div.tracker-list {:on-click #(reset! open false)}
            [:b (t :ui/tracker-missing)]
            (for [c missing]
              ^{:key (:id c)}
              [:a {:href (str "#/c/" (js/encodeURIComponent (:id c)))}
               (:title c) (when (fold/stale? kg (:id c)) (t :ui/node-stale-suffix))])])]))))

(defn- error-text [{:keys [error detail]}]
     (case error
       ;; server-first graphs (wiki/Graph-Store.md): the server's guard said no,
       ;; in its own words; or the server was out of reach and the edits went back
       :server-refused (t :ui/error-server-refused {"{why}" (error-text detail)})
       :server-unreachable (t :ui/error-server-unreachable {"{n}" detail})
       :store-refused (t :ui/error-store-refused {"{why}" (error-text detail)})
       :requires-cycle (t :ui/error-cycle {"{detail}" (pr-str detail)})
       :was-discarded (t :ui/error-discarded)
       :missing-endpoint (t :ui/error-missing-endpoint)
       :exists (t :ui/error-exists {"{detail}" detail})
       :duplicate (t :ui/error-duplicate)
       :in-course (t :ui/error-in-course)
       :storage-full (t :ui/error-storage-full)
       :old-format (t :ui/error-old-format {"{n}" detail})
       :unknown-route (t :ui/error-unknown-route {"{route}" detail})
       :relation-exists (t :ui/error-relation-exists {"{id}" detail})
       :invalid-schema (t :ui/error-invalid-schema {"{detail}" (pr-str detail)})
       :unknown-relation (t :ui/error-unknown-relation {"{detail}" (pr-str detail)})
       :relation-misfit (t :ui/error-relation-misfit {"{detail}" (pr-str detail)})
       :unknown-node-type (t :ui/error-unknown-node-type {"{detail}" (pr-str detail)})
       :profile-locked (t :ui/error-profile-locked)
       :ontology-graph-only (t :ui/error-ontology-graph-only)
       :ontology-elsewhere (t :ui/error-ontology-elsewhere {"{title}" (store/graph-title detail)})
       :ontology-breaks-data (t :ui/error-ontology-breaks-data {"{detail}" (pr-str detail)})
       :no-whole-ontology (t :ui/error-no-whole-ontology)
       :ontology-in-use (t :ui/error-ontology-in-use)
       :bad-profile (t :ui/error-bad-profile {"{detail}" (pr-str detail)})
       :bad-ontology (t :ui/error-bad-ontology {"{detail}" (str detail)})
       :bad-split (t :ui/error-bad-split {"{detail}" (t (keyword "ui" (str "split-why-" (name detail))))})
       :missing (t :ui/error-missing {"{detail}" (pr-str detail)})
       :bad-import (t :ui/error-bad-import {"{detail}" (pr-str detail)})
       :bad-edn (t :ui/error-bad-edn)
       :graph-switched-elsewhere (t :ui/error-graph-switched {"{title}" (store/graph-title detail)})
       :demo-unavailable (t :ui/error-demo-unavailable)
       :bad-object-link (t :ui/error-bad-object-link)
       :merge-unavailable (t :ui/merge-error {"{e}" detail})
       :past-view (t :ui/error-past-view)
       (pr-str [error detail])))

(defn error-banner []
  (when-let [err @store/last-error]
    [:div.error
     (error-text err)
     [:button {:on-click #(reset! store/last-error nil) :title (t :ui/close) :aria-label (t :ui/close)} [icons/icon :close]]]))

;; kintale's graph-side rail: icon-only round buttons stacked at the
;; map's bottom-right, assistant at the bottom (Denis 2026-09-08 — the
;; header row of labelled buttons was the thing he asked to lose).
(defn- layout-btn
  "Layers (prerequisites left → right) or free physics; switching frames the map."
  []
  (let [layers? (= :layers @layout-mode)]
    [:button.rbtn.nav-layout {:title (t (if layers? :ui/layout-to-free :ui/layout-to-layers))
                              :aria-label (t (if layers? :ui/layout-to-free :ui/layout-to-layers))
                              :aria-pressed layers?
                              :on-click (fn []
                                          (let [next (if layers? :free :layers)]
                                            (reset! layout-mode next)
                                            (try (.setItem js/localStorage layout-key (name next)) (catch :default _ nil))
                                            (js/setTimeout #(fit-all! 90) 900)))}
     [icons/icon (if layers? :rows :graph)]]))

(defn fly-to!
  "Bring a topic into view when it is off screen or under the open panel
   (search, the not-ready list): pans, keeps the zoom. The panel's real
   rectangle is left clear — beside it on a wide screen, below it on a phone."
  [id]
  (when-let [p (and @vb (pos id))]
    (let [{:keys [cx cy w h]} @vb
          k (/ w (max 1 @canvas-w))
          ^js svg (js/document.querySelector ".map-wrap svg.graph")
          ^js pane (js/document.querySelector ".gpopup, .split > .panel, .split > .chat")
          sr (some-> svg .getBoundingClientRect)
          pr (some-> pane .getBoundingClientRect)
          wide? (> (.-innerWidth js/window) 700)
          ;; world units hidden on the right (wide) or at the top (phone)
          right (if (and sr pr wide?) (* k (max 0 (- (.-right sr) (.-left pr)))) 0)
          top (if (and sr pr (not wide?)) (* k (max 0 (- (.-bottom pr) (.-top sr)))) 0)
          x0 (- cx (/ w 2)) x1 (- (+ cx (/ w 2)) right)
          y0 (+ (- cy (/ h 2)) top) y1 (+ cy (/ h 2))
          m (* 1.5 R)]
      (when-not (and (< (+ x0 m) (:x p) (- x1 m)) (< (+ y0 m) (:y p) (- y1 m)))
        (let [tx (+ (:x p) (/ right 2)) ty (- (:y p) (/ top 2)) sx cx sy cy t0 (js/performance.now)]
          (letfn [(step [now]
                    (let [u (min 1 (/ (- now t0) 320)) e (- 1 (js/Math.pow (- 1 u) 3))]
                      (swap! vb assoc :cx (+ sx (* e (- tx sx))) :cy (+ sy (* e (- ty sy))))
                      (when (< u 1) (js/requestAnimationFrame step))))]
            (js/requestAnimationFrame step)))))))

(defn- local-dt
  "A datetime-local input's value (local wall clock) ↔ epoch ms."
  ([ms] (when ms
          (let [d (js/Date. ms) pad #(if (< % 10) (str "0" %) (str %))]
            (str (.getFullYear d) "-" (pad (inc (.getMonth d))) "-" (pad (.getDate d))
                 "T" (pad (.getHours d)) ":" (pad (.getMinutes d))))))
  ([s _] (let [ms (.getTime (js/Date. s))] (when-not (js/isNaN ms) ms))))

(defn- asof-btn
  "The graph as it stood (wiki/Data-Model.md § Bitemporal): two clocks over
   the same log — «as of» = what the changes themselves say (`:ev/ts`), «as
   known by» = what this log had taken in (`:ev/recorded`). Set either and
   every reader shows the past; the rail and the pencils are absent until
   «Now». A native datetime input: the demo's whole history is one morning."
  []
  (r/with-let [open (r/atom false)]
    (let [at @store/as-of
          set-at! (fn [k s] (swap! store/as-of (fn [a] (let [a (if (local-dt s nil) (assoc a k (local-dt s nil)) (dissoc a k))]
                                                        (when (seq a) a)))))
          fmt #(.toLocaleString (js/Date. %) (name @i18n/locale) #js {:dateStyle "medium" :timeStyle "short"})]
      [:div.asof-wrap
       [:button.rbtn.nav-asof {:title (t :ui/asof) :aria-label (t :ui/asof) :aria-expanded @open
                               :class (when at "on") :on-click #(swap! open not)}
        [icons/icon :clock]]
       (when at
         [:span.asof-chip {:on-click #(swap! open not)}
          (str (t :ui/asof-valid) " " (fmt (or (:valid at) (:recorded at))))])
       (when @open
         [:div.asof-pop
          [:p.muted (t :ui/asof-lead)]
          [:label (t :ui/asof-valid)
           [:input.asof-valid {:type "datetime-local" :default-value (local-dt (:valid at))
                               :on-change #(set-at! :valid (.. % -target -value))}]]
          [:label (t :ui/asof-recorded)
           [:input.asof-recorded {:type "datetime-local" :default-value (local-dt (:recorded at))
                                  :on-change #(set-at! :recorded (.. % -target -value))}]]
          (when at
            [:button.primary.asof-now {:on-click #(do (reset! store/as-of nil) (reset! open false))} (t :ui/asof-now)])])])))

(defn- merge-badge
  "Merge files waiting for the open graph (loretile.merge): a count on the
   map, the list itself in the drawer. Absent when nothing waits."
  []
  (let [n (count (mrg/pending))]
    (when (pos? n)
      [:button.merge-badge.nav-merges {:title (t :ui/merges) :on-click #(reset! drawer-open true)}
       [icons/icon :inbox] (t :ui/merge-badge {"{n}" n})])))

(defn- legend-btn
  "What the colours and glyphs mean, one tap away — it lived only in the
   tour (UX review 2026-10-02 #11). Reuses the tour's legend text."
  []
  (r/with-let [open (r/atom false)]
    [:div.legend-wrap
     [:button.rbtn.nav-legend {:title (t :ui/legend) :aria-label (t :ui/legend) :aria-expanded @open
                               :on-click #(swap! open not)} "?"]
     (when @open
       (let [p (store/profile) kg (store/kg)]
         [:div.legend-card {:on-click #(reset! open false)}
          (if (profile/learning? p)
            [:<> [:b (t :tour/legend-t)] [:p (t :tour/legend)]]
            ;; a custom ontology's legend IS its vocabulary
            [:<>
             [:b (:title p)]
             [:p (for [nt (profile/node-types p)]
                   ^{:key (:id nt)}
                   [:span.legend-type [:i.swatch {:style {:background (or (:color (graph-type-style kg (:id nt))) "var(--accent)")}}] (:label nt) " "])]
             [:p (str/join " · " (for [r (profile/relations p) :when (not= :grounded-in (:id r))]
                                   (str (:label r) (when (:ordering? r) " →"))))]])]))]))

(defn- fullscreen-btn []
  ;; the DOCUMENT, not .map-wrap: fullscreening a container hides every
  ;; sibling (modals, palette) — kintale learned that 2026-07-31
  (r/with-let [fs? (r/atom (boolean (.-fullscreenElement js/document)))
               on-change #(reset! fs? (boolean (.-fullscreenElement js/document)))
               _ (.addEventListener js/document "fullscreenchange" on-change)]
    [:button.rbtn.nav-fs {:title (t :ui/fullscreen) :aria-label (t :ui/fullscreen)
                   :style {:font-size "17px" :line-height 1}
                   :on-click (fn []
                               (if (.-fullscreenElement js/document)
                                 (some-> js/document (.exitFullscreen))
                                 (some-> (.requestFullscreen js/document.documentElement)
                                         (.catch (fn [_])))))}
     (if @fs? "\u29c9" "\u26f6")]
    (finally (.removeEventListener js/document "fullscreenchange" on-change))))

(defonce demos-on?
  ;; an organisation's build ships no demo graphs (`make dist-enterprise`
  ;; writes <meta name="loretile-demos" content="off"> into index.html): every
  ;; demo control is then ABSENT — a picker over files that are not there
  ;; would only fail (Denis 2026-10-20: release without demo graphs)
  (not= "off" (some-> (js/document.querySelector "meta[name=loretile-demos]")
                      (.getAttribute "content"))))

(def demos
  [[:sql :ui/demo-sql-t :ui/demo-sql]
   [:infosec :ui/demo-infosec-t :ui/demo-infosec]
   [:vineyard :ui/demo-vineyard-t :ui/demo-vineyard]])

(defonce demo-picker-open (r/atom false))

(defn- pick-demo!
  "Each demo lands in a graph of its own; picking one already loaded just
   switches to it. Nothing is replaced, so nothing to confirm."
  [slug]
  (let [[_ kt _] (first (filter #(= slug (first %)) demos))]
    (store/load-demo! slug (t kt)))
  (reset! demo-picker-open false)
  (goto! "#/"))

(defn demo-picker []
  (when (and demos-on? @demo-picker-open)
    [:div.modal {:on-click #(reset! demo-picker-open false)}
     [:div.form {:on-click #(.stopPropagation %)}
      [:h3 [icons/icon :graph] " " (t :ui/demos-title)]
      (for [[slug kt kb] demos]
        ^{:key slug}
        [:button.demo-row {:class (when (= slug :sql) "load-demo")
                           :on-click #(pick-demo! slug)}
         [:b (t kt)] [:span.muted (t kb)]])
      [:div.row [:button {:on-click #(reset! demo-picker-open false)} (t :ui/cancel)]]]]))

(defn- app-version
  "Deploy stamp from the cache-busted bundle name (main-<sha>.js); dev otherwise."
  []
  (or (some->> (js/document.querySelector "script[src*='main']") (.-src)
               (re-find #"main-([a-z0-9+]+)\.js") second)
      "dev"))

;; kintale's settings page: general first, then one section per feature
;; that has something to configure — the assistant key today, more later
(defn- author-section
  "Your name on every change from this device (2026-10-21: every change was
   «denis»). Written into each graph you change, so an exported graph says
   who added what. Semi-controlled like every form here."
  []
  (let [d (r/atom nil)]
    (fn []
      [:section.author
       [:h3 (t :ui/author-heading)]
       [:p.muted (t :ui/author-lead)]
       [:div.row
        [:input.author-name {:placeholder (t :ui/author-placeholder) :default-value (:name @store/author)
                             :on-change #(reset! d (.. % -target -value))}]
        [:button.primary.author-save
         {:on-click (fn [_] (when (store/set-author-name! (or @d (:name @store/author) "")) (snack! (t :ui/author-saved))))}
         (t :ui/owner-save)]]])))

(defn- budget-section
  "Caps day one (Ethics): the day's tokens against the budget, editable."
  []
  (let [{:keys [tokens calls corrupt]} (llm/usage) budget (llm/daily-budget)]
    [:section.budget
     [:h3 (t :ui/budget-heading)]
     (when-not llm/backend [:p.muted (t :ui/budget-lead)])
     [:input.budget-input {:type "number" :min 1000 :step 10000 :default-value budget
                           :on-change #(llm/set-daily-budget! (js/parseInt (.. % -target -value)))}]
     [:p.muted.budget-usage (t :ui/budget-usage {"{used}" tokens "{budget}" budget "{calls}" calls})]
     (when (or corrupt (nil? budget)) [:p.budget-corrupt (t :ui/budget-corrupt)])]))

(defn- cq-form
  "A competency question (wiki/Profiles.md): the question in words, and —
   so the fold can CHECK it — the relation that answers it, from which node
   type, read from which end. Semi-controlled like every form on the map."
  [_]
  (let [q (r/atom "") rel (r/atom "") from (r/atom "") dir (r/atom "out")]
    (fn [kg]
      (let [p (fold/defined kg)]
      [:div.onto-form.cq-form
       [:input {:placeholder (t :ui/cq-question-placeholder) :default-value @q
                :on-change #(reset! q (.. % -target -value))}]
       [:select.cq-rel {:value @rel :on-change #(reset! rel (.. % -target -value))}
        [:option {:value ""} (t :ui/cq-no-pattern-opt)]
        (for [r (profile/relations p) :when (not= :grounded-in (:id r))]
          ^{:key (:id r)} [:option {:value (name (:id r))} (str (t :ui/cq-pattern) " " (:label r))])]
       (when (seq @rel)
         [:<>
          [:select.cq-dir {:value @dir :on-change #(reset! dir (.. % -target -value))}
           [:option {:value "out"} (t :ui/cq-dir-out)]
           [:option {:value "in"} (t :ui/cq-dir-in)]]
          [:select.cq-from {:value @from :on-change #(reset! from (.. % -target -value))}
           [:option {:value ""} (t :ui/cq-any-type)]
           (for [nt (profile/node-types p)]
             ^{:key (:id nt)} [:option {:value (name (:id nt))} (:label nt)])]])
       [:button.primary.cq-add
        {:disabled (str/blank? @q)
         :on-click (fn [_]
                     (when (nil? (store/append!
                                  (ops/raise-cq (cond-> {:id (str "cq-" (random-uuid)) :question (str/trim @q)}
                                                  (seq @rel) (assoc :pattern (cond-> {:relation (keyword @rel)}
                                                                               (seq @from) (assoc :from-type (keyword @from))
                                                                               (= "in" @dir) (assoc :inverse? true)))))))
                       (snack! (t :ui/cq-added))
                       (reset! q "")))}
        (t :ui/add-cq)]]))))

(defn- cq-section
  "Competency questions with their check — what the graph must answer and
   where it is silent. Computed by the fold (never the model's opinion)."
  [kg]
  (let [report (fold/cq-report kg)
        answered (reduce + (keep (comp :answered :check) report))
        asked (reduce + (keep (comp :asked :check) report))]
    [:section.cqs
     [:h3 (t :ui/cqs) (when (pos? asked) [:span.muted.cq-tracker " · " (t :ui/cq-tracker {"{a}" answered "{n}" asked})])]
     [:p.muted (t :ui/cqs-hint)]
     (for [{:keys [id question pattern check]} report
           :let [inherited? (get-in kg [:cqs id :inherited?])
                 check (when-not (:ontology-graph? kg) check)]]
       ^{:key id}
       [:div.cq-row {:class (when inherited? "inherited")}
        [:span.q question
         (when pattern [:span.muted (str " [" (name (:relation pattern))
                                        (when (:from-type pattern) (str " · " (profile/kind-label (fold/profile kg) (:from-type pattern))))
                                        (when (:inverse? pattern) " ←") "]")])]
        (if check
          [:span {:class (if (seq (:gaps check)) "gap" "ok")}
           (t :ui/cq-answered {"{a}" (:answered check) "{n}" (:asked check)})
           (when (seq (:gaps check))
             [:span.muted " · " (t :ui/cq-gaps)
              (for [g (:gaps check)]
                ^{:key g} [:a {:href (str "#/c/" (js/encodeURIComponent g))} (get-in kg [:concepts g :title] g) " "])])]
          (cond (:ontology-graph? kg) [:span.muted (t :ui/cq-checked-in-graphs)]
                :else [:span.muted (t :ui/cq-no-pattern)]))
        (if inherited?
          [:span.muted.cq-inherited (t :ui/cq-inherited)]
          [rm-btn id (ops/retire-cq id)])])
     ^{:key (str "cq-form-" (count report))} [cq-form kg]]))

(defn- pitfalls-section
  "What the fold sees without a model (both-ways use of a directed relation,
   isolated nodes, unused types and relations), and the one tap that asks
   the model for the questions the vocabulary cannot answer yet."
  [kg]
  (let [pits (fold/ontology-pitfalls kg)
        tname (fn [k] (or (profile/kind-label (fold/profile kg) k) (name k)))]
    [:section.pitfalls
     [:h3 (t :ui/pitfalls)]
     (if (empty? pits)
       [:p.muted (t :ui/pitfalls-none)]
       (for [[i {:keys [kind detail]}] (map-indexed vector pits)]
         ^{:key i}
         [:div.pit-row
          (case kind
            :symmetric-use (let [[a b type] detail]
                             (t :ui/pit-symmetric {"{a}" (get-in kg [:concepts a :title] a) "{b}" (get-in kg [:concepts b :title] b)
                                                   "{rel}" (edge-label kg type)}))
            :isolated [:<> (t :ui/pit-isolated) " " [:a {:href (str "#/c/" (js/encodeURIComponent detail))} (get-in kg [:concepts detail :title] detail)]]
            :unused-type (t :ui/pit-unused-type {"{t}" (tname detail)})
            :unused-relation (t :ui/pit-unused-relation {"{rel}" (edge-label kg detail)})
            (pr-str detail))]))
     (when (llm/ready?)
       [:button.probe-ontology {:on-click (fn [] (chat/probe-ontology!) (goto! "#/chat"))} (t :ui/probe-ontology)])]))

(defn- owner-section
  "Who answers for the graph (Netflix UDA: every domain model has an
   owner) — a team and how to reach it, in the log, so it exports and an
   agent reads it first in describe-ontology."
  [_]
  (let [team (r/atom nil) contact (r/atom nil)]
    (fn [kg]
      (let [o (:owner kg)]
        [:section.owner
         [:h3 (t :ui/owner)]
         [:p.muted (t :ui/owner-hint)]
         [:div.onto-form.owner-form
          [:input.owner-team {:placeholder (t :ui/owner-team-placeholder) :default-value (or (:team o) "")
                              :on-change #(reset! team (.. % -target -value))}]
          [:input.owner-contact {:placeholder (t :ui/owner-contact-placeholder) :default-value (or (:contact o) "")
                                 :on-change #(reset! contact (.. % -target -value))}]
          [:button.primary.owner-save
           {:on-click (fn [_]
                        (let [tm (str/trim (or @team (:team o) "")) ct (str/trim (or @contact (:contact o) ""))]
                          (when (and (seq tm)
                                     (nil? (store/append! (ops/set-owner (cond-> {:team tm} (seq ct) (assoc :contact ct))))))
                            (snack! (t :ui/owner-saved)))))}
           (t :ui/owner-save)]]]))))

(def color-schemes ["default" "blueprint" "warm"])

(defn color-scheme
  "How a graph's map is drawn: its `:color-scheme` property, else blueprint
   for a graph describing types, else the default."
  [kg]
  (or (get-in kg [:graph :color-scheme]) (if (:ontology-graph? kg) "blueprint" "default")))

(defn- type-style-rows
  "The graph's own style per type (its meta, `:type-style`): colour and icon
   over what the ontology gives; × goes back to the ontology's."
  [kg]
  (let [p (fold/profile kg)
        styles (or (get-in kg [:graph :type-style]) {})
        put! (fn [kind props]
               (let [m (into {} (remove (comp nil? val)) (merge (get styles kind) props))]
                 (store/append! {:ev/type :change
                                 :ops [[:graph-set {:type-style (if (seq m) (assoc styles kind m) (dissoc styles kind))}]]})))]
    (when (and (not (:ontology-graph? kg)) (not (profile/learning? p)))
      [:div.type-styles
       [:h4 (t :ui/type-styles)]
       (for [nt (profile/node-types p)]
         ^{:key (:id nt)}
         [:div.type-style-row
          [:span.type-style-name (:label nt)]
          [style-controls (graph-type-style kg (:id nt)) (get styles (:id nt)) #(put! (:id nt) %)]])])))

(defn- graph-props-section
  "The open graph's own properties: its map's colour scheme, the property
   shown under node titles, and its style per type — `graph-set` ops."
  [kg]
  [:section.graph-props
   [:h3 (t :ui/graph-props)]
   (when-let [ks (seq (prop-keys kg))]
     [:label (t :ui/map-prop) " "
      [:select.map-prop {:value (some-> (get-in kg [:graph :map-prop]) name)
                         :on-change #(store/append! {:ev/type :change
                                                     :ops [[:graph-set {:map-prop (let [v (.. % -target -value)] (when (seq v) (keyword v)))}]]})}
       [:option {:value ""} (t :ui/map-prop-none)]
       (for [k ks] ^{:key k} [:option {:value (name k)} (name k)])]])
   [type-style-rows kg]
   [:label (t :ui/color-scheme) " "
    [:select.color-scheme {:value (color-scheme kg)
                           :on-change #(store/append! {:ev/type :change
                                                       :ops [[:graph-set {:color-scheme (.. % -target -value)}]]})}
     (for [c color-schemes] ^{:key c} [:option {:value c} (t (keyword "ui" (str "scheme-" c)))])]]])

(defn- migration-section
  "The open graph is held on an old ontology version (it would lose types
   or relations its data uses): choose what each dropped one becomes, then
   take the new version — retyping and the new copy in one change
   (2026-11-20; the graph used to stay stuck)."
  []
  (let [plan (r/atom {:types {} :relations {}})]
    (fn []
      (when-let [{:keys [breaks latest]} (store/stuck)]
        (let [k (store/kg)
              old (fold/profile k)
              n-kind (frequencies (keep :kind (vals (:concepts k))))
              n-rel (frequencies (map :type (:edges k)))
              types (:node-types latest)
              rels (remove #(= :grounded-in (:id %)) (:relations latest))
              chosen-type #(get-in @plan [:types %] (:id (first types)))
              chosen-rel #(get-in @plan [:relations %] :remove)]
          [:section.migration
           [:h3 (t :ui/migration-heading)]
           [:p.muted (t :ui/migration-lead {"{onto}" (store/graph-title (get-in k [:ontology :graph]))})]
           (for [kind (:node-types breaks)]
             ^{:key (str "t" kind)}
             [:div.migration-row
              [:span (t :ui/migration-type {"{type}" (profile/kind-label old kind) "{n}" (get n-kind kind 0)})]
              [:select.migrate-type {:value (name (chosen-type kind))
                                     :on-change #(swap! plan assoc-in [:types kind] (keyword (.. % -target -value)))}
               (for [nt types] ^{:key (:id nt)} [:option {:value (name (:id nt))} (:label nt)])]])
           (for [rel (:relations breaks)]
             ^{:key (str "r" rel)}
             [:div.migration-row
              [:span (t :ui/migration-relation {"{rel}" (profile/label old rel) "{n}" (get n-rel rel 0)})]
              [:select.migrate-relation {:value (if (= :remove (chosen-rel rel)) "" (name (chosen-rel rel)))
                                         :on-change #(swap! plan assoc-in [:relations rel]
                                                            (let [v (.. % -target -value)] (if (seq v) (keyword v) :remove)))}
               [:option {:value ""} (t :ui/migration-remove-links)]
               (for [r rels] ^{:key (:id r)} [:option {:value (name (:id r))} (:label r)])]])
           [:button.primary.migrate-go
            {:on-click (fn []
                         (let [p {:types (into {} (for [kind (:node-types breaks)] [kind (chosen-type kind)]))
                                  :relations (into {} (for [rel (:relations breaks)] [rel (chosen-rel rel)]))}]
                           (if-let [err (store/migrate! p)]
                             (reset! store/last-error err)
                             (snack! (t :ui/migration-done)))))}
            (t :ui/migration-go)]])))))

(defn- ontology-users-section
  "An ontology graph's own page: the graphs on this device that use it, and
   a new graph that does."
  []
  (let [oid @store/current
        users (filter #(= oid (:ontology %)) @store/graphs)]
    [:section.ontology-users
     [:h3 (t :ui/ontology-users)]
     (if (seq users)
       [:ul (for [g users] ^{:key (:id g)}
              [:li [:a {:href (str "#/g/" (js/encodeURIComponent (:id g)) "/")} (:title g)]])]
       [:p.muted (t :ui/ontology-users-none)])
     [:button.new-graph-from-ontology
      ;; refused BEFORE a graph is made: an unfinished ontology left an
      ;; empty unbound graph behind
      {:on-click (fn []
                   (if-not (store/whole-ontology? oid)
                     (reset! store/last-error {:error :no-whole-ontology})
                     (new-graph-asked! oid)))}
      (t :ui/new-graph-from-ontology)]]))

(defn ontology-page
  "The graph's vocabulary, read like Settings (sections, no modals): node
   types and relations with their inverse, endpoints, and how many nodes
   and edges use each; the competency questions it must answer, checked.
   A custom ontology grows here (additively); learning's reserved
   vocabulary shows the same way, read-only."
  [kg]
  (let [p (fold/defined kg)
        learning? (profile/learning? p)
        onto? (:ontology-graph? kg)
        bound (get-in kg [:ontology :graph])
        ;; a bound graph's vocabulary is edited in its ontology graph
        ;; (wiki/Ontology-Graph.md); learning's is built in
        ;; an ontology graph is edited on its map (wiki/Property-Graph.md)
        fixed? (or learning? bound onto?)
        loose (set (fold/unjustified-relations kg))
        n-kind (frequencies (map :kind (vals (:concepts kg))))
        n-rel (frequencies (map :type (:edges kg)))]
    [:div.settings-page.ontology-page
     [:a.back {:href "#/"} (t :ui/back-to-map)]
     [:h2 (t :ui/ontology)]
     [:p.muted (cond learning? (t :ui/ontology-learning-hint)
                     onto? (t :ui/ontology-graph-hint)
                     bound (t :ui/ontology-bound-hint)
                     :else (t :ui/ontology-hint))]
     (when bound
       [:p.bound-note (t :ui/bound-to) " "
        [:a {:href (str "#/g/" (js/encodeURIComponent bound) "/ontology")} (store/graph-title bound)]
        (when-let [{:keys [error detail]} (get @store/sync-refused @store/current)]
          [:span.sync-refused " " (t :ui/sync-refused {"{detail}" (if (= :ontology-breaks-data error) (pr-str detail) (name error))})])])
     (when bound [migration-section])
     (when onto? [:p.edit-on-map (t :ui/ontology-edit-on-map)])
     (when onto? [ontology-users-section])
     [graph-props-section kg]
     [owner-section kg]
     (when-not fixed? [:p.muted.vocab-hint (t :ui/vocab-hint)])
     [:section
      [:h3 (t :ui/node-types)]
      [:table.onto
       [:tbody
        (for [nt (profile/node-types p)]
          ^{:key (:id nt)}
          [:<>
           [:tr {:class (when-not learning? "has-desc")}
            [:td [:i.swatch {:style {:background (or (:color (graph-type-style kg (:id nt))) "var(--accent)")}}]]
            [:td [:code (name (:id nt))]]
            [:td (if learning? (t :ui/topic) (:label nt))]
            [:td.muted (when (:learnable? nt) (t :ui/learnable))]
            [:td.muted.n (cond learning? (count (:concepts kg)) onto? "" :else (get n-kind (:id nt) 0))]]
           ;; what it means, on its own full-width line (a column was cut off) —
           ;; read-only: words are edited in the ontology graph
           (when (and (not learning?) (seq (:description nt)))
             [:tr.desc-row [:td] [:td.desc-cell {:col-span 4} [:span.describe [:span.desc (:description nt)]]]])])]]]
     [:section
      [:h3 (t :ui/relations)]
      [:table.onto
       [:thead [:tr [:th] [:th (t :ui/rel-label)] [:th (t :ui/rel-inverse)] [:th (t :ui/rel-from-to)] [:th] [:th]]]
       [:tbody
        (for [r (profile/relations p)]
          ^{:key (:id r)}
          [:<>
          [:tr {:class (when (and (not learning?) (not (:reserved? r))) "has-desc")}
           [:td [:code (name (:id r))]]
           [:td (if (or learning? (:reserved? r)) (edge-label kg (:id r)) (:label r))]
           [:td (if (or learning? (:reserved? r)) (edge-label kg (:id r) true) (or (:inverse r) "—"))]
           [:td.muted (if (and (seq (:from r)) (seq (:to r)))
                        (str (str/join "|" (map name (:from r))) " → " (str/join "|" (map name (:to r))))
                        (if (= :grounded-in (:id r)) (t :ui/rel-to-source) "—"))]
           [:td.muted (str/join " · " (remove nil? [(when (:ordering? r) (t :ui/rel-ordering))
                                                    (when (and (:acyclic? r) (not (:ordering? r))) (t :ui/rel-acyclic))
                                                    (when (:symmetric? r) (t :ui/rel-symmetric))
                                                    ;; the «no relation without a reader» rule, mechanical
                                                    (when (and (loose (:id r)) (seq (:cqs kg))) (t :ui/rel-unjustified))]))]
           [:td.muted.n (if onto? "" (get n-rel (:id r) 0))]]
          (when (and (not learning?) (seq (:description r)) (not (:reserved? r)))
            [:tr.desc-row [:td.desc-cell {:col-span 6} [:span.describe [:span.desc (:description r)]]]])])]]
      ]
     ;; questions and pitfalls are checked over DATA — in the graphs that use it
     (when-not learning? [cq-section kg])
     (when-not (or learning? onto?) [pitfalls-section kg])]))

;; --- graphs and ontologies, managed (Denis 2026-11-19) -------------------------
;; The drawer only navigates; each kind of graph has a list view where it is
;; opened, renamed, exported and deleted. Delete is absent for an ontology a
;; graph on this device still uses.

(defn- entity-row [g]
  (let [id (:id g)
        open? (= id @store/current)
        users (store/ontology-users id)]
    [:li.entity-row {:class (when open? "on")}
     [:a.entity-title {:href (str "#/g/" (js/encodeURIComponent id) "/")} (:title g)]
     [:span.muted.entity-meta
      (str (t :ui/ontology-of) " " (ontology-name (:ontology g)))
      (when (seq users) (str " · " (t :ui/used-by {"{n}" (count users)})))
      (when open? (str " · " (t :ui/open-now)))]
     [:span.entity-actions
      [:button.rename {:title (t :ui/rename) :aria-label (t :ui/rename)
                       :on-click #(when-let [v (js/prompt (t :ui/rename-prompt) (:title g))]
                                    (when (seq (str/trim v)) (store/rename-graph! id (str/trim v))))}
       [icons/icon :pencil]]
      [:button.export-log {:title (t :ui/export) :on-click #(store/export-edn! id)} "edn"]
      [:button.export-agent {:title (t :ui/export-agent) :on-click #(store/export-md! id)} "md"]
      [:button.export-ttl {:title (t :ui/export-turtle) :on-click #(store/export-ttl! id)} "ttl"]
      ;; a graph other graphs use as their ontology is not deleted under them
      (when (empty? users)
        [:button.delete {:title (t :ui/delete) :aria-label (t :ui/delete)
                         :on-click #(when (js/confirm (t :ui/delete-confirm {"{title}" (:title g)}))
                                      (store/delete-graph! id))}
         [icons/icon :trash]])]]))

(defn- import-graph-button []
  [:label.btn.import-graph {:role "button" :tab-index 0} [icons/icon :inbox] (t :ui/import)
   [:input {:type "file" :accept ".edn" :style {:display "none"}
            :on-change (fn [ev]
                         (when-let [f (aget (.. ev -target -files) 0)]
                           (-> (.text f)
                               (.then #(when (store/import-as-new-graph! (str/replace (.-name f) #"\.edn$" "") %)
                                         (goto! "#/"))))))}]])

(defn- server-graphs-section
  "Graphs the server keeps that this device does not have yet — opening one
   brings it here (wiki/Graph-Sync.md)."
  []
  (let [listed (r/atom nil)]
    (-> (sync/server-graphs) (.then #(reset! listed %)) (.catch #(reset! listed :error)))
    (fn []
      (let [here (set (map :id @store/graphs))
            others (when (sequential? @listed) (remove #(here (:id %)) @listed))]
        [:section.server-graphs
         [:h3 (t :ui/server-graphs)]
         (cond
           (nil? @listed) [:p.muted (t :ui/server-graphs-loading)]
           (= :error @listed) [:p.muted (t :ui/server-graphs-offline)]
           (empty? others) [:p.muted (t :ui/server-graphs-none)]
           :else
           [:ul.entity-list
            (for [g others]
              ^{:key (:id g)}
              [:li.entity-row.server-row
               [:span.entity-title (or (:title g) (:id g))]
               [:span.muted.entity-meta (t :ui/server-graph-meta {"{n}" (or (:events g) 0)})]
               [:span.entity-actions
                [:button.open-server {:on-click #(-> (sync/open-server-graph! g) (.then (fn [id] (when id (goto! "#/")))))}
                 (t :ui/open)]]])])]))))

(defn graphs-page []
  [:div.settings-page.graphs-page
   [:a.back {:href "#/"} (t :ui/back-to-map)]
   [:h2 (t :ui/graphs)]
   [:div.page-actions
    [:button.primary.new-graph {:on-click #(new-graph-asked!)} [icons/icon :plus] (t :ui/new-graph)]
    (when demos-on?
      [:button.add-demo {:on-click #(reset! demo-picker-open true)} [icons/icon :graph] (t :ui/demos-title)])
    [import-graph-button]]
   [:ul.entity-list
    (for [g @store/graphs] ^{:key (:id g)} [entity-row g])]
   (when sync/enabled? [server-graphs-section])])

(defn settings-page []
  [:div.settings-page
   [:a.back {:href "#/"} (t :ui/back-to-map)]
   [:h2 (t :ui/settings)]
   [:section
    [:h3 (t :ui/general)]
    [:div.row {:style {:align-items "center"}}
     [:span.muted {:style {:min-width "100px"}} (t :ui/language)]
     (for [l i18n/locales]
       ^{:key l}
       [:button {:class (when (= l @i18n/locale) "on") :on-click #(i18n/set-locale! l)}
        (str/upper-case (name l))])]
    [:div.row {:style {:align-items "center" :margin-top "8px"}}
     [:span.muted {:style {:min-width "100px"}} (t :ui/color-theme)]
     (for [[theme-val label-key] [["light" :ui/theme-light] ["dark" :ui/theme-dark] [nil :ui/theme-system]]]
       ^{:key (str theme-val)}
       [:button {:class (when (= theme-val @theme) "on") :on-click #(set-theme! theme-val)} (t label-key)])]]
   [:section
    [:h3 [icons/icon :chat] (t :ui/assistant-heading)]
    (if llm/backend
      [:p.muted (t :chat/model-prefix) (:model (llm/config))]
      [chat/config-fields])]
   [budget-section]
   [author-section]
   (when (or (not llm/backend) (mrg/url))
     [:section.merges
      [:h3 [icons/icon :inbox] (t :ui/merges)]
      (when-not llm/backend
        [:<>
         [:p.muted (t :ui/merges-lead)]
         [:input.merge-url {:type "url" :default-value (mrg/url) :placeholder (t :ui/merge-url-placeholder)
                            :on-change #(mrg/set-url! (.. % -target -value))}]])
      (cond
        @mrg/error [:p.key-status.bad (t :ui/merge-error {"{e}" @mrg/error})]
        (mrg/url) [:p.muted (t :ui/merge-count {"{n}" (count (mrg/pending))})])])])

(defn drawer []
  (when @drawer-open
    (let [close! #(reset! drawer-open false)
          go (fn [h] (close!) (goto! h))]
      [:<>
       [:div.drawer-scrim {:on-click close!}]
       [:div.drawer
        [:a.dbrand {:href "/"} [:img {:src "icon.svg" :alt ""}] "Loretile"]
        ;; the entries are role=button anchors without href: only a[href]
        ;; activates on a key, so Enter and Space did nothing (review 2026-10-16)
        [:nav {:on-key-down (fn [^js e]
                              (let [el (.-target e)]
                                (when (and (#{"Enter" " "} (.-key e)) (= "button" (.getAttribute el "role")))
                                  (.preventDefault e)
                                  (.click el))))}
         [:a {:role "button" :tab-index 0 :on-click #(go "#/")} [icons/icon :graph] (t :ui/map)]
         (when (profile/learnable? (store/profile))
           [:a {:role "button" :tab-index 0 :on-click #(go "#/courses")} [icons/icon :notes] (t :ui/courses)])
         ;; graphs and ontologies are managed in their own views (Denis
         ;; 2026-11-19): open, rename, export, delete — the drawer only navigates
         [:a.nav-graphs {:role "button" :tab-index 0 :on-click #(go "#/graphs")} [icons/icon :graph] (t :ui/graphs)]
         [:div.dsep]
         ;; merge files waiting on the bucket for the open graph (loretile.merge)
         (when-let [ms (seq (mrg/pending))]
           [:<>
            [:div.dsec (t :ui/merges)]
            (for [k ms]
              ^{:key k}
              [:a.merge-row {:role "button" :tab-index 0
                             :on-click (fn [] (close!) (mrg/pick! k) (goto! "#/chat"))}
               [icons/icon :inbox] (mrg/title-of k)])])
         [:a {:role "button" :tab-index 0 :on-click #(go "#/chat")} [icons/icon :chat] (t :ui/assistant)]
         (when (profile/learnable? (store/profile))
           [:a {:role "button" :tab-index 0 :on-click #(go "#/course/new")} [icons/icon :notes] (t :ui/new-course)])
         [:a.nav-ontology {:role "button" :tab-index 0 :on-click #(go "#/ontology")} [icons/icon :lineage] (t :ui/ontology)]
         [:a {:role "button" :tab-index 0
              :on-click (fn [] (close!) (goto! "#/") (js/setTimeout tour/start-map-tour! 200))}
          [icons/icon :info] (t :ui/show-tour)]
         [:a {:role "button" :tab-index 0 :on-click #(go "#/settings")} [icons/icon :gear] (t :ui/settings)]]
        [:div.dfoot
         [:div.row
          (for [l i18n/locales]
            ^{:key l}
            [:button {:class (when (= l @i18n/locale) "on") :on-click #(i18n/set-locale! l)}
             (str/upper-case (name l))])
          [:button {:on-click toggle-theme! :title (t :ui/theme-title)}
           [icons/icon (if (dark?) :sun :moon)]]]
         [:span.muted.dmeta "© 2026 Loretile · v " (app-version)]]]])))

;; the map's property filter: {:k key :v value} or nil — a view, never saved
(defonce prop-filter (r/atom nil))

(defonce ^:private prop-keys-cache (atom [nil nil]))

(defn- prop-keys
  "Every free property key the graph's nodes carry, sorted — kept per topics
   value (the filter button asks on every render)."
  [kg]
  (let [[k v] @prop-keys-cache]
    (if (identical? k (:concepts kg))
      v
      (let [v (sort-by name (distinct (mapcat (comp (partial map key) fold/node-props) (vals (:concepts kg)))))]
        (reset! prop-keys-cache [(:concepts kg) v])
        v))))

(defonce ^:private some-props-cache (atom [nil nil]))

(defn- some-props?
  "Does any node carry a free property? A scan that stops at the first, kept
   per topics value: with none it walked all 40k topics every render (0.2 s)."
  [kg]
  (let [[k v] @some-props-cache]
    (if (identical? k (:concepts kg))
      v
      (let [v (boolean (some (fn [c] (some #(not (fold/node-core %)) (keys c))) (vals (:concepts kg))))]
        (reset! some-props-cache [(:concepts kg) v])
        v))))

(defn- filter-btn
  "Dim every node whose property is not the chosen value (2026-11-20).
   Present only when some node has a property."
  []
  (r/with-let [open (r/atom false)]
    ;; present when ANY node has a property (`some-props?`); the whole key
    ;; list only once the menu opens (per edit it cost 0.25 s at 40k)
    (let [kg (store/kg)
          ks (when (or @open (:k @prop-filter)) (prop-keys kg))
          {fk :k fv :v} @prop-filter]
      (when (some-props? kg)
        [:div.filter-wrap
         [:button.rbtn.nav-filter {:title (t :ui/prop-filter) :aria-label (t :ui/prop-filter)
                                   :class (when (or @open fk) "on") :on-click #(swap! open not)}
          [icons/icon :filter]]
         (when @open
           (let [k (or fk (first ks))
                 vals* (sort (distinct (keep #(some-> (get % k) str) (vals (:concepts kg)))))]
             [:div.filter-card
              [:select.filter-key {:value (name k)
                                   :on-change #(reset! prop-filter {:k (keyword (.. % -target -value)) :v nil})}
               (for [x ks] ^{:key x} [:option {:value (name x)} (name x)])]
              [:select.filter-value {:value (or fv "")
                                     :on-change #(let [v (.. % -target -value)]
                                                   (reset! prop-filter (when (seq v) {:k k :v v})))}
               [:option {:value ""} (t :ui/prop-filter-any)]
               (for [v vals*] ^{:key v} [:option {:value v} v])]
              (when fk
                [:button.filter-clear {:on-click #(reset! prop-filter nil)} (t :ui/prop-filter-clear)])]))]))))

(defn rail []
  ;; on an empty graph only the two doors that CAN do something are shown
  ;; (kintale's rule: absence, never a disabled button). Exploration must
  ;; not be rewarded with silence.
  ;; an ontology graph is edited like any graph: its nodes are types, its
  ;; links relations (wiki/Property-Graph.md)
  (let [empty? (empty? (:concepts (store/kg)))]
    [:div.rail
     [:button.rbtn.nav-add {:title (t :ui/topic) :on-click #(reset! show-new-concept true)}
      [icons/icon :plus]]
     (when-not empty?
       [:<>
        [:button.rbtn.nav-link {:title (t :ui/link) :class (when @link-mode "on")
                                :on-click #(swap! link-mode (fn [m] (when-not m {:from nil})))}
         [icons/icon :link]]
        [:button.rbtn.nav-ego {:title (t :ui/ego) :class (when @ego-mode "on")
                               :on-click #(swap! ego-mode not)}
         [icons/icon :lineage]]
        [:button.rbtn.nav-tidy {:title (t :ui/tidy) :on-click #(rearrange! (store/kg))}
         [icons/icon :tidy]]
        (when (profile/learnable? (store/profile))
          [:a.rbtn.nav-course {:title (t :ui/course) :href "#/course/new"} [icons/icon :notes]])
        [:button.rbtn.nav-search {:title (t :ui/search) :on-click #(reset! palette-open true)}
         [icons/icon :search]]])
   ;; a toggle (kintale chat/toggle!): open → the map, so a second press
   ;; does something even when the panel is where you cannot see it
     [:a.rbtn.nav-chat {:title (t :ui/assistant) :href (if (= :chat (:view @route)) "#/" "#/chat")
                        :class (when (= :chat (:view @route)) "on")}
      [icons/icon :chat]]]))

(defn- undo-asked! []
  (if (store/undo!) (snack! (t :ui/undone)) (snack! (t :ui/nothing-to-undo))))

(defn- redo-asked! []
  (if (store/redo!) (snack! (t :ui/redone)) (snack! (t :ui/nothing-to-redo))))

(defn global-keys! []
  (js/window.addEventListener
   "keydown"
   (fn [e]
     (cond
       ;; the key's PLACE, not its letter: «л» on a Russian layout, «K» with Caps Lock
       (and (or (.-ctrlKey e) (.-metaKey e)) (= "KeyK" (.-code e)))
       (do (.preventDefault e) (swap! palette-open not))

       ;; undo / redo of the graph's edits — outside text fields, where the
       ;; browser's own text undo belongs (2026-11-20)
       (and (or (.-ctrlKey e) (.-metaKey e))
            (#{"KeyZ" "KeyY"} (.-code e))
            (not (#{"INPUT" "TEXTAREA" "SELECT"} (.. e -target -tagName))))
       (do (.preventDefault e)
           (if (or (= "KeyY" (.-code e)) (.-shiftKey e)) (redo-asked!) (undo-asked!)))

       (= "Escape" (.-key e))
       (do (reset! palette-open false) (reset! show-new-concept false)
           (reset! drawer-open false)
           (reset! link-draft nil) (reset! link-mode nil))))))

;; --- the catalog: the LEARNER's home, separate from the author's map ---------
;; Two hats, one switch (Denis 2026-09-11): the map is where courses are
;; made, the catalog is where they are taken. Learning runs live here, on
;; the course they belong to — the palette used to interleave them with the
;; assistant's sittings, which reads as one undifferentiated history.

(defonce catalog-q (r/atom ""))

(defn- course-matches?
  "Search over the course AND its target topics: typing JOIN must find the
   SQL course even though no course is called JOIN."
  [kg course q]
  (let [q (str/lower-case (str/trim q))
        hay (str/lower-case
             (str (:title course) " "
                  (str/join " " (map #(get-in kg [:concepts (first %) :title] "") (:targets course)))))]
    (or (empty? q) (str/includes? hay q))))

(defn- progress-line [p]
  (case (:state p)
    :passed (t :ui/cat-passed {"{pct}" (or (:pct p) 100)})
    :in-progress (t :ui/cat-progress {"{n}" (:closed p) "{m}" (:total p) "{r}" (or (:round p) 1)})
    ;; never taken, yet topics are already closed → they were proven in
    ;; another course of this graph. That subtraction is the whole thesis,
    ;; so the card says it before the learner starts.
    (if (pos? (:closed p))
      (tn (:closed p) [:ui/cat-credit-1 :ui/cat-credit-2 :ui/cat-credit-5] {"{m}" (:total p) "{left}" (- (:total p) (:closed p))})
      (t :ui/cat-fresh {"{m}" (:total p)}))))

(defn- course-card [kg course graph]
  (let [p (learn/course-progress kg (:id graph) course)]
    [:div.course-card {:class (name (:state p))}
     [:div.cc-head
      [:h3 (:title course)]
      [:span.badge {:class (case (:state p) :passed "tile-ready" :in-progress "tile-stale" "tile-empty")}
       (t (case (:state p) :passed :ui/cat-state-passed :in-progress :ui/cat-state-open :ui/cat-state-new))]]
     [:p.muted (progress-line p)]
     [:div.cc-meta
      [:span.chip (str (count (:targets course)) " " (t :ui/cat-targets))]
      (when (> (count @store/graphs) 1) [:span.chip.cc-graph {:title (t :ui/cat-graph-title)} (:title graph)])
      ;; the learner's own graph: progress there certifies nothing
      (when (:personal? graph) [:span.chip.cc-self {:title (t :learn/self-checked)} (t :ui/cat-self-checked)])
      (when (:locale course) [:span.chip (i18n/lang-name (:locale course))])
      (when (:pass-threshold course) [:span.chip (t :ui/cat-threshold {"{n}" (:pass-threshold course)})])]
     [:div.row
      [:a.btn.primary.cc-go {:href (str "#/learn/" (:id course))}
       [icons/icon :graph]
       ;; a passed course opens its RESULT; «continue» promised more to do
       ;; (learning review 2026-11-20 D4)
       (t (case (:state p) :not-started :ui/cat-start :passed :ui/cat-open-result :ui/cat-continue))]
      (when (= :passed (:state p))
        [:button.btn.cc-again {:on-click #(do (learn/restart-from-head! (:id course))
                                             (goto! (str "#/learn/" (:id course))))}
         (t :ui/cat-take-again)])]
     ;; no author's door here: the learner's hat (UX review 2026-10-02 #10);
     ;; authors reach the course from the map
     (when (seq (:runs p))
       [:div.cc-runs
        [:div.dsec (t :ui/cat-history)]
        (for [r (:runs p)]
          ^{:key (:id r)}
          [:button.cc-run {:on-click #(do (learn/open-run! (assoc r :current? (:current? r)))
                                          (goto! (str "#/learn/" (:id course))))}
           [:span (t :learn/round {"{n}" (:round r)})]
           [:span.muted (t (case (:status r) :passed :learn/session-status-passed
                                 :failed :learn/session-status-failed :learn/session-status-open))]
           (when (:current? r) [:span.muted (str " · " (t :ui/session-now))])])])]))

(defn catalog [_]
  (let [entries (for [g (store/graph-kgs)
                      c (vals (:courses (:kg g)))]
                  {:graph g :kg (:kg g) :course c})
        courses (map :course entries)
        hits (filter #(course-matches? (:kg %) (:course %) @catalog-q) entries)]
    [:div.catalog
     [:div.cat-head
      [:h2 (t :ui/courses)]
      [:input.cat-search {:default-value @catalog-q :placeholder (t :ui/cat-search)
                          :on-change #(reset! catalog-q (.. % -target -value))}]
      ;; on a phone the assistant sits under every course card: this brings it
      ;; up (learning review 2026-11-20 D3; hidden by CSS where it is beside)
      ;; the panel itself (without a key it says how to get one), its field focused when there is one
      [:button.btn.ask-guide {:on-click #(when-let [^js g (js/document.querySelector ".guide")]
                                           (.scrollIntoView g #js {:block "start"})
                                           (some-> (.querySelector g "textarea") .focus))}
       [icons/icon :chat] (t :ui/cat-ask-guide)]]
     (cond
       (empty? courses)
       [:div.empty
        [icons/icon :notes "empty-ico"]
        [:p (t :ui/cat-none)]
        [:a.btn.primary {:href "#/course/new"} [icons/icon :plus] (t :ui/new-course)]]
       (empty? hits) [:p.muted (t :ui/nothing-found)]
       :else
       [:div.cat-grid
        (for [e (sort-by #(:title (:course %)) hits)]
          ^{:key (str (:id (:graph e)) "/" (:id (:course e)))}
          [course-card (:kg e) (:course e) (:graph e)])])]))

(defn- hats
  "The one switch between the two homes."
  []
  (let [view (:view @route)
        learner? (#{:catalog :learn} view)]
    [:div.hats
     [:a.hat {:class (when-not learner? "on") :href "#/"} [icons/icon :graph] (t :ui/hat-author)]
     [:a.hat {:class (when learner? "on") :href "#/courses"} [icons/icon :notes] (t :ui/hat-learn)]]))

(defn app []
  (let [kg (store/kg)]
    ;; .past: the as-of view — CSS removes every control that writes
    [:div.app {:class (when @store/as-of "past")}
     [:header
      [:button.rbtn.sm.burger {:title (t :ui/menu) :aria-label (t :ui/menu)
                              :on-click #(reset! drawer-open true)}
       [icons/icon :burger]]
      [:h1 {:on-click #(goto! "#/")} "Loretile"]
      ;; a graph with nothing to learn has no learner's hat and nothing to
      ;; count toward a course (wiki/Profiles.md) — absent, never disabled
      (when (profile/learnable? (fold/profile kg)) [hats])
      ;; nothing to count on an empty graph, and "0 / 0" is noise on the one
      ;; screen that should read as an invitation
      (when (and (seq (:concepts kg)) (profile/learnable? (fold/profile kg))
                 (not (#{:catalog :learn} (:view @route))))
        [tracker kg])]
     [drawer]
     [demo-picker]
     [new-graph-modal]
     [error-banner]
     (when @show-new-concept
       [:div.modal {:on-click #(reset! show-new-concept false)}
        [:div {:on-click #(.stopPropagation %)} [new-concept-form]]])
     [edge-type-chooser kg]
     (when @palette-open [palette kg])
     (when @link-mode
       [:div.hint (t :ui/link-mode-hint)])
     (let [view (:view @route)
           split {:class (str "win-" (name (if (= :chat view) @chat/size @win-size)))}]
       (case view
         ;; the concept is a WINDOW over the map (kintale gpopup), not a column
         :concept [:div.map-page
                   [map-view kg]
                   [:div.gpopup split ^{:key (:id @route)} [concept-panel kg (:id @route)]]]
         :course [:div.split split [map-view kg] [course-panel kg (:id @route)]]
         :source [:div.split split [map-view kg] ^{:key (:id @route)} [source-panel kg (:id @route) (:anchor @route)]]
         :chat [:div.split split [map-view kg] [chat/chat-view]]
         :settings [settings-page]
         :ontology [ontology-page kg]
         :graphs [graphs-page]
         ;; the assistant lives where you already are (Assistant design #1):
         ;; on the catalog, in learner mode, beside the courses
         :catalog [:div.split.catalog-split {:class "win-normal"} [catalog kg] [guide/view]]
         :learn [learn/view (:id @route)]
         [map-view kg]))
     (when @snack [:div.snack @snack])
     [peer-pills]]))
