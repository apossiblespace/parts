(ns aps.parts.frontend.state.subs
  (:require
   [aps.parts.frontend.state.save-status :as save-status]
   [aps.parts.frontend.state.sessions :as sessions]
   [aps.parts.frontend.state.time-travel :as time-travel]
   [aps.parts.frontend.state.toolbar :as toolbar]
   [aps.parts.frontend.state.windows :as windows]
   [re-frame.core :as rf]))

(rf/reg-sub
 :demo
 (fn [db _]
   (boolean (:demo-mode db))))

(rf/reg-sub
 :minimal-demo
 (fn [db _]
   (= (:demo-mode db) :minimal)))

(rf/reg-sub
 :launched
 (fn [db _]
   (boolean (:launched db))))

(rf/reg-sub
 :maps/list
 (fn [db _]
   (get-in db [:maps :list])))

(rf/reg-sub
 :maps/loading
 (fn [db _]
   (get-in db [:maps :loading])))

(rf/reg-sub
 :map/id
 (fn [db _]
   (get-in db [:map :id])))

(rf/reg-sub
 :map/title
 (fn [db _]
   (get-in db [:map :title])))

(rf/reg-sub
 :map/parts
 (fn [db _]
   (get-in db [:map :parts])))

(rf/reg-sub
 :map/relationships
 (fn [db _]
   (get-in db [:map :relationships])))

(rf/reg-sub
 :map/save-error
 (fn [db _]
   (boolean (get-in db [:map :save-error]))))

;; -- Sessions (ADR-0014) ----------------------------------------------------

(rf/reg-sub
 :map/sessions
 ;; nil until the fetch lands — the chip renders nothing rather than a
 ;; false "no sessions" state.
 (fn [db _]
   (get-in db [:map :sessions])))

(rf/reg-sub
 :session/active
 (fn [db _]
   (sessions/active-session db)))

(rf/reg-sub
 :canvas/editable?
 (fn [db _]
   (sessions/editable? db)))

(rf/reg-sub
 :ui/session-error
 (fn [db _]
   (get-in db [:ui :session-error])))

(rf/reg-sub
 :session/undoable?
 (fn [db _]
   (sessions/undoable? db)))

(rf/reg-sub
 :map/save-status
 (fn [db _]
   (save-status/status db)))

(rf/reg-sub
 :map/epoch
 (fn [db _]
   (get-in db [:ui :map-epoch] 0)))

;; -- Time-travel mode (TASK-073.03) -----------------------------------------

(rf/reg-sub
 :time-travel/active?
 (fn [db _]
   (time-travel/active? db)))

(rf/reg-sub
 :time-travel/viewing
 (fn [db _]
   (time-travel/viewing db)))

(rf/reg-sub
 :time-travel/error
 (fn [db _]
   (time-travel/error db)))

(rf/reg-sub
 :time-travel/last-step
 (fn [db _]
   (get-in db [:time-travel :last-step])))

;; Canvas source subs: the live Map in Editing mode, the viewed
;; Session's snapshot in Time-travel. Everything that joins against
;; the canvas (sidebar selected-entity views included) reads these.

(rf/reg-sub
 :canvas/parts
 (fn [db _]
   (:parts (time-travel/canvas-content db))))

(rf/reg-sub
 :canvas/relationships
 (fn [db _]
   (:relationships (time-travel/canvas-content db))))

(rf/reg-sub
 :canvas/conversation-entries
 (fn [db _]
   (:conversation_entries (time-travel/canvas-content db))))

;; Label and type only, so drag frames (position writes) never re-render
;; the conversation window — the same reason `:canvas/part-options` exists.
(rf/reg-sub
 :canvas/part-names
 :<- [:canvas/parts]
 (fn [parts _]
   (into {} (map (juxt :id #(select-keys % [:label :type]))) parts)))

(rf/reg-sub
 :conversation/scope-part
 :<- [:map/selected-parts]
 (fn [parts _]
   (some-> (windows/scope-part parts) (select-keys [:id :label :type]))))

(rf/reg-sub
 :conversation/chosen-mode
 (fn [db _]
   (get-in db [:ui :conversation-mode] :part)))

(rf/reg-sub
 :canvas/viewed-session
 (fn [db _]
   (time-travel/viewed-session db)))

;; Projection for the sidebar's activation select: id, label and type
;; only, so per-frame position writes (drags, the Time-travel glide)
;; produce an `=` output and never re-render the card.
(rf/reg-sub
 :canvas/part-options
 :<- [:canvas/parts]
 (fn [parts _]
   (mapv #(select-keys % [:id :label :type]) parts)))

(rf/reg-sub
 :canvas/session-badges?
 (fn [db _]
   ;; The badge gate is the History-button gate: with a single Session
   ;; every badge would read "S1" — noise until there is history.
   (time-travel/has-history? (get-in db [:map :sessions]))))

(rf/reg-sub
 :ui/intensity-preview
 (fn [db _]
   (get-in db [:ui :intensity-preview])))

(rf/reg-sub
 :ui/selected-node-ids
 (fn [db _]
   (get-in db [:ui :selected-node-ids] [])))

(rf/reg-sub
 :ui/selected-edge-ids
 (fn [db _]
   (get-in db [:ui :selected-edge-ids] [])))

(rf/reg-sub
 :ui/tool-mode
 ;; The active canvas tool (ADR-0015); Select is the resting default.
 (fn [db _]
   (get-in db [:ui :tool-mode] toolbar/default-tool)))

(rf/reg-sub
 :ui/relationship-type
 (fn [db _]
   (get-in db [:ui :relationship-type] :unknown)))

(rf/reg-sub
 :ui/windows
 ;; Floating windows (ADR-0017): {kind {:open? :pos :size :z :drafts}}.
 (fn [db _]
   (get-in db [:ui :windows] {})))

(rf/reg-sub
 :ui/window
 ;; One kind's window state, or nil if never opened.
 :<- [:ui/windows]
 (fn [windows [_ kind]]
   (get windows kind)))

(rf/reg-sub
 :ui/window-open?
 :<- [:ui/windows]
 (fn [windows [_ kind]]
   (windows/open? windows kind)))

(rf/reg-sub
 :notes/scope-id
 :<- [:map/selected-parts]
 :<- [:map/selected-relationships]
 (fn [[parts relationships] _]
   (:id (second (windows/scope-notes parts relationships)))))

(rf/reg-sub
 :ui/window-draft
 ;; The draft `kind` holds for `entity-id`, or nil (see `windows/draft`).
 :<- [:ui/windows]
 (fn [windows [_ kind entity-id]]
   (windows/draft windows kind entity-id)))

(rf/reg-sub
 :map/selected-parts
 ;; Joins against the CANVAS source, not [:map :parts] — in Time-travel
 ;; the sidebar must show a Part's details as they stood in the viewed
 ;; Session, not today's.
 :<- [:ui/selected-node-ids]
 :<- [:canvas/parts]
 (fn [[selected-ids parts] _]
   (toolbar/selected selected-ids parts)))

(rf/reg-sub
 :map/selected-relationships
 :<- [:ui/selected-edge-ids]
 :<- [:canvas/relationships]
 (fn [[selected-ids relationships] _]
   (toolbar/selected selected-ids relationships)))

(rf/reg-sub
 :auth/user
 (fn [db _]
   (get-in db [:auth :user])))

(rf/reg-sub
 :account/update-error
 (fn [db _]
   (get-in db [:account :update-error])))

(rf/reg-sub
 :account/billing-error
 (fn [db _]
   (get-in db [:account :billing-error])))

(rf/reg-sub
 :account/billing-pending
 (fn [db _]
   (get-in db [:account :billing-pending])))

(rf/reg-sub
 :auth/loading
 (fn [db _]
   (get-in db [:auth :loading])))

(rf/reg-sub
 :auth/logged-in
 :<- [:auth/user]
 (fn [user _]
   (boolean user)))
