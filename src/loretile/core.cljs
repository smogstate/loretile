(ns loretile.core
  (:require [reagent.dom.client :as rdc]
            [loretile.i18n :as i18n]
            [loretile.chat :as chat]
            [loretile.graph-local :as graph-local]
            [loretile.merge :as mrg]
            [loretile.changesets :as cs]
            [loretile.store :as store]
            [loretile.sync :as sync]
            [loretile.tool-defs]                ; registers the assistant's read tools
            [loretile.ui :as ui]
            [loretile.vectors :as vectors]))

(defonce root (atom nil))

(defn ^:export init []
  ;; clickjacking (security review 2026-10-03): frame-ancestors can't be set in
  ;; a meta tag and this host sends no headers, so refuse to run framed — a
  ;; hostile page can't trick taps on «Принять» or delete through an iframe
  (if-not (identical? js/window.top js/window.self)
    (set! (.. js/document -body -textContent) "Loretile cannot run inside a frame.")
    ;; the logs come out of IndexedDB first (async)
    (.then (store/load!) (fn []
  (sync/start!)                     ; graphs kept by the server, edits server-first (wiki/Graph-Store.md)
  (graph-local/expose!)
  ;; local mode: the projection in SQLite, in the one tab holding it. A BIG
  ;; log's first render waits (≤ 4 s) for the graph built from its rows — it
  ;; replaces a refold of seconds; a small one renders at once (its fold is instant).
  ;; Server mode: EVERY graph waits (≤ 4 s) for the server's rows — the page's
  ;; graph is the server's projection (wiki/Graph-Store.md § Backend mode)
  (.then (let [booting (if sync/enabled? (sync/boot-kg!) (graph-local/start!))]
           (if (and booting (or sync/enabled? (> (count @store/events) 5000)))
             (js/Promise.race #js [booting (js/Promise. (fn [ok] (js/setTimeout ok 4000)))])
             (js/Promise.resolve nil)))
         (fn []
  (vectors/load!)
  (chat/restore!)
  (mrg/refresh!)                    ; merge files waiting for the open graph
  (cs/refresh!)                     ; changesets the server holds for it (wiki/Changesets.md)
  (ui/apply-theme!)
  (store/watch-other-tabs!)
  (set! (.-lang js/document.documentElement) (name @i18n/locale))
  (ui/read-route!)
  (js/window.addEventListener "hashchange" ui/read-route!)
  (ui/global-keys!)
  (reset! root (rdc/create-root (js/document.getElementById "app")))
  (rdc/render @root [ui/app])))))))
