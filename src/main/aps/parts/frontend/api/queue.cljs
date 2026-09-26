(ns aps.parts.frontend.api.queue
  "Batching up change events for sending to the backend.

   One queue belongs to one open Map. `start` builds a fresh pipe — input
   channel → debounce → consumer — bound to that Map's id and stores it in
   `active`; `stop` tears it down. Keeping the channels per-Map (rather than
   one module-global pipe) is what stops a remounting Map view from leaving
   an orphaned consumer behind: without it, navigating between Maps
   accumulated live consumers racing for the same batches, so one Map's
   changes could be POSTed to another Map's id (see TASK-064)."
  (:require
   [aps.parts.common.observe :as o]
   [aps.parts.frontend.api.batch :as batch]
   [aps.parts.frontend.state.save-status :as save-status]
   [aps.parts.frontend.storage.protocol :refer [process-batched-changes]]
   [aps.parts.frontend.storage.registry :as storage-registry]
   [cljs.core.async :refer [<! chan close! go-loop put!]]
   [re-frame.core :as rf]
   [re-frame.db :as rf-db]))

(defn- consume!
  "Drain debounced batches and POST each to `map-id`'s backend until the
   debounced channel closes — which `stop` triggers by closing the input,
   so a flushed final batch still lands on the Map it belongs to."
  [map-id debounced-chan]
  (go-loop []
    (when-let [batch (<! debounced-chan)]
      (when-let [backend (storage-registry/get-backend)]
        (rf/dispatch [:save-status/flush-started])
        (let [response (<! (process-batched-changes backend map-id batch))]
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
            (rf/dispatch [:map/batch-failed]))))
      (recur))))

;; The currently-open Map's input channel, or nil. At most one queue runs at
;; a time; `start` replaces it, `stop` clears it.
(defonce ^:private active (atom nil))

(defn flush!
  "Send the pending batch now instead of after the debounce."
  []
  (when-let [input-chan @active]
    (put! input-chan batch/flush-signal)))

(defn- on-visibility-change []
  ;; Hidden is the last moment a page can rely on: iPad Safari may discard
  ;; a background tab without any further event.
  (when (= "hidden" (.-visibilityState js/document))
    (flush!)))

(defn- on-before-unload
  ;; ponytail: if the user confirms leaving, a batch still in the debounce
  ;; window (up to ~2 s of edits) is lost. Upgrade: send the final batch
  ;; with `fetch` keepalive on pagehide.
  [^js event]
  (when (#{:dirty :saving} (save-status/status @rf-db/app-db))
    (.preventDefault event)
    ;; Older browsers show the prompt only for a truthy returnValue.
    (set! (.-returnValue event) true)))

(defn stop
  "Tear down the running queue, if any. Closing the input flushes any pending
   batch to the current Map's backend (via the debounce cascade), then the
   consumer exits."
  []
  (when-let [input-chan @active]
    (o/info "queue.stop" "update queue stopped")
    (.removeEventListener js/document "visibilitychange" on-visibility-change)
    (.removeEventListener js/window "beforeunload" on-before-unload)
    (close! input-chan)
    (reset! active nil)))

(defn start
  "Start a change-event queue bound to `map-id`. Replaces any running queue
   first, so navigating between Maps never accumulates consumers."
  [map-id]
  (stop)
  (o/info "queue.start" "update queue started for map" map-id)
  (let [input-chan (chan)]
    (consume! map-id (batch/debounce-batch input-chan {:idle-ms 2000 :max-ms 10000}))
    (.addEventListener js/document "visibilitychange" on-visibility-change)
    (.addEventListener js/window "beforeunload" on-before-unload)
    (reset! active input-chan)))

(defn add-events!
  "Enqueue canonical change-events for batched delivery to the backend.
   Events are built by `aps.parts.common.change-event` constructors. A no-op
   when no Map queue is running."
  [events]
  (when-let [input-chan @active]
    (when (seq events)
      (rf/dispatch [:save-status/dirty]))
    (doseq [event events]
      (put! input-chan event))))
