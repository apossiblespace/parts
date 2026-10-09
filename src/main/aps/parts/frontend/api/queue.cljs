(ns aps.parts.frontend.api.queue
  "Batching up change events for sending to the backend.

   One queue belongs to one open Map. `start` builds a fresh pipe of
   batcher, batch channel and consumer for the id of that Map, and stores
   it in `active`. `stop` tears it down. When the page is hidden, the
   pending batch goes in a request that survives the page going away.
   Keeping the channels per-Map (rather than
   one module-global pipe) is what stops a remounting Map view from leaving
   an orphaned consumer behind: without it, navigating between Maps
   accumulated live consumers racing for the same batches, so one Map's
   changes could be POSTed to another Map's id (see TASK-064)."
  (:require
   [aps.parts.common.observe :as o]
   [aps.parts.frontend.api.batch :as batch]
   [aps.parts.frontend.state.save-status :as save-status]
   [aps.parts.frontend.storage.protocol :refer [process-batched-changes send-now]]
   [aps.parts.frontend.storage.registry :as storage-registry]
   [cljs.core.async :refer [<! chan close! go go-loop put!]]
   [re-frame.core :as rf]
   [re-frame.db :as rf-db]))

;; This atom counts the batches that went to the consumer and have no
;; answer yet, for all queues.
(defonce ^:private unanswered (atom 0))

(defn- land!
  "Waits for the `result` channel of a batch and reports the result to the
   UI."
  [result]
  (rf/dispatch [:save-status/flush-started])
  (go
    (let [response (<! result)]
      (swap! unanswered dec)
      ;; Ids/counts only: the response echoes entity content, which must
      ;; not reach the console even at debug level.
      (o/debug "queue.batch-response" "batch done"
               {:success (:success response)
                :results (count (:results response))})
      (rf/dispatch [:save-status/request-done])
      ;; A failed batch was rolled back server-side, so the canvas no
      ;; longer matches what's stored — that must never be silent.
      ;; (`:map/save-error` also drives the indicator's red state.)
      (when-not (:success response)
        (rf/dispatch [:map/batch-failed])))))

(defn- consume!
  "Posts each batch from `batches` to the backend of `map-id`, one at a
   time and in order. An item `{:sent result}` is a batch that is already
   sent, and the consumer only waits for its `result`. Stops when `batches`
   closes."
  ;; `stop` closes `batches` after the final flush, so that batch still goes
  ;; to its own Map.
  [map-id batches]
  (go-loop []
    (when-let [{:keys [sent events]} (<! batches)]
      (<! (land! (or sent
                     (process-batched-changes (storage-registry/get-backend)
                                              map-id events))))
      (recur))))

;; This atom holds the queue of the open Map, or nil. The value has the
;; keys `:map-id`, `:batcher` and `:batches`. At most one queue runs at a
;; time.
(defonce ^:private active (atom nil))

(defn- enqueue!
  [batches events]
  (when (storage-registry/get-backend)
    (swap! unanswered inc)
    (put! batches {:events events})))

(defn- send-before-hide!
  "Sends the pending batch in a request that survives the page going away.
   When an earlier batch has no answer yet, or the batch is too large,
   sends it through the queue instead. Returns `:keepalive`, `:queue`, or
   nil when nothing was pending."
  ;; A keepalive request could overtake an earlier batch. The server must
  ;; get batches in order, because a batch fails as a whole (ADR-0003).
  ;; ponytail: closing the tab while a batch is in flight can still lose the
  ;; pending batch. The leave-page prompt warns. Upgrade: send the queued
  ;; batches with keepalive too, in order.
  []
  (when-let [{:keys [map-id batcher batches]} @active]
    (if (pos? @unanswered)
      (do (batch/flush! batcher) :queue)
      (when-let [events (batch/take! batcher)]
        (if-let [result (some-> (storage-registry/get-backend)
                                (send-now map-id events))]
          ;; The answer goes through the consumer, so that later batches
          ;; wait for it.
          (do (swap! unanswered inc)
              (put! batches {:sent result})
              :keepalive)
          (do (enqueue! batches events) :queue))))))

(defn- on-visibility-change []
  ;; Hidden is the last event that a page can rely on. iPad Safari can
  ;; discard a background tab with no further event, and closing a tab
  ;; also hides it.
  (when (= "hidden" (.-visibilityState js/document))
    (send-before-hide!)))

(defn- on-before-unload
  [^js event]
  ;; The pending batch goes now, so the prompt shows only when an edit can
  ;; still be lost: a request is in flight, or the batch is in the queue.
  (when (or (= :saving (save-status/status @rf-db/app-db))
            (pos? @unanswered)
            (= :queue (send-before-hide!)))
    (.preventDefault event)
    ;; Older browsers show the prompt only for a truthy returnValue.
    (set! (.-returnValue event) true)))

(defn stop
  "Tears down the running queue, if any. Sends the pending batch to the
   backend of the current Map first."
  []
  (when-let [{:keys [batcher batches]} @active]
    (o/info "queue.stop" "update queue stopped")
    (.removeEventListener js/document "visibilitychange" on-visibility-change)
    (.removeEventListener js/window "pagehide" send-before-hide!)
    (.removeEventListener js/window "beforeunload" on-before-unload)
    (batch/flush! batcher)
    (close! batches)
    (reset! active nil)))

(defn start
  "Start a change-event queue bound to `map-id`. Replaces any running queue
   first, so navigating between Maps never accumulates consumers."
  [map-id]
  (stop)
  (o/info "queue.start" "update queue started for map" map-id)
  (let [batches (chan 100)]
    (consume! map-id batches)
    (.addEventListener js/document "visibilitychange" on-visibility-change)
    ;; Older Safari fires no visibilitychange when the page unloads.
    (.addEventListener js/window "pagehide" send-before-hide!)
    (.addEventListener js/window "beforeunload" on-before-unload)
    (reset! active {:map-id  map-id
                    :batches batches
                    :batcher (batch/batcher {:idle-ms  2000
                                             :max-ms   10000
                                             :on-batch #(enqueue! batches %)})})))

(defn add-events!
  "Enqueue canonical change-events for batched delivery to the backend.
   Events are built by `aps.parts.common.change-event` constructors. A no-op
   when no Map queue is running."
  [events]
  (when-let [{:keys [batcher]} @active]
    (when (seq events)
      (rf/dispatch [:save-status/dirty]))
    (doseq [event events]
      (batch/add! batcher event))))
