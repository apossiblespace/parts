(ns aps.parts.api.maps
  (:require
   [aps.parts.api.maps-events :as events]
   [aps.parts.db :as db]
   [aps.parts.entity.map :as parts-map]
   [com.brunobonacci.mulog :as mulog]
   [ring.util.response :as response]))

(defn list-maps
  "List all maps for the authenticated user"
  [{:keys [identity] :as _request}]
  (let [user-id (:sub identity)
        maps    (parts-map/index user-id)]
    (-> (response/response maps)
        (response/status 200))))

(defn create-map
  "Create a new map"
  [{:keys [identity body-params] :as _request}]
  (let [user-id  (:sub identity)
        map-data (assoc body-params :owner_id user-id)
        created  (parts-map/create! map-data user-id)]
    (-> (response/response created)
        (response/status 201))))

(defn get-map
  "Get a map by ID. Access is gated by `wrap-map-access` middleware."
  [{:keys [parameters] :as _request}]
  (let [map-id  (get-in parameters [:path :id])
        the-map (parts-map/fetch map-id)]
    (-> (response/response the-map)
        (response/status 200))))

(defn update-map
  "Update an existing map. Access is gated by `wrap-map-access` middleware."
  [{:keys [identity parameters body-params] :as _request}]
  (let [user-id (:sub identity)
        map-id  (get-in parameters [:path :id])
        updated (parts-map/update! map-id body-params user-id)]
    (-> (response/response updated)
        (response/status 200))))

(defn delete-map
  "Delete a map. Access is gated by `wrap-map-access` middleware."
  [{:keys [identity parameters] :as _request}]
  (let [user-id (:sub identity)
        map-id  (get-in parameters [:path :id])]
    (parts-map/delete! map-id user-id)
    (response/status 204)))

(defn process-changes
  "Apply a batch of changes for a map atomically. Access is gated by
   `wrap-map-access` middleware.

   Request body: a single change-event map, or a vector of them. Each has:
   - `entity`: the entity type (`part`, `relationship`)
   - `id`: the entity ID
   - `type`: the operation (`create`, `update`, `remove`)
   - `data`: operation-specific payload

   Atomicity is all-or-nothing: any failure rolls back the entire batch.

   Responses:
   - 200 OK + `{:success true :results [...]}` when every change applied
   - 404 Not Found when the Map doesn't exist or isn't owned by the caller
   - 422 Unprocessable + `{:error ... :failing_change <change>}` when a
     domain error rolled the batch back (handled by the exception middleware)
   - 409 Conflict for DB constraint violations
   - 500 for unexpected exceptions"
  [{:keys [identity parameters body-params] :as _request}]
  (let [user-id (:sub identity)
        map-id  (get-in parameters [:path :id])
        results (events/apply-changes!
                 db/datasource
                 {:map-id   (db/->uuid map-id)
                  :actor-id user-id
                  :changes  body-params})]
    (mulog/log ::process-changes
               :user-id      user-id
               :map-id       map-id
               :change-count (count results))
    (-> (response/response {:success true :results results})
        (response/status 200))))

;; TODO: Implement PDF export endpoint once we have the PDF generation service
(defn export-pdf
  "Generate PDF export of a map"
  [_request]
  (-> (response/response {:error "Not implemented"})
      (response/status 501)))
