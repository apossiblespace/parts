(ns aps.parts.frontend.state.save-status
  "Pure state for the map save-status indicator (TASK-077): the single
   feedback surface for the app's silent autosave. Green is the RESTING
   state — the indicator answers \"does anything need saving?\", not
   \"did a save happen this visit\".

   Two kinds of write feed it:
   - the change-event queue (parts/relationships): `mark-dirty` while a
     batch accumulates in the debounce window, `flush-started` /
     `request-done` around the POST;
   - direct writes (map rename, session trigger): `write-*` around the
     request — they have no dirty phase, the request starts immediately.

   Failure semantics differ deliberately. A failed BATCH was rolled back
   server-side, so the canvas no longer matches the stored Map — that is
   the existing `[:map :save-error]` fact (the banner's flag), which
   `status` reads directly rather than shadowing; it clears when the Map
   is reloaded. A failed direct write rolls back its optimistic state,
   so consistency is restored and the next successful write clears it.

   Dependency-free so the kaocha cljs suite can unit-test it.")

(defn mark-dirty [db]
  (assoc-in db [:save-status :dirty?] true))

(defn flush-started
  "The queue drained its pending events into a batch and is POSTing it."
  [db]
  (-> db
      (assoc-in [:save-status :dirty?] false)
      (update-in [:save-status :in-flight] (fnil inc 0))))

(defn request-done
  "A queue batch or direct write finished, either way. Clamped at zero:
   a late flush for a Map navigated away from must not go negative."
  [db]
  (update-in db [:save-status :in-flight] #(max 0 (dec (or % 0)))))

(defn write-started [db]
  (update-in db [:save-status :in-flight] (fnil inc 0)))

(defn write-succeeded [db]
  (-> db request-done (assoc-in [:save-status :write-failed?] false)))

(defn write-failed [db]
  (-> db request-done (assoc-in [:save-status :write-failed?] true)))

(defn status
  "The indicator's state: :error > :saving > :dirty > :saved."
  [db]
  (let [{:keys [dirty? in-flight write-failed?]} (:save-status db)]
    (cond
      ;; A key access, not a shadow flag: the banner's batch-failure
      ;; fact (set by `map-updates/mark-batch-failed`) is the truth.
      (or (get-in db [:map :save-error]) write-failed?) :error
      (pos? (or in-flight 0))                           :saving
      dirty?                                            :dirty
      :else                                             :saved)))
