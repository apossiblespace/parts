(ns aps.parts.frontend.state.handlers
  (:require
   [aps.parts.common.change-event :as ce]
   [aps.parts.common.demo :as demo]
   [aps.parts.common.models.part :refer [make-part]]
   [aps.parts.common.models.relationship :as relationship :refer [make-relationship]]
   [aps.parts.common.observe :as o]
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
 :app/init-map
 (fn [{:keys [db]} _]
   ;; Prioritize URL path over localStorage for map ID
   (if-let [url-id (api-utils/get-map-id-from-url)]
     ;; Store as pending — wait for auth check to complete before fetching
     {:db (assoc db :pending-map-id url-id)}
     ;; Fall back to localStorage or create demo
     (if-let [stored-id (api-utils/get-current-map-id)]
       {:storage/get-map {:id stored-id}}
       {:dispatch [:app/create-demo-map]}))))

(rf/reg-event-fx
 :app/create-demo-map
 (fn [{:keys [db]} _]
   (let [map-id             (str (random-uuid))
         demo-parts         (mapv #(make-part %) (demo/demo-part-attrs map-id))
         demo-relationships (mapv #(make-relationship %) (demo/demo-relationship-attrs demo-parts))
         demo-map           {:id            map-id
                             :title         "Demo Map"
                             :parts         demo-parts
                             :relationships demo-relationships}]
     ;; Load demo map into app state immediately
     {:db (assoc-in db [:map] demo-map)
      ;; Persist to storage backend and save current map ID
      :fx [[:storage/create-map demo-map]
           [:api-utils/save-current-map-id map-id]]})))

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

(rf/reg-event-db
 :ui/tool-mode-set
 (fn [db [_ mode]]
   (assoc-in db [:ui :tool-mode] mode)))

;; -- optimistic mutation helpers ------------------------------------------
;; Each :map/* handler below does the same two-beat: mutate :db optimistically,
;; then enqueue a change-event. These name the mutation half; the change-event
;; half is named by the constructors in `ce/*`.

(defn- add-part
  "Append `new-part` to the Map and select it (clearing edge selection)."
  [db new-part]
  (-> db
      (update-in [:map :parts] conj new-part)
      (assoc-in [:ui :selected-node-ids] [(:id new-part)])
      (assoc-in [:ui :selected-edge-ids] [])))

(defn- merge-part
  "Merge `attrs` into the part with `part-id`."
  [db part-id attrs]
  (update-in db [:map :parts]
             (fn [parts]
               (mapv (fn [part]
                       (if (= (:id part) part-id)
                         (merge part attrs)
                         part))
                     parts))))

(defn- remove-part
  "Drop the part with `part-id` and clear it from selection."
  [db part-id]
  (-> db
      (update-in [:map :parts]
                 (fn [parts] (filterv #(not= (:id %) part-id) parts)))
      (update-in [:ui :selected-node-ids]
                 (fn [ids] (filterv #(not= % part-id) (or ids []))))))

(defn- add-relationship
  "Append `new-relationship` to the Map."
  [db new-relationship]
  (update-in db [:map :relationships] conj new-relationship))

(defn- merge-relationship
  "Merge `attrs` into the relationship with `relationship-id`."
  [db relationship-id attrs]
  (update-in db [:map :relationships]
             (fn [rels]
               (mapv (fn [rel]
                       (if (= (:id rel) relationship-id)
                         (merge rel attrs)
                         rel))
                     rels))))

(defn- remove-relationship
  "Drop the relationship with `relationship-id` and clear it from selection."
  [db relationship-id]
  (-> db
      (update-in [:map :relationships]
                 (fn [rels] (filterv #(not= (:id %) relationship-id) rels)))
      (update-in [:ui :selected-edge-ids]
                 (fn [ids] (filterv #(not= % relationship-id) (or ids []))))))

(rf/reg-event-fx
 :map/part-create
 (fn [{:keys [db]} [_ attrs]]
   (let [map-id   (get-in db [:map :id])
         new-part (make-part (merge {:map_id     map-id
                                     :position_x 390
                                     :position_y 290}
                                    attrs))]
     {:db              (add-part db new-part)
      :queue/add-event (ce/part-create
                        (:id new-part)
                        (select-keys new-part [:type :label :position_x :position_y]))})))

(rf/reg-event-fx
 :map/part-update
 (fn [{:keys [db]} [_ part-id attrs]]
   {:db              (merge-part db part-id attrs)
    :queue/add-event (ce/part-update part-id attrs)}))

(rf/reg-event-fx
 :map/part-remove
 (fn [{:keys [db]} [_ part-id]]
   {:db              (remove-part db part-id)
    :queue/add-event (ce/part-remove part-id)}))

(rf/reg-event-db
 :map/part-update-position
 (fn [db [_ node-id position]]
   (merge-part db node-id {:position_x (int (:x position))
                           :position_y (int (:y position))})))

(rf/reg-event-fx
 :map/part-finish-position-change
 (fn [_ [_ node-id position]]
   {:queue/add-event (ce/part-moved node-id (:x position) (:y position))}))

(rf/reg-event-fx
 :map/relationship-create
 (fn [{:keys [db]} [_ attrs]]
   (let [map-id           (get-in db [:map :id])
         relationships    (get-in db [:map :relationships])
         new-relationship (make-relationship (merge {:map_id map-id} attrs))]
     (if (relationship/can-connect? relationships
                                    (:source_id new-relationship)
                                    (:target_id new-relationship))
       {:db              (add-relationship db new-relationship)
        :queue/add-event (ce/relationship-create
                          (:id new-relationship)
                          (select-keys new-relationship [:type :source_id :target_id]))}
       (do (o/info "handlers.relationship-create"
                   "blocked duplicate (one relationship per ordered pair)"
                   (select-keys new-relationship [:source_id :target_id]))
           {})))))

(rf/reg-event-fx
 :map/relationship-update
 (fn [{:keys [db]} [_ relationship-id attrs]]
   {:db              (merge-relationship db relationship-id attrs)
    :queue/add-event (ce/relationship-update relationship-id attrs)}))

(rf/reg-event-fx
 :map/relationship-remove
 (fn [{:keys [db]} [_ relationship-id]]
   {:db              (remove-relationship db relationship-id)
    :queue/add-event (ce/relationship-remove relationship-id)}))

(rf/reg-event-fx
 :map/fetch
 (fn [{:keys [db]} [_ map-id]]
   {:db              (assoc-in db [:maps :loading] true)
    :storage/get-map {:id map-id}}))

(rf/reg-event-fx
 :map/create
 (fn [{:keys [db]} _]
   {:db                 (assoc-in db [:maps :loading] true)
    :storage/create-map {:title "Untitled Map"}}))

(rf/reg-event-fx
 :map/fetch-list
 (fn [{:keys [db]} _]
   {:db               (assoc-in db [:maps :loading] true)
    :storage/get-maps nil}))

(rf/reg-event-db
 :map/fetch-list-success
 (fn [db [_ maps]]
   (-> db
       (assoc-in [:maps :loading] false)
       (assoc-in [:maps :list] maps))))

(rf/reg-event-db
 :map/fetch-list-failure
 (fn [db [_ _error]]
   (-> db
       (assoc-in [:maps :loading] false)
       (assoc-in [:maps :error] "Failed to load maps"))))

(rf/reg-event-fx
 :map/fetch-unauthorized
 (fn [{:keys [db]} _]
   (if-let [url-id (api-utils/get-map-id-from-url)]
     {:db (-> db
              (assoc-in [:maps :loading] false)
              (assoc :pending-map-id url-id))}
     {:db          (-> db
                       (assoc-in [:maps :loading] false)
                       (assoc-in [:maps :error] "Please sign in to view this map"))
      :navigate-to "/"})))

(rf/reg-event-fx
 :map/fetch-forbidden
 (fn [{:keys [db]} _]
   {:db          (-> db
                     (assoc-in [:maps :loading] false)
                     (assoc-in [:maps :error] "You don't have access to this map"))
    :navigate-to "/"}))

(rf/reg-event-fx
 :map/fetch-not-found
 (fn [{:keys [db]} _]
   {:db          (-> db
                     (assoc-in [:maps :loading] false)
                     (assoc-in [:maps :error] "Map not found"))
    :navigate-to "/"}))

(rf/reg-event-fx
 :map/fetch-failure
 (fn [{:keys [db]} [_ _error]]
   (let [demo-mode? (:demo-mode db)]
     (cond-> {:db (-> db
                      (assoc-in [:maps :loading] false)
                      (assoc-in [:maps :error] "Failed to load map"))}
       ;; Only create demo map when in demo mode (localStorage backend)
       ;; When using HTTP backend, redirect to home instead
       demo-mode? (assoc :dispatch [:app/create-demo-map])
       (not demo-mode?) (assoc :navigate-to "/")))))

(rf/reg-event-db
 :map/create-failure
 (fn [db [_ _error]]
   (-> db
       (assoc-in [:maps :loading] false)
       (assoc-in [:maps :error] "Failed to create map"))))

(rf/reg-event-db
 :map/fetch-success
 (fn [db [_ the-map]]
   (-> db
       (assoc-in [:maps :loading] false)
       (assoc-in [:map] the-map))))

(rf/reg-event-db
 :map/create-success
 (fn [db [_ the-map]]
   (-> db
       (assoc-in [:maps :loading] false)
       (assoc-in [:map] the-map)
       (update-in [:maps :list] conj the-map))))

(rf/reg-event-fx
 :map/load
 (fn [{:keys [db]} [_ map-id]]
   {:db              (assoc-in db [:maps :loading] true)
    :storage/get-map {:id map-id}}))

(rf/reg-event-db
 :map/update-success
 (fn [db [_ updated-map]]
   (-> db
       (assoc-in [:map] updated-map)
       (update-in [:maps :list]
                  (fn [maps]
                    (mapv (fn [m]
                            (if (= (:id m) (:id updated-map))
                              updated-map
                              m))
                          maps))))))

(rf/reg-event-db
 :map/update-failure
 (fn [db [_ error]]
   (-> db
       (assoc-in [:maps :error] error))))

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
 :auth/check-complete
 (fn [{:keys [db]} _]
   (when-let [pending-id (:pending-map-id db)]
     (when (get-in db [:auth :user])
       {:db              (dissoc db :pending-map-id)
        :storage/get-map {:id pending-id}}))))

(rf/reg-event-fx
 :auth/register
 (fn [{:keys [db]} [_ params]]
   {:db               db
    :auth/register-fx params}))
