(ns aps.parts.api.systems
  (:require
   [aps.parts.api.systems-events :as events]
   [aps.parts.db :as db]
   [aps.parts.entity.system :as system]
   [com.brunobonacci.mulog :as mulog]
   [ring.util.response :as response]))

(defn list-systems
  "List all systems for the authenticated user"
  [{:keys [identity] :as _request}]
  (let [user-id (:sub identity)
        systems (system/index user-id)]
    (-> (response/response systems)
        (response/status 200))))

(defn create-system
  "Create a new system"
  [{:keys [identity body-params] :as _request}]
  (let [user-id     (:sub identity)
        system-data (assoc body-params :owner_id user-id)
        created     (system/create! system-data user-id)]
    (-> (response/response created)
        (response/status 201))))

(defn get-system
  "Get a system by ID. Access is gated by `wrap-system-access` middleware."
  [{:keys [parameters] :as _request}]
  (let [system-id (get-in parameters [:path :id])
        system    (system/fetch system-id)]
    (-> (response/response system)
        (response/status 200))))

(defn update-system
  "Update an existing system. Access is gated by `wrap-system-access` middleware."
  [{:keys [identity parameters body-params] :as _request}]
  (let [user-id   (:sub identity)
        system-id (get-in parameters [:path :id])
        updated   (system/update! system-id body-params user-id)]
    (-> (response/response updated)
        (response/status 200))))

(defn delete-system
  "Delete a system. Access is gated by `wrap-system-access` middleware."
  [{:keys [identity parameters] :as _request}]
  (let [user-id   (:sub identity)
        system-id (get-in parameters [:path :id])]
    (system/delete! system-id user-id)
    (response/status 204)))

(defn process-changes
  "Apply a batch of changes for a system atomically. Access is gated by
   `wrap-system-access` middleware.

   Request body: a single change-event map, or a vector of them. Each has:
   - `entity`: the entity type (`part`, `relationship`)
   - `id`: the entity ID
   - `type`: the operation (`create`, `update`, `remove`)
   - `data`: operation-specific payload

   Atomicity is all-or-nothing: any failure rolls back the entire batch.

   Responses:
   - 200 OK + `{:success true :results [...]}` when every change applied
   - 404 Not Found when the System doesn't exist or isn't owned by the caller
   - 422 Unprocessable + `{:error ... :failing_change <change>}` when a
     domain error rolled the batch back (handled by the exception middleware)
   - 409 Conflict for DB constraint violations
   - 500 for unexpected exceptions"
  [{:keys [identity parameters body-params] :as _request}]
  (let [user-id   (:sub identity)
        system-id (get-in parameters [:path :id])
        results   (events/apply-changes!
                   db/datasource
                   {:system-id (db/->uuid system-id)
                    :actor-id  user-id
                    :changes   body-params})]
    (mulog/log ::process-changes
               :user-id      user-id
               :system-id    system-id
               :change-count (count results))
    (-> (response/response {:success true :results results})
        (response/status 200))))

;; TODO: Implement PDF export endpoint once we have the PDF generation service
(defn export-pdf
  "Generate PDF export of a system"
  [_request]
  (-> (response/response {:error "Not implemented"})
      (response/status 501)))
