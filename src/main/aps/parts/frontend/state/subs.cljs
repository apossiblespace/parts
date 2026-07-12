(ns aps.parts.frontend.state.subs
  (:require
   [aps.parts.frontend.state.sessions :as sessions]
   [aps.parts.frontend.state.time-travel :as time-travel]
   [aps.parts.frontend.state.toolbar :as toolbar]
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
 :canvas/viewed-ordinal
 (fn [db _]
   (time-travel/viewed-ordinal db)))

(rf/reg-sub
 :canvas/session-badges?
 (fn [db _]
   ;; The badge gate is the History-button gate: with a single Session
   ;; every badge would read "S1" — noise until there is history.
   (time-travel/has-history? (get-in db [:map :sessions]))))

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
 :map/selected-parts
 ;; Joins against the CANVAS source, not [:map :parts] — in Time-travel
 ;; the sidebar must show a Part's details as they stood in the viewed
 ;; Session, not today's.
 :<- [:ui/selected-node-ids]
 :<- [:canvas/parts]
 (fn [[selected-ids parts] _]
   (filterv #(contains? (set selected-ids) (:id %)) parts)))

(rf/reg-sub
 :map/selected-relationships
 :<- [:ui/selected-edge-ids]
 :<- [:canvas/relationships]
 (fn [[selected-ids relationships] _]
   (filterv #(contains? (set selected-ids) (:id %)) relationships)))

(rf/reg-sub
 :auth/user
 (fn [db _]
   (get-in db [:auth :user])))

(rf/reg-sub
 :auth/loading
 (fn [db _]
   (get-in db [:auth :loading])))

(rf/reg-sub
 :auth/logged-in
 :<- [:auth/user]
 (fn [user _]
   (boolean user)))
