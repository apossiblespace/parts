(ns aps.parts.frontend.state.subs
  (:require
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
 :map/parts
 (fn [db _]
   (get-in db [:map :parts])))

(rf/reg-sub
 :map/relationships
 (fn [db _]
   (get-in db [:map :relationships])))

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
 (fn [db _]
   (get-in db [:ui :tool-mode] :move)))

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

(rf/reg-sub
 :map/pending-id
 (fn [db _]
   (:pending-map-id db)))
