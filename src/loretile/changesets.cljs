;; Changesets (wiki/Changesets.md): TEXT that changes a graph — an implemented
;; story, a spec, notes — posted to the server with the graph's id in its
;; header. The open graph's pending ones are listed here; picking one makes it
;; a SOURCE of the graph (`changeset:<id>`: applied, with the link back) and
;; hands its text to the assistant, which reads it against the graph and
;; offers cards — new topics and links, edits and removals of existing ones.
(ns loretile.changesets
  (:require [promesa.core :as p]
            [reagent.core :as r]
            [loretile.blobs :as blobs]
            [loretile.chat :as chat]
            [loretile.i18n :refer [t]]
            [loretile.ops :as ops]
            [loretile.store :as store]
            [loretile.sync :as sync]))

(defonce pending (r/atom []))   ; the open graph's: [{:id :title :source}]

(defn refresh!
  "The open graph's pending changesets, from the server. Backend mode only."
  []
  (if-not sync/enabled?
    (reset! pending [])
    (let [gid @store/current]
      (-> (sync/changesets gid)
          (p/then #(when (= gid @store/current) (reset! pending (vec %))))
          (p/catch (fn [_] nil))))))

;; listed again whenever another graph is opened
(defonce ^:private watcher
  (add-watch store/current ::changesets (fn [_ _ a b] (when (not= a b) (refresh!)))))

(defn source-id [id] (str "changeset:" id))

(defn pick!
  "Take changeset `id` up: a new sitting, the changeset a source of the open
   graph (once the server stored it, it is no longer pending), then its text
   read by the assistant as a changeset. A promise."
  [id]
  (let [graph @store/current]
    (p/let [{:keys [title text source]} (sync/changeset id)]
      (when (and (store/append-to? graph) (chat/new-conversation!))
        (reset! chat/graph graph)
        (let [src (source-id id)]
          (blobs/put! (str src ".md") text)
          (store/after! (store/append! (ops/add-source {:id src :title title :ref (or source id)}))
                        (fn [err]
                          (refresh!)
                          (if err
                            (reset! store/last-error err)
                            (do (reset! chat/doc {:name title :text text :source-id src :changeset? true})
                                (swap! chat/msgs conj {:who :sys :text (t :chat/changeset-picked {"{title}" title})})
                                (chat/send! ""))))))))))
