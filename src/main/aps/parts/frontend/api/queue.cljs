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
   [aps.parts.frontend.storage.protocol :refer [process-batched-changes]]
   [aps.parts.frontend.storage.registry :as storage-registry]
   [cljs.core.async :refer [<! >! alts! chan close! go-loop put! timeout]]
   [re-frame.core :as rf]))

(def ^:private debounce-ms 2000)

(defn debounce-batch
  "Creates a debounced channel that batches incoming changes from `input-chan`.
   After a period of inactivity specified by `debounce-ms`, it sends the
   accumulated batch of changes to the output channel as a vector. When
   `input-chan` is closed, it sends any remaining batch and closes the output
   channel."
  [input-chan debounce-ms]
  (let [output-chan (chan)]
    (go-loop [batch []]
      (let [timer          (timeout debounce-ms)
            [value source] (alts! [input-chan timer])]
        (cond
          ;; 1. Timer fired, send the batch accumulated so far.
          (= source timer)
          (do
            (when (seq batch)
              (>! output-chan batch))
            (recur []))

          ;; 2. Received a new change from input, batch it up.
          value
          (recur (conj batch value))

          ;; 3. Input is closed (value is nil), send any remaining changes,
          ;; clean up.
          :else
          (do
            (when (seq batch)
              (>! output-chan batch))
            (close! output-chan)))))
    output-chan))

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
          (o/debug "queue.batch-response" "batch update response" response)
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

(defn stop
  "Tear down the running queue, if any. Closing the input flushes any pending
   batch to the current Map's backend (via the debounce cascade), then the
   consumer exits."
  []
  (when-let [input-chan @active]
    (o/info "queue.stop" "update queue stopped")
    (close! input-chan)
    (reset! active nil)))

(defn start
  "Start a change-event queue bound to `map-id`. Replaces any running queue
   first, so navigating between Maps never accumulates consumers."
  [map-id]
  (stop)
  (o/info "queue.start" "update queue started for map" map-id)
  (let [input-chan (chan)]
    (consume! map-id (debounce-batch input-chan debounce-ms))
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
