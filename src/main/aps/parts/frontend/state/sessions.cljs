(ns aps.parts.frontend.state.sessions
  "Pure state transitions for therapy Sessions (ADR-0014).

   The loaded Map's Sessions live under `[:map :sessions]`, in the
   server's anchor order — so the active Session is simply the last, and
   \"reopening a Map resumes the latest Session\" needs no stored state.

   `editable?` is the one seam for the read-only canvas: editing requires
   an active Session, demo Maps are exempt, and a Map whose Sessions
   haven't loaded yet reads as read-only — it must prove it is editable,
   never be assumed so. TASK-073.03's viewing-the-past state joins here
   as a second `read-only-reason`.

   Dependency-free so the kaocha cljs suite can unit-test it (that suite
   carries no re-frame); `state/handlers` and the session chip consume it.")

(defn apply-sessions
  "Land a fetched Session list, dropping a stale response for a Map that
   is no longer the open one (navigated away mid-request)."
  [db map-id sessions]
  (if (= map-id (get-in db [:map :id]))
    (assoc-in db [:map :sessions] (vec sessions))
    db))

(defn active-session
  "The active Session — always the latest by anchor, the list's last —
   or nil when none exist or none are loaded yet."
  [db]
  (peek (get-in db [:map :sessions])))

(defn read-only-reason
  "Why the canvas refuses edits — nil when editable. The growth point for
   new reasons: TASK-073.03's Time-travel mode inserts `:viewing-past` as
   a clause here. `editable?` derives from this, so a reason and the gate
   cannot drift apart."
  [db]
  (cond
    (:demo-mode db)                     nil
    ;; A key access, not a require: `state/time-travel` depends on this
    ;; namespace, so the gate reads its subtree directly.
    (get-in db [:time-travel :active?]) :viewing-past
    (nil? (active-session db))          :no-session
    :else                               nil))

(defn editable?
  "Editing requires an active Session (ADR-0014); demo Maps are exempt."
  [db]
  (nil? (read-only-reason db)))

(defn set-error
  "Store a Session operation's server refusal verbatim — the server is
   the judge (e.g. of emptiness for delete), the client just relays."
  [db message]
  (assoc-in db [:ui :session-error] message))

(defn clear-error
  [db]
  (update db :ui dissoc :session-error))

(defn add-session
  "A started Session appends — by construction the latest, so it becomes
   the active one."
  [db session]
  (-> db
      (update-in [:map :sessions] (fnil conj []) session)
      clear-error))

;; -- Undo window --------------------------------------------------------------
;; "Undo new session" is honest only while the server would actually allow
;; the delete, and the client cannot compute that from what it sees (a
;; part created and deleted again still counts as content, but its history
;; rows never reach the client). So the window is tracked, not derived:
;; opened when this client starts a Session, closed by the first content
;; creation — a strict subset of the server's rule (edits to older
;; entities close nothing, matching `require-empty!`), so the affordance
;; can never draw a refusal. A reload loses the window; the stranded
;; empty Session that can leave behind is accepted (and arguably a valid
;; record: "we met, nothing changed").

(defn open-undo-window
  [db session-id]
  (assoc-in db [:ui :session-undo-id] session-id))

(defn close-undo-window
  [db]
  (update db :ui dissoc :session-undo-id))

(defn undoable?
  "True while the active Session is the one this client just started and
   nothing has been created in it yet."
  [db]
  (let [active (active-session db)]
    (boolean (and active
                  (= (:id active) (get-in db [:ui :session-undo-id]))))))

;; -- Trigger save feedback -----------------------------------------------------

(defn mark-trigger-saved
  "The PUT round-tripped — the quiet 'Saved' can show."
  [db]
  (assoc-in db [:ui :session-trigger-saved?] true))

(defn trigger-saved?
  [db]
  (boolean (get-in db [:ui :session-trigger-saved?])))

(defn set-trigger
  "Optimistically retitle one Session's trigger. Typing again withdraws
   the 'Saved' indicator until the next round-trip confirms."
  [db session-id trigger]
  (-> db
      (update-in [:map :sessions]
                 (fn [sessions]
                   (mapv #(if (= (:id %) session-id)
                            (assoc % :trigger trigger)
                            %)
                         sessions)))
      (update :ui dissoc :session-trigger-saved?)))

(defn remove-session
  "Drop a deleted Session; the previous one becomes active again — the
   started-by-mistake undo. Its window closes with it."
  [db session-id]
  (-> db
      (update-in [:map :sessions]
                 (fn [sessions]
                   (filterv #(not= (:id %) session-id) sessions)))
      close-undo-window
      clear-error))

(defn display-label
  "\"Session {ordinal} — {trigger}\", ordinal alone when no trigger."
  [{:keys [ordinal trigger]}]
  (cond-> (str "Session " ordinal)
    (seq trigger) (str " — " trigger)))
