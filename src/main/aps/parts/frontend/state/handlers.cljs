(ns aps.parts.frontend.state.handlers
  (:require
   [aps.parts.common.models.part :refer [make-part]]
   [aps.parts.common.models.relationship :refer [make-relationship]]
   [aps.parts.frontend.api.utils :as api-utils]
   [re-frame.core :as rf]))

(rf/reg-event-fx
 :app/init-db
 (fn [_ [_ default-db]]
   {:db                 (-> default-db
                            (assoc :auth {:user    nil
                                          :loading true}))
    :auth/check-auth-fx nil}))

(rf/reg-event-fx
 :app/init-system
 (fn [_ _]
   (if-let [stored-id (api-utils/get-current-system-id)]
     ;; Try to load stored system
     {:storage/get-system {:id stored-id}}
     ;; No stored system, create demo system
     {:dispatch [:app/create-demo-system]})))

(rf/reg-event-fx
 :app/create-demo-system
 (fn [{:keys [db]} _]
   (let [system-id          (str (random-uuid))
         demo-parts         [(make-part {:type       "firefighter"
                                         :label      "Escapist"
                                         :position_x 300
                                         :position_y 130
                                         :notes      "Numbs/distracts when the Exile gets activated. Could be substances, scrolling social media, shopping, etc."
                                         :system_id  system-id})
                             (make-part {:type       "exile"
                                         :label      "Disappointed kid"
                                         :position_x 200
                                         :position_y 320
                                         :notes      "Carries shame/sadness from childhood events. Can get triggered by criticism/rejection."
                                         :system_id  system-id})
                             (make-part {:type       "manager"
                                         :label      "Overachiever"
                                         :position_x 100
                                         :position_y 150
                                         :notes      "Shows up as workaholism, imposter syndrome, etc."
                                         :system_id  system-id})]
         demo-relationships [(make-relationship {:type      "unknown"
                                                 :source_id (:id (nth demo-parts 0))
                                                 :target_id (:id (nth demo-parts 1))
                                                 :system_id system-id})
                             (make-relationship {:type      "protective"
                                                 :source_id (:id (nth demo-parts 2))
                                                 :target_id (:id (nth demo-parts 1))
                                                 :note      "Overachieving behaviour protects exile from getting triggered."
                                                 :system_id system-id})]
         demo-system        {:id            system-id
                             :title         "Demo System"
                             :parts         demo-parts
                             :relationships demo-relationships}]
     ;; Load demo system into app state immediately
     {:db (assoc-in db [:system] demo-system)
      ;; Persist to storage backend and save current system ID
      :fx [[:storage/create-system demo-system]
           [:api-utils/save-current-system-id system-id]]})))

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

(rf/reg-event-fx
 :system/part-create
 (fn [{:keys [db]} [_ attrs]]
   (let [system-id           (get-in db [:system :id])
         attrs-with-position (merge {:position_x 390
                                     :position_y 290}
                                    attrs)
         new-part            (make-part (merge {:system_id system-id}
                                               attrs-with-position))
         part-id             (:id new-part)
         updated-db          (-> db
                                 (update-in [:system :parts] conj new-part)
                                 (assoc-in [:ui :selected-node-ids] [part-id])
                                 (assoc-in [:ui :selected-edge-ids] []))]
     {:db              updated-db
      :queue/add-event {:entity :part
                        :id     part-id
                        :type   "create"
                        :data   (select-keys new-part [:type :label :position_x :position_y])}})))

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
     {:db              updated-db
      :queue/add-event {:entity :part
                        :id     part-id
                        :type   "update"
                        :data   attrs}})))

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
     {:db              updated-db
      :queue/add-event {:entity :part
                        :id     part-id
                        :type   "remove"
                        :data   {}}})))

(rf/reg-event-db
 :system/part-update-position
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
   {:db              db
    :queue/add-event {:entity :part
                      :id     node-id
                      :type   "position"
                      :data   position}}))

(rf/reg-event-fx
 :system/relationship-create
 (fn [{:keys [db]} [_ attrs]]
   (let [system-id        (get-in db [:system :id])
         new-relationship (make-relationship (merge {:system_id system-id}
                                                    attrs))
         rel-id           (:id new-relationship)
         updated-db       (update-in db [:system :relationships] conj new-relationship)]
     {:db              updated-db
      :queue/add-event {:entity :relationship
                        :id     rel-id
                        :type   "create"
                        :data   (select-keys new-relationship [:type :source_id :target_id])}})))

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
     {:db              updated-db
      :queue/add-event {:entity :relationship
                        :id     relationship-id
                        :type   "update"
                        :data   attrs}})))
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
     {:db              updated-db
      :queue/add-event {:entity :relationship
                        :id     relationship-id
                        :type   "remove"
                        :data   {}}})))

(rf/reg-event-fx
 :system/fetch
 (fn [{:keys [db]} [_ system-id]]
   {:db                 (assoc-in db [:systems :loading] true)
    :storage/get-system {:id system-id}}))

(rf/reg-event-fx
 :system/create
 (fn [{:keys [db]} _]
   {:db                    (assoc-in db [:systems :loading] true)
    :storage/create-system {:title "Untitled System"}}))

(rf/reg-event-fx
 :system/fetch-list
 (fn [{:keys [db]} _]
   {:db                  (assoc-in db [:systems :loading] true)
    :storage/get-systems nil}))

(rf/reg-event-db
 :system/fetch-list-success
 (fn [db [_ systems]]
   (-> db
       (assoc-in [:systems :loading] false)
       (assoc-in [:systems :list] systems))))

(rf/reg-event-db
 :system/fetch-list-failure
 (fn [db [_ _error]]
   (-> db
       (assoc-in [:systems :loading] false)
       (assoc-in [:systems :error] "Failed to load systems"))))

(rf/reg-event-fx
 :system/fetch-failure
 (fn [{:keys [db]} [_ _error]]
   {:db       (-> db
                  (assoc-in [:systems :loading] false)
                  (assoc-in [:systems :error] "Failed to load system"))
    :dispatch [:app/create-demo-system]}))

(rf/reg-event-db
 :system/create-failure
 (fn [db [_ _error]]
   (-> db
       (assoc-in [:systems :loading] false)
       (assoc-in [:systems :error] "Failed to create system"))))

(rf/reg-event-db
 :system/fetch-success
 (fn [db [_ system]]
   (-> db
       (assoc-in [:systems :loading] false)
       (assoc-in [:system] system))))

(rf/reg-event-db
 :system/create-success
 (fn [db [_ system]]
   (-> db
       (assoc-in [:systems :loading] false)
       (assoc-in [:system] system)
       (update-in [:systems :list] conj system))))

(rf/reg-event-fx
 :system/load
 (fn [{:keys [db]} [_ system-id]]
   {:db                 (assoc-in db [:systems :loading] true)
    :storage/get-system {:id system-id}}))

(rf/reg-event-db
 :system/update-success
 (fn [db [_ updated-system]]
   (-> db
       (assoc-in [:system] updated-system)
       (update-in [:systems :list]
                  (fn [systems]
                    (mapv (fn [sys]
                            (if (= (:id sys) (:id updated-system))
                              updated-system
                              sys))
                          systems))))))

(rf/reg-event-db
 :system/update-failure
 (fn [db [_ error]]
   (-> db
       (assoc-in [:systems :error] error))))

(rf/reg-event-db
 :auth/set-user
 (fn [db [_ user]]
   (assoc-in db [:auth :user] user)))

(rf/reg-event-db
 :auth/set-loading
 (fn [db [_ loading]]
   (assoc-in db [:auth :loading] loading)))

(rf/reg-event-fx
 :auth/login
 (fn [{:keys [db]} [_ credentials]]
   {:db            db
    :auth/login-fx credentials}))

(rf/reg-event-fx
 :auth/logout
 (fn [{:keys [db]} _]
   {:db             (assoc-in db [:auth :user] nil)
    :auth/logout-fx nil}))

(rf/reg-event-fx
 :auth/check-auth
 (fn [{:keys [db]} _]
   {:db                 db
    :auth/check-auth-fx nil}))

(rf/reg-event-fx
 :auth/register
 (fn [{:keys [db]} [_ params]]
   {:db               db
    :auth/register-fx params}))
