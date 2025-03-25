(ns parts.frontend.api.queue
  "Batching up change events for efficient sending to backend"
  (:require
   [cljs.core.async :refer [<! >! alts! chan close! go go-loop timeout]]
   [parts.frontend.api.core :refer [send-batched-updates]]))

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
  "Start a loop sending batched system updates to the backend"
  []
  (println "[queue] update queue started")
  (go-loop []
    (let [batch (<! debounced-chan)]
      (when batch
        (let [response (send-batched-updates batch)]
          (println "Batch response:" (:status response) (:body response)))))
    (recur)))

(defn stop
  "Close channles and stop processing the queue"
  []
  ;; (close! changes-chan)
  ;; (close! debounced-chan)
  (println "[queue] update queue stopped"))

(defn add
  "Process a node or edge change event and put on queue when appropriate"
  [entity changes]
  {:pre [(or (= entity :node) (= entity :edge))]}
  (println "[process-change]" entity changes)
  (go
    (>! changes-chan {:type "generic"
                      :data changes})))
