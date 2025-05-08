(ns parts.frontend.state.subs
  (:require
   [re-frame.core :as rf]))

(rf/reg-sub
 :demo?
 (fn [db _]
   (boolean (:demo-mode db))))

(rf/reg-sub
 :minimal-demo?
 (fn [db _]
   (= (:demo-mode db) :minimal)))

(rf/reg-sub
 :systems/list
 (fn [db _]
   (get-in db [:systems :list])))

(rf/reg-sub
 :systems/loading
 (fn [db _]
   (get-in db [:systems :loading])))

(rf/reg-sub
 :system/id
 (fn [db _]
   (get-in db [:system :id])))

(rf/reg-sub
 :system/parts
 (fn [db _]
   (get-in db [:system :parts])))

(rf/reg-sub
 :system/relationships
 (fn [db _]
   (get-in db [:system :relationships])))

(rf/reg-sub
 :ui/selected-node-ids
 (fn [db _]
   (get-in db [:ui :selected-node-ids] [])))

(rf/reg-sub
 :ui/selected-edge-ids
 (fn [db _]
   (get-in db [:ui :selected-edge-ids] [])))

(rf/reg-sub
 :system/selected-parts
 :<- [:ui/selected-node-ids]
 :<- [:system/parts]
 (fn [[selected-ids parts] _]
   (filterv #(contains? (set selected-ids) (:id %)) parts)))

(rf/reg-sub
 :system/selected-relationships
 :<- [:ui/selected-edge-ids]
 :<- [:system/relationships]
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
