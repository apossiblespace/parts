(ns aps.parts.frontend.state.time-travel
  "Pure state transitions for Time-travel mode (TASK-073.03, ADR-0014):
   the read-only mode that steps the canvas between Sessions, one click
   per step — the launch form of the Scrubber.

   The mode lives under its own `[:time-travel]` subtree: the live
   `[:map]`, its optimistic state, and the change-event queue are never
   touched by browsing, so exiting is a pure render switch. Snapshots
   are cached per Session id for the visit — the past is immutable (only
   the ops-level `correction!` can rewrite it), so a revisited Session
   never refetches. The latest Session has no snapshot: its range is
   open, its final state IS the live Map.

   Re-frame-free so the kaocha cljs suite can unit-test it; the
   read-only gate itself lives in `sessions/read-only-reason`, which
   reads this subtree's `:active?` flag (a key access, not a require —
   this namespace depends on `sessions`, never the reverse)."
  (:require
   [aps.parts.frontend.state.sessions :as sessions]))

(defn active? [db]
  (boolean (get-in db [:time-travel :active?])))

(defn- clear-selection
  "Selection ids must not dangle across content sources — cleared on
   both mode boundaries."
  [db]
  (update db :ui assoc :selected-node-ids [] :selected-edge-ids []))

(defn enter
  "Enter the mode at the latest Session (the live view); stepping goes
   back from there. A no-op for a Map with fewer than two Sessions —
   there is no history to travel (the entry button is hidden too)."
  [db]
  (let [the-sessions (get-in db [:map :sessions])]
    (if (< (count the-sessions) 2)
      db
      (-> db
          (assoc :time-travel {:active?    true
                               :session-id (:id (peek the-sessions))
                               :snapshots  {}})
          clear-selection))))

(defn exit [db]
  (-> db (dissoc :time-travel) clear-selection))

(defn viewing
  "The viewed Session and its place on the timeline:
   `{:session … :index … :count … :latest? …}` (1-based index), or nil
   outside the mode."
  [db]
  (when (active? db)
    (let [the-sessions (get-in db [:map :sessions])
          session-id   (get-in db [:time-travel :session-id])
          idx          (first (keep-indexed
                               (fn [i s] (when (= (:id s) session-id) i))
                               the-sessions))]
      (when idx
        {:session (nth the-sessions idx)
         :index   (inc idx)
         :count   (count the-sessions)
         :latest? (= idx (dec (count the-sessions)))}))))

(defn step
  "Move the view one Session `:back` or `:forward`, clamped at the ends.
   Clears any held fetch error — the failure belonged to the previous
   target."
  [db direction]
  (if-let [{:keys [index]} (viewing db)]
    (let [the-sessions (get-in db [:map :sessions])
          idx          (-> (case direction
                             :back    (- index 2)
                             :forward index)
                           (max 0)
                           (min (dec (count the-sessions))))]
      (-> db
          (assoc-in [:time-travel :session-id] (:id (nth the-sessions idx)))
          (update :time-travel dissoc :error)))
    db))

(defn snapshot-needed?
  "True when the viewed Session is a past one whose snapshot isn't
   cached yet — the signal to fetch (and the bar's loading state)."
  [db]
  (boolean
   (and (active? db)
        (not (:latest? (viewing db)))
        (nil? (get-in db [:time-travel :snapshots
                          (get-in db [:time-travel :session-id])])))))

(defn store-snapshot
  "Cache a fetched Session view. Dropped when the mode was exited while
   the fetch was in flight — never grafted onto a non-travelling db."
  [db session-id the-map]
  (if (active? db)
    (-> db
        (assoc-in [:time-travel :snapshots session-id]
                  (select-keys the-map [:parts :relationships]))
        (update :time-travel dissoc :error))
    db))

(defn canvas-content
  "What the canvas renders: the live Map in Editing mode and at the
   latest Session; the cached snapshot when viewing a past one (nil
   content while it loads — the fetch is one round trip, once)."
  [db]
  (if (and (active? db) (not (:latest? (viewing db))))
    (get-in db [:time-travel :snapshots
                (get-in db [:time-travel :session-id])])
    {:parts         (get-in db [:map :parts])
     :relationships (get-in db [:map :relationships])}))

(defn viewed-ordinal
  "Which Session's newcomers wear the accented recency badge: the viewed
   Session in the mode, the active Session in Editing mode."
  [db]
  (if (active? db)
    (:ordinal (:session (viewing db)))
    (:ordinal (sessions/active-session db))))

(defn set-error [db message]
  (if (active? db)
    (assoc-in db [:time-travel :error] message)
    db))

(defn error [db]
  (get-in db [:time-travel :error]))
