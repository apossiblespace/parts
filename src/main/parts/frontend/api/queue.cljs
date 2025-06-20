(ns parts.frontend.api.queue
  "Batching up change events for sending to backend"
  (:require
   [cljs.core.async :refer [<! >! alts! chan close! go-loop put! timeout]]
   [parts.frontend.observe :as o]
   [parts.frontend.storage.registry :as storage-registry]
   [parts.frontend.storage.protocol :refer [process-batched-changes]]))

(defn debounce-batch
  "Creates a debounced channel that batches incoming changes from `input-chan`.
   After a period of inactivity specified by `debounce-ms`, it sends the
   accumulated batch of changes to the output channel as a vector. When
   `input-chan` is closed, it sends any remaining batch and closes the output
   channel."
  [input-chan debounce-ms]
  (let [output-chan (chan)]
    (go-loop [batch []]
      (let [timer (timeout debounce-ms)
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

          ;; 3. Input is closed (value is nil), send any remaining changes, clean up.
          :else
          (do
            (when (seq batch)
              (>! output-chan batch))
            (close! output-chan)))))
    output-chan))

(def changes-chan (chan))
(def debounced-chan (debounce-batch changes-chan 2000))

(defn start
  "Start a loop sending batched system updates for a specific system ID to the backend"
  [system-id]
  (o/info "queue.start" "update queue started for system" system-id)
  (go-loop []
    (let [batch (<! debounced-chan)]
      (when batch
        (when-let [backend (storage-registry/get-backend)]
          (let [response (<! (process-batched-changes backend system-id batch))]
            (o/debug "queue.batch-response" "batch update response" response)))))
    (recur)))

(defn stop
  "Close channels and stop processing the queue"
  []
  (o/info "queue.stop" "update queue stopped"))

(defmulti normalize-event
  "Returns a normalized event to be enqueued"
  (fn [entity event]
    [entity (:type event)]))

(defmethod normalize-event [:part "position"]
  [entity event]
  (when-not (:dragging event)
    {:entity entity
     :id (:id event)
     :type (:type event)
     :data (:data event)}))

(defmethod normalize-event [:part "remove"]
  [entity event]
  {:entity entity
   :id (:id event)
   :type (:type event)
   :data {}})

(defmethod normalize-event [:part "create"]
  [entity event]
  {:entity entity
   :id (:id event)
   :type (:type event)
   :data (:data event)})

(defmethod normalize-event [:part "update"]
  [entity event]
  {:entity entity
   :id (:id event)
   :type (:type event)
   :data (:data event)})

(defmethod normalize-event [:relationship "create"]
  [entity event]
  {:entity entity
   :id (:id event)
   :type (:type event)
   :data (:data event)})

(defmethod normalize-event [:relationship "update"]
  [entity event]
  {:entity entity
   :id (:id event)
   :type (:type event)
   :data (:data event)})

(defmethod normalize-event [:relationship "remove"]
  [entity event]
  {:entity entity
   :id (:id event)
   :type (:type event)
   :data {}})

(defmethod normalize-event :default
  [entity event]
  (o/warn "queue.normalize-event" "unhandled event type" entity event)
  nil)

(defn- normalize-events
  "Normalize a collection of events for enqueuing"
  [entity events]
  {:pre [(or (= entity :part) (= entity :relationship))]}
  (keep #(normalize-event entity %) events))

(defn add-events!
  "Process and enqueue part or relationship change events"
  [entity events]
  (when-let [normalized (seq (normalize-events entity events))]
    (doseq [event normalized]
      (put! changes-chan event))))
