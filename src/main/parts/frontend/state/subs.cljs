(ns parts.frontend.state.subs
  (:require
   [re-frame.core :as rf]))

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
