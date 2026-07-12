(ns aps.parts.frontend.state.handlers
  (:require
   [aps.parts.common.change-event :as ce]
   [aps.parts.common.demo :as demo]
   [aps.parts.common.models.map :as map-model]
   [aps.parts.common.models.part :refer [make-part]]
   [aps.parts.common.models.relationship :as relationship :refer [make-relationship]]
   [aps.parts.common.observe :as o]
   [aps.parts.frontend.api.utils :as api-utils]
   [aps.parts.frontend.state.map-updates :as map-updates]
   [aps.parts.frontend.state.sessions :as sessions]
   [aps.parts.frontend.state.time-travel :as time-travel]
   [aps.parts.frontend.state.toolbar :as toolbar]
   [re-frame.core :as rf]))

(def ^:private require-editable
  "Backstop for the read-only canvas (ADR-0014: editing requires an
   active Session; demo Maps exempt): drops a mutating :map/* event
   before its handler runs. The UI already disables every edit path —
   this guarantees that anything it misses still can't touch the
   optimistic state or the change-event queue."
  (rf/->interceptor
   :id :require-editable
   :before (fn [context]
             (if (sessions/editable? (get-in context [:coeffects :db]))
               context
               (do (o/info "handlers.require-editable"
                           "dropped mutation on read-only canvas"
                           (get-in context [:coeffects :event 0]))
                   (assoc context :queue []))))))

(rf/reg-event-fx
 :app/init-db
 (fn [_ [_ default-db]]
   {:db                 (-> default-db
                            (assoc :auth {:user    nil
                                          :loading true}))
    :auth/check-auth-fx nil}))

(rf/reg-event-fx
 :app/init-demo-map
 (fn [_ _]
   ;; Playground boot path only: restore the last demo Map from
   ;; localStorage, or seed a fresh one. The authenticated app loads its
   ;; Map through the client-side router instead.
   (if-let [stored-id (api-utils/get-current-map-id)]
     {:storage/get-map {:id stored-id}}
     {:dispatch [:app/create-demo-map]})))

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
   (update db :ui toolbar/select-tool mode)))

(rf/reg-event-db
 :ui/tool-spring-down
 (fn [db [_ tool]]
   (update db :ui toolbar/spring-hold tool)))

(rf/reg-event-db
 :ui/tool-spring-up
 (fn [db _]
   (update db :ui toolbar/spring-release)))

(rf/reg-event-db
 :ui/relationship-type-set
 (fn [db [_ type]]
   (assoc-in db [:ui :relationship-type] type)))

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
 [require-editable]
 (fn [{:keys [db]} [_ attrs]]
   (let [map-id   (get-in db [:map :id])
         new-part (make-part (merge {:map_id     map-id
                                     :position_x 390
                                     :position_y 290}
                                    attrs))]
     ;; A first appearance makes the active Session undeletable, so its
     ;; undo window closes here (and in relationship-create) — edits and
     ;; moves close nothing, mirroring the server's `require-empty!`.
     {:db              (-> db
                           (add-part new-part)
                           sessions/close-undo-window)
      :queue/add-event (ce/part-create
                        (:id new-part)
                        (select-keys new-part [:type :label :position_x :position_y]))})))

(rf/reg-event-fx
 :map/part-update
 [require-editable]
 (fn [{:keys [db]} [_ part-id attrs]]
   {:db              (merge-part db part-id attrs)
    :queue/add-event (ce/part-update part-id attrs)}))

(rf/reg-event-fx
 :map/part-remove
 [require-editable]
 (fn [{:keys [db]} [_ part-id]]
   {:db              (remove-part db part-id)
    :queue/add-event (ce/part-remove part-id)}))

(rf/reg-event-db
 :map/part-update-position
 [require-editable]
 (fn [db [_ node-id position]]
   (merge-part db node-id {:position_x (int (:x position))
                           :position_y (int (:y position))})))

(rf/reg-event-fx
 :map/part-finish-position-change
 [require-editable]
 (fn [_ [_ node-id position]]
   {:queue/add-event (ce/part-moved node-id (:x position) (:y position))}))

(rf/reg-event-db
 :map/part-update-size
 [require-editable]
 (fn [db [_ node-id {:keys [width height]}]]
   (merge-part db node-id {:width  (int width)
                           :height (int height)})))

(rf/reg-event-fx
 :map/part-finish-size-change
 [require-editable]
 ;; One change-event per completed resize. It carries the position too:
 ;; resizing from a top/left corner moves the Part, but those position
 ;; frames never commit on their own (the adapter suppresses :part-moved
 ;; during a resize), so the final geometry travels as one update.
 (fn [{:keys [db]} [_ node-id {:keys [width height]}]]
   (let [db'  (merge-part db node-id {:width  (int width)
                                      :height (int height)})
         part (some #(when (= node-id (:id %)) %)
                    (get-in db' [:map :parts]))]
     {:db              db'
      :queue/add-event (ce/part-update node-id
                                       {:width      (int width)
                                        :height     (int height)
                                        :position_x (:position_x part)
                                        :position_y (:position_y part)})})))

(rf/reg-event-fx
 :map/relationship-create
 [require-editable]
 (fn [{:keys [db]} [_ attrs]]
   (let [relationships    (get-in db [:map :relationships])
         new-relationship (make-relationship
                           (toolbar/relationship-create-attrs db attrs))]
     (if (relationship/can-connect? relationships
                                    (:source_id new-relationship)
                                    (:target_id new-relationship))
       ;; Only the success branch closes the undo window — a blocked
       ;; duplicate creates nothing, so the Session stays undoable.
       {:db              (-> db
                             (add-relationship new-relationship)
                             sessions/close-undo-window)
        :queue/add-event (ce/relationship-create
                          (:id new-relationship)
                          (select-keys new-relationship [:type :source_id :target_id]))}
       (do (o/info "handlers.relationship-create"
                   "blocked duplicate (one relationship per ordered pair)"
                   (select-keys new-relationship [:source_id :target_id]))
           {})))))

(rf/reg-event-fx
 :map/relationship-update
 [require-editable]
 (fn [{:keys [db]} [_ relationship-id attrs]]
   {:db              (merge-relationship db relationship-id attrs)
    :queue/add-event (ce/relationship-update relationship-id attrs)}))

(rf/reg-event-fx
 :map/relationship-remove
 [require-editable]
 (fn [{:keys [db]} [_ relationship-id]]
   {:db              (remove-relationship db relationship-id)
    :queue/add-event (ce/relationship-remove relationship-id)}))

(rf/reg-event-fx
 :map/fetch
 (fn [_ [_ map-id]]
   ;; No `[:maps :loading]` toggle — that's the *list's* flag. The map view
   ;; gates on whether the loaded map matches the requested id (`map-route`),
   ;; so it shows a spinner for the requested map rather than the previous one.
   {:storage/get-map {:id map-id}}))

(rf/reg-event-fx
 :map/create
 (fn [{:keys [db]} _]
   {:db                 (assoc-in db [:maps :loading] true)
    :storage/create-map {:title map-model/default-title}}))

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
   ;; The session expired or was never there. Drop the loaded user so the
   ;; SPA root's auth gate shows the login screen; the router keeps
   ;; matching the deep-linked URL, so login lands the user right back here.
   {:db (-> db
            (assoc-in [:maps :loading] false)
            (assoc-in [:auth :user] nil)
            (assoc-in [:maps :error] "Please sign in to view this map"))}))

(rf/reg-event-fx
 :map/fetch-forbidden
 (fn [{:keys [db]} _]
   {:db              (-> db
                         (assoc-in [:maps :loading] false)
                         (assoc-in [:maps :error] "You don't have access to this map"))
    :router/navigate {:name :aps.parts.frontend.router/maps-list}}))

(rf/reg-event-fx
 :map/fetch-not-found
 (fn [{:keys [db]} _]
   {:db              (-> db
                         (assoc-in [:maps :loading] false)
                         (assoc-in [:maps :error] "Map not found"))
    :router/navigate {:name :aps.parts.frontend.router/maps-list}}))

(rf/reg-event-fx
 :map/fetch-failure
 (fn [{:keys [db]} [_ _error]]
   (let [demo-mode? (:demo-mode db)]
     (cond-> {:db (-> db
                      (assoc-in [:maps :loading] false)
                      (assoc-in [:maps :error] "Failed to load map"))}
       ;; Only create demo map when in demo mode (localStorage backend).
       ;; In the app, fall back to the Maps list.
       demo-mode?       (assoc :dispatch [:app/create-demo-map])
       (not demo-mode?) (assoc :router/navigate
                               {:name :aps.parts.frontend.router/maps-list})))))

(rf/reg-event-db
 :map/create-failure
 (fn [db [_ _error]]
   (-> db
       (assoc-in [:maps :loading] false)
       (assoc-in [:maps :error] "Failed to create map"))))

(rf/reg-event-fx
 :map/fetch-success
 (fn [{:keys [db]} [_ the-map]]
   ;; Sessions load right behind the Map; until they arrive the canvas
   ;; reads as read-only (`sessions/editable?`). A loaded Map always
   ;; lands in Editing mode — any Time-travel state belonged to the
   ;; previous Map.
   (cond-> {:db (-> db (dissoc :time-travel) (assoc :map the-map))}
     (not (:demo-mode db))
     (assoc :sessions/fetch-fx {:map-id (:id the-map)}))))

;; -- Sessions (ADR-0014) ----------------------------------------------------
;; Pure transitions live in `state/sessions` (kept re-frame-free so the
;; cljs test suite can reach them); these register them and pair each
;; with its HTTP effect. Trigger edits are optimistic; delete waits for
;; the server's verdict — only it can judge "empty", because membership
;; is derived, not stored.

(rf/reg-event-db
 :sessions/fetch-success
 (fn [db [_ map-id the-sessions]]
   (sessions/apply-sessions db map-id the-sessions)))

(rf/reg-event-db
 :sessions/fetch-failure
 (fn [db [_ _map-id]]
   ;; The canvas stays read-only (no Sessions loaded) — safe by default.
   (sessions/set-error db "Could not load this map's sessions")))

(rf/reg-event-fx
 :session/start
 (fn [{:keys [db]} _]
   {:session/create-fx {:map-id (get-in db [:map :id])}}))

(rf/reg-event-db
 :session/start-success
 (fn [db [_ map-id session]]
   (if (= map-id (get-in db [:map :id]))
     (-> db
         (sessions/add-session session)
         (sessions/open-undo-window (:id session)))
     db)))

(rf/reg-event-db
 :session/start-failure
 (fn [db [_ message]]
   (sessions/set-error db message)))

(rf/reg-event-fx
 :session/set-trigger
 (fn [{:keys [db]} [_ session-id trigger]]
   {:db                        (sessions/set-trigger db session-id trigger)
    :session/update-trigger-fx {:map-id     (get-in db [:map :id])
                                :session-id session-id
                                :trigger    trigger}}))

(rf/reg-event-db
 :session/trigger-saved
 (fn [db _]
   (sessions/mark-trigger-saved db)))

(rf/reg-event-fx
 :session/trigger-save-failure
 (fn [{:keys [db]} [_ map-id message]]
   ;; Refetch to roll the optimistic trigger back to the stored truth.
   {:db                (sessions/set-error db message)
    :sessions/fetch-fx {:map-id map-id}}))

(rf/reg-event-fx
 :session/delete
 (fn [{:keys [db]} [_ session-id]]
   {:session/delete-fx {:map-id     (get-in db [:map :id])
                        :session-id session-id}}))

(rf/reg-event-db
 :session/delete-success
 (fn [db [_ session-id]]
   (sessions/remove-session db session-id)))

(rf/reg-event-db
 :session/delete-failure
 (fn [db [_ message]]
   (sessions/set-error db message)))

;; -- Time-travel mode (TASK-073.03) -----------------------------------------
;; Pure transitions live in `state/time-travel`; these pair them with the
;; snapshot fetch. Only a step onto an uncached PAST Session fetches —
;; the latest is the live Map, and revisits hit the snapshot cache (the
;; past is immutable).

(rf/reg-event-db
 :time-travel/enter
 (fn [db _]
   (time-travel/enter db)))

(rf/reg-event-db
 :time-travel/exit
 (fn [db _]
   (time-travel/exit db)))

(rf/reg-event-fx
 :time-travel/step
 (fn [{:keys [db]} [_ direction]]
   (let [db' (time-travel/step db direction)]
     (cond-> {:db db'}
       (time-travel/snapshot-needed? db')
       (assoc :time-travel/fetch-fx
              {:map-id     (get-in db' [:map :id])
               :session-id (get-in db' [:time-travel :session-id])})))))

(rf/reg-event-db
 :time-travel/snapshot-success
 (fn [db [_ session-id the-map]]
   (time-travel/store-snapshot db session-id the-map)))

(rf/reg-event-db
 :time-travel/fetch-failure
 (fn [db [_ message]]
   (time-travel/set-error db message)))

(rf/reg-event-fx
 :map/create-success
 (fn [{:keys [db]} [_ the-map]]
   (cond-> {:db (-> db
                    (assoc-in [:maps :loading] false)
                    (assoc-in [:map] the-map)
                    (update-in [:maps :list] conj the-map))}
     ;; In the app (not the playground demo) a freshly created Map opens
     ;; its canvas via the client-side router. The Sessions fetch must
     ;; ride along here: the route effect skips :map/fetch for an
     ;; already-loaded Map, and without the (empty) Session list the
     ;; chip's "Start a session" call-to-action never renders — a new
     ;; Map would open read-only with no way out.
     (not (:demo-mode db))
     (assoc :router/navigate {:name        :aps.parts.frontend.router/map
                              :path-params {:id (:id the-map)}}
            :sessions/fetch-fx {:map-id (:id the-map)}))))

;; -- map metadata: rename -------------------------------------------------
;; Pure transitions live in `state/map-updates` (kept re-frame-free so the
;; cljs test suite can reach them); here we just register them. A Map's
;; title is bitemporal metadata (ADR-0002) — a rename goes through
;; PUT /maps/:id, separate from the change-event batch.

(rf/reg-event-fx
 :map/rename
 (fn [{:keys [db]} [_ new-title]]
   (map-updates/rename-map db new-title)))

(rf/reg-event-db
 :map/update-success
 (fn [db [_ updated-map]]
   (map-updates/apply-map-update db updated-map)))

(rf/reg-event-db
 :map/update-failure
 (fn [db [_ error]]
   (map-updates/revert-map-update db error)))

(rf/reg-event-db
 :map/batch-failed
 (fn [db _]
   (map-updates/mark-batch-failed db)))

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
