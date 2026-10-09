(ns aps.parts.frontend.api.batch
  "Batching of change events for the save queue (`api/queue`).

   The pending events live in an atom with plain timers, not inside a
   go-loop, so that a page-hide handler can take them synchronously.

   Dependency-free, so the kaocha cljs suite (which loads no re-frame) can
   test it.")

(defn batcher
  "Returns a batcher that gives each batch, a vector of events, to
   `on-batch`. A batch goes `idle-ms` after its last event or `max-ms`
   after its first event, whichever is sooner. While nothing is pending, no
   timer runs."
  [{:keys [idle-ms max-ms on-batch]}]
  (atom {:events  []      :idle   nil    :max      nil
         :idle-ms idle-ms :max-ms max-ms :on-batch on-batch}))

(defn take!
  "Removes and returns the pending events, or nil when there are none. The
   timers stop, so no other code sends these events."
  [b]
  (let [{:keys [events idle max]} @b]
    (js/clearTimeout idle)
    (js/clearTimeout max)
    (swap! b assoc :events [] :idle nil :max nil)
    (not-empty events)))

(defn flush!
  "Gives the pending batch to `on-batch` now, if there is one."
  [b]
  (when-let [events (take! b)]
    ((:on-batch @b) events)))

(defn add!
  "Adds `event` to the pending batch and restarts the idle timer."
  [b event]
  (let [{:keys [idle max idle-ms max-ms]} @b]
    (js/clearTimeout idle)
    (swap! b #(-> %
                  (update :events conj event)
                  (assoc :idle (js/setTimeout (fn [] (flush! b)) idle-ms))
                  (cond-> (nil? max)
                    (assoc :max (js/setTimeout (fn [] (flush! b)) max-ms)))))))
