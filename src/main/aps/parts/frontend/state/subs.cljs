(ns aps.parts.frontend.state.subs
  (:require
   [aps.parts.frontend.state.sessions :as sessions]
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

(rf/reg-sub
 :session/trigger-saved?
 (fn [db _]
   (sessions/trigger-saved? db)))

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
 :<- [:ui/selected-node-ids]
 :<- [:map/parts]
 (fn [[selected-ids parts] _]
   (filterv #(contains? (set selected-ids) (:id %)) parts)))

(rf/reg-sub
 :map/selected-relationships
 :<- [:ui/selected-edge-ids]
 :<- [:map/relationships]
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
