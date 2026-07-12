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
   read-only gate itself lives in `sessions/read-only-reason`."
  (:require
   [aps.parts.frontend.state.sessions :as sessions]))

(defn active? [db]
  (boolean (get-in db [:time-travel :active?])))

(defn has-history?
  "A Map is travelable once it has two Sessions — the single gate shared
   by `enter` and the entry button's visibility."
  [the-sessions]
  (>= (count the-sessions) 2))

(defn key-event
  "The event a bare keypress dispatches while the mode is active, or nil
   for keys that aren't the mode's — those fall through to the tool
   shortcuts (V/H and the Space spring-hand keep working). The pure
   key-router twin of `toolbar/shortcut-tool`."
  [key]
  (case key
    "Escape"     [:time-travel/exit]
    "ArrowLeft"  [:time-travel/step :back]
    "ArrowRight" [:time-travel/step :forward]
    nil))

(defn toggle-key?
  "T toggles the mode from either side — the shortcut the History
   button's tooltip advertises. Escape additionally exits (`key-event`)."
  [key]
  (contains? #{"t" "T"} key))

(defn- clear-selection
  "Selection ids must not dangle across content sources — cleared on
   both mode boundaries."
  [db]
  (update db :ui assoc :selected-node-ids [] :selected-edge-ids []))

(defn enter
  "Enter the mode at the latest Session (the live view); stepping goes
   back from there. A no-op for a Map with fewer than two Sessions —
   there is no history to travel (the entry button is hidden too).

   `:session-id` is the TARGET the bar navigates; `:shown-session-id`
   is what the canvas renders. They part ways only while an uncached
   snapshot is in flight — the previous content stays on screen, so a
   step never blanks the canvas."
  [db]
  (let [the-sessions (get-in db [:map :sessions])
        latest-id    (:id (peek the-sessions))]
    (if-not (has-history? the-sessions)
      db
      (-> db
          (assoc :time-travel {:active?          true
                               :session-id       latest-id
                               :shown-session-id latest-id
                               :snapshots        {}})
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

(defn- displayable?
  "A Session's content can show right now: it is the latest (the live
   Map) or its snapshot is cached."
  [db session-id]
  (or (= session-id (:id (sessions/active-session db)))
      (some? (get-in db [:time-travel :snapshots session-id]))))

(defn step
  "Move the target one Session `:back` or `:forward`, clamped at the
   ends. The shown content follows immediately when the target is
   displayable; otherwise it waits for the snapshot (`store-snapshot`).
   Clears any held fetch error — the failure belonged to the previous
   target."
  [db direction]
  (if-let [{:keys [index]} (viewing db)]
    (let [the-sessions (get-in db [:map :sessions])
          idx          (-> (case direction
                             :back    (- index 2)
                             :forward index)
                           (max 0)
                           (min (dec (count the-sessions))))
          target-id    (:id (nth the-sessions idx))]
      (cond-> (-> db
                  (assoc-in [:time-travel :session-id] target-id)
                  (update :time-travel dissoc :error))
        (displayable? db target-id)
        (assoc-in [:time-travel :shown-session-id] target-id)))
    db))

(defn snapshot-needed?
  "True when the target Session's content can't show yet — the signal to
   fetch (and the bar's loading state)."
  [db]
  (boolean
   (and (active? db)
        (not (displayable? db (get-in db [:time-travel :session-id]))))))

(defn store-snapshot
  "Cache a fetched Session view; when it is the current target, the
   canvas switches to it (a late snapshot for a superseded target only
   fills the cache). Dropped when the mode was exited while the fetch
   was in flight — never grafted onto a non-travelling db."
  [db session-id the-map]
  (if (active? db)
    (cond-> (-> db
                (assoc-in [:time-travel :snapshots session-id]
                          (select-keys the-map [:parts :relationships]))
                (update :time-travel dissoc :error))
      (= session-id (get-in db [:time-travel :session-id]))
      (assoc-in [:time-travel :shown-session-id] session-id))
    db))

(defn- shown-session
  [db]
  (let [shown-id (get-in db [:time-travel :shown-session-id])]
    (some #(when (= (:id %) shown-id) %)
          (get-in db [:map :sessions]))))

(defn canvas-content
  "What the canvas renders: the live Map in Editing mode and when the
   SHOWN Session is the latest; otherwise the shown Session's snapshot.
   The shown Session lags the target while a snapshot is in flight, so
   stepping never blanks the canvas."
  [db]
  (let [shown-id (get-in db [:time-travel :shown-session-id])]
    (if (and (active? db)
             (not= shown-id (:id (sessions/active-session db))))
      (get-in db [:time-travel :snapshots shown-id])
      {:parts         (get-in db [:map :parts])
       :relationships (get-in db [:map :relationships])})))

(defn viewed-ordinal
  "Which Session's newcomers wear the accented recency badge: the SHOWN
   Session in the mode (badges must match the content on screen), the
   active Session in Editing mode."
  [db]
  (if (active? db)
    (:ordinal (shown-session db))
    (:ordinal (sessions/active-session db))))

(defn interpolate-parts
  "One frame of the session-switch glide: the TARGET Parts, with each
   Part that also existed in `from` placed `t` (0..1) of the way from
   its old position to its new one. Everything animates from this one
   source — the canvas re-derives nodes, edge curves, and badges from
   the interpolated positions, so nothing snaps out of step. Apply
   easing to `t` before calling; t=1 is exactly the target."
  [from to t]
  (let [from-by-id (into {} (map (juxt :id identity)) from)
        lerp       (fn [a b] (+ a (* t (- b a))))]
    (mapv (fn [part]
            (if-let [prev (from-by-id (:id part))]
              (assoc part
                     :position_x (lerp (:position_x prev) (:position_x part))
                     :position_y (lerp (:position_y prev) (:position_y part)))
              part))
          to)))

(defn set-error [db message]
  (if (active? db)
    (assoc-in db [:time-travel :error] message)
    db))

(defn error [db]
  (get-in db [:time-travel :error]))
