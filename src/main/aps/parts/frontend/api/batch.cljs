(ns aps.parts.frontend.api.batch
  "Batching of change events for the save queue (`api/queue`).

   Dependency-free apart from core.async, so the kaocha cljs suite (which
   loads no re-frame) can test it."
  (:require
   [cljs.core.async :refer [>! alts! chan close! go-loop timeout]]))

(def flush-signal
  "Put this on the input channel to send the pending batch now."
  ::flush)

(defn debounce-batch
  "Batch the values from `input-chan` onto the returned channel, as vectors.
   A batch goes out `idle-ms` after its last value, or `max-ms` after its
   first, whichever comes first, so continuous input cannot hold changes
   back for ever. `flush-signal` sends the batch at once. When
   `input-chan` closes, the remaining batch goes out and the returned
   channel closes. While nothing is pending, no timer runs."
  [input-chan {:keys [idle-ms max-ms]}]
  (let [output-chan (chan)]
    (go-loop [batch [] max-timer nil]
      (let [[value source] (alts! (if (seq batch)
                                    [input-chan (timeout idle-ms) max-timer]
                                    [input-chan]))]
        (cond
          (and (= source input-chan) (nil? value))
          (do (when (seq batch)
                (>! output-chan batch))
              (close! output-chan))

          (and (= source input-chan) (not= value flush-signal))
          (recur (conj batch value) (or max-timer (timeout max-ms)))

          :else
          (do (when (seq batch)
                (>! output-chan batch))
              (recur [] nil)))))
    output-chan))
