(ns aps.parts.frontend.api.queue
  "Batching up change events for sending to backend"
  (:require
   [aps.parts.common.observe :as o]
   [aps.parts.frontend.storage.protocol :refer [process-batched-changes]]
   [aps.parts.frontend.storage.registry :as storage-registry]
   [cljs.core.async :refer [<! >! alts! chan close! go-loop put! timeout]]
   [re-frame.core :as rf]))

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

(def changes-chan (chan))
(def debounced-chan (debounce-batch changes-chan 2000))

(defn start
  "Start a loop sending batched map updates for a specific map ID to the
  backend"
  [map-id]
  (o/info "queue.start" "update queue started for map" map-id)
  (go-loop []
    (let [batch (<! debounced-chan)]
      (when batch
        (when-let [backend (storage-registry/get-backend)]
          (let [response (<! (process-batched-changes backend map-id batch))]
            (o/debug "queue.batch-response" "batch update response" response)
            ;; A failed batch was rolled back server-side, so the canvas no
            ;; longer matches what's stored — that must never be silent.
            (when-not (:success response)
              (rf/dispatch [:map/batch-failed]))))))
    (recur)))

(defn stop
  "Close channels and stop processing the queue"
  []
  (o/info "queue.stop" "update queue stopped"))

(defn add-events!
  "Enqueue canonical change-events for batched delivery to the backend.
   Events are built by `aps.parts.common.change-event` constructors."
  [events]
  (doseq [event events]
    (put! changes-chan event)))
