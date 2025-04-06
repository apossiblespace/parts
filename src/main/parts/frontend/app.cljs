(ns parts.frontend.app
  (:require
   ["htmx.org" :default htmx]
   [parts.frontend.components.system :refer [system]]
   [parts.frontend.context :refer [auth-provider]]
   [parts.frontend.api.queue :as queue]
   [parts.common.models.part :refer [make-part]]
   [parts.common.models.relationship :refer [make-relationship]]
   [uix.core :refer [$ defui]]
   [uix.dom]
   [uix.re-frame :as uix.rf]
   [re-frame.core :as rf]))

(def system-id (str (random-uuid)))

(def parts
  [(make-part {:type "manager"
               :label "Manager"
               :position_x 300
               :position_y 130
               :system_id system-id})
   (make-part {:type "exile"
               :label "Exile"
               :position_x 200
               :position_y 300
               :system_id system-id})
   (make-part {:type "firefighter"
               :label "Firefighter"
               :position_x 100
               :position_y 130
               :system_id system-id})])

(def relationships
  [(make-relationship {:type "unknown"
                       :source_id (:id (nth parts 0))
                       :target_id (:id (nth parts 1))
                       :system_id system-id})
   (make-relationship {:type "protective"
                       :source_id (:id (nth parts 2))
                       :target_id (:id (nth parts 1))
                       :system_id system-id})])

(def system-data
  {:system
   {:id system-id
    :parts parts
    :relationships relationships}})

(rf/reg-sub
 :system/parts
 (fn [db _]
   (get-in db [:system :parts])))

(rf/reg-sub
 :system/relationships
 (fn [db _]
   (get-in db [:system :relationships])))

(rf/reg-event-fx
 :app/init-db []
 (fn [{:store/keys [system]} [_ default-db]]
   {:db (update default-db :system into system)}))

(rf/reg-event-db
 :system/update-part-position
 (fn [db [_ node-id position]]
   (update-in db [:system :parts]
              (fn [parts]
                (mapv (fn [part]
                        (if (= (:id part) node-id)
                          (-> part
                              (assoc :position_x (int (:x position)))
                              (assoc :position_y (int (:y position))))
                          part))
                      parts)))))

(rf/reg-event-fx
 :system/part-finish-position-change
 (fn [{:keys [db]} [_ node-id position]]
   {:db db
    :queue/add-event {:entity :part
                      :id node-id
                      :type "position"
                      :data position}}))

(rf/reg-fx
 :queue/add-event
 (fn [event]
   (queue/add-events!
    (:entity event)
    [event])))

(rf/reg-event-db
 :selection/set
 (fn [db [_ selection]]
   (-> db
       (assoc-in [:ui :selected-node-ids] (mapv :id (:nodes selection)))
       (assoc-in [:ui :selected-edge-ids] (mapv :id (:edges selection))))))

(rf/reg-event-db
 :selection/toggle-node
 (fn [db [_ node-id selected?]]
   (update-in db [:ui :selected-node-ids]
              (fn [ids]
                (let [current-ids (or ids [])]
                  (if selected?
                    (if (some #{node-id} current-ids)
                      current-ids
                      (conj current-ids node-id))
                    (filterv #(not= node-id %) current-ids)))))))

(rf/reg-event-db
 :selection/toggle-edge
 (fn [db [_ edge-id selected?]]
   (update-in db [:ui :selected-edge-ids]
              (fn [ids]
                (let [current-ids (or ids [])]
                  (if selected?
                    (if (some #{edge-id} current-ids)
                      current-ids
                      (conj current-ids edge-id))
                    (filterv #(not= edge-id %) current-ids)))))))

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

(rf/reg-event-fx
 :system/part-create
 (fn [{:keys [db]} [_ attrs]]
   (let [system-id (get-in db [:system :id])
         attrs-with-position (merge {:position_x 390
                                     :position_y 290}
                                    attrs)
         new-part (make-part (merge {:system_id system-id}
                                    attrs-with-position))
         part-id (:id new-part)
         updated-db (-> db
                        (update-in [:system :parts] conj new-part)
                        (assoc-in [:ui :selected-node-ids] [part-id])
                        (assoc-in [:ui :selected-edge-ids] []))]
     {:db updated-db
      :queue/add-event {:entity :part
                        :id part-id
                        :type "create"
                        :data (select-keys new-part [:type :label :position_x :position_y])}})))

(rf/reg-event-fx
 :system/part-update
 (fn [{:keys [db]} [_ part-id attrs]]
   (let [updated-db (update-in db [:system :parts]
                               (fn [parts]
                                 (mapv (fn [part]
                                         (if (= (:id part) part-id)
                                           (merge part attrs)
                                           part))
                                       parts)))]
     {:db updated-db
      :queue/add-event {:entity :part
                        :id part-id
                        :type "update"
                        :data attrs}})))

(rf/reg-event-fx
 :system/part-remove
 (fn [{:keys [db]} [_ part-id]]
   (let [updated-db (-> db
                        (update-in [:system :parts]
                                   (fn [parts]
                                     (filterv #(not= (:id %) part-id) parts)))
                        (update-in [:ui :selected-node-ids]
                                   (fn [ids]
                                     (filterv #(not= % part-id) (or ids [])))))]
     {:db updated-db
      :queue/add-event {:entity :part
                        :id part-id
                        :type "remove"
                        :data {}}})))

(rf/reg-event-fx
 :system/relationship-create
 (fn [{:keys [db]} [_ attrs]]
   (let [system-id (get-in db [:system :id])
         new-relationship (make-relationship (merge {:system_id system-id}
                                                    attrs))
         rel-id (:id new-relationship)
         updated-db (update-in db [:system :relationships] conj new-relationship)]
     {:db updated-db
      :queue/add-event {:entity :relationship
                        :id rel-id
                        :type "create"
                        :data (select-keys new-relationship [:type :source_id :target_id])}})))

(rf/reg-event-fx
 :system/relationship-update
 (fn [{:keys [db]} [_ relationship-id attrs]]
   (let [updated-db (update-in db [:system :relationships]
                               (fn [relationships]
                                 (mapv (fn [relationship]
                                         (if (= (:id relationship) relationship-id)
                                           (merge relationship attrs)
                                           relationship))
                                       relationships)))]
     {:db updated-db
      :queue/add-event {:entity :relationship
                        :id relationship-id
                        :type "update"
                        :data attrs}})))
(rf/reg-event-fx
 :system/relationship-remove
 (fn [{:keys [db]} [_ relationship-id]]
   (let [updated-db (-> db
                        (update-in [:system :relationships]
                                   (fn [relationships]
                                     (filterv #(not= (:id %) relationship-id) relationships)))
                        (update-in [:ui :selected-edge-ids]
                                   (fn [ids]
                                     (filterv #(not= % relationship-id) (or ids [])))))]
     {:db updated-db
      :queue/add-event {:entity :relationship
                        :id relationship-id
                        :type "remove"
                        :data {}}})))

(defui app []
  ($ auth-provider {}
     ($ system)))

(defonce root
  (when-let [root-element (js/document.getElementById "root")]
    (uix.dom/create-root root-element)))

(defn render-app
  "Render the app if root element exists"
  []
  (when root
    (js/console.log "dispatching init-db")
    (rf/dispatch-sync [:app/init-db system-data])
    (uix.dom/render-root ($ app) root)))

(defn ^:export init []
  (.on htmx "htmx:load"
       (fn [_]
         (render-app)
         (let [version (.-version htmx)]
           (js/console.log "HTMX loaded! Version:" version)))))

(defn ^:dev/after-load reload! []
  (js/console.log "Reloading app...")
  (uix.dom/render-root ($ app) root))
