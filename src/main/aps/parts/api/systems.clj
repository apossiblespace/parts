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
  "Get a system by ID"
  [{:keys [identity parameters] :as _request}]
  (let [user-id   (:sub identity)
        system-id (get-in parameters [:path :id])
        system    (system/fetch system-id)]
    (if (= (db/->uuid user-id) (:owner_id system))
      (-> (response/response system)
          (response/status 200))
      (-> (response/response {:error "Not authorized"})
          (response/status 403)))))

(defn update-system
  "Update an existing system"
  [{:keys [identity parameters body-params] :as _request}]
  (let [user-id   (:sub identity)
        system-id (get-in parameters [:path :id])
        existing  (system/fetch system-id)]
    (if (= (db/->uuid user-id) (:owner_id existing))
      (let [updated (system/update!
                     system-id
                     (assoc body-params :owner_id (:owner_id existing))
                     user-id)]
        (-> (response/response updated)
            (response/status 200)))
      (-> (response/response {:error "Not authorized"})
          (response/status 403)))))

(defn delete-system
  "Delete a system"
  [{:keys [identity parameters] :as _request}]
  (let [user-id   (:sub identity)
        system-id (get-in parameters [:path :id])
        existing  (system/fetch system-id)]
    (if (= (db/->uuid user-id) (:owner_id existing))
      (do
        (system/delete! system-id user-id)
        (response/status 204))
      (-> (response/response {:error "Not authorized"})
          (response/status 403)))))

(defn- user-can-modify-system?
  "Check if the user has permission to modify the system.
   Compares user-id (JWT string) against owner_id (UUID from DB)."
  [user-id system]
  (= (db/->uuid user-id) (:owner_id system)))

(defn process-changes
  "Apply a batch of changes for a system atomically.

   Request body: a single change-event map, or a vector of them. Each has:
   - `entity`: the entity type (`part`, `relationship`)
   - `id`: the entity ID
   - `type`: the operation (`create`, `update`, `remove`, `position`)
   - `data`: operation-specific payload

   Atomicity is all-or-nothing: any failure rolls back the entire batch.

   Responses:
   - 200 OK + `{:success true :results [...]}` when every change applied
   - 403 Forbidden when the user doesn't own the system
   - 404 Not Found when the system doesn't exist
   - 422 Unprocessable + `{:error ... :failing_change <change>}` when a
     domain error rolled the batch back (handled by the exception middleware)
   - 409 Conflict for DB constraint violations
   - 500 for unexpected exceptions"
  [{:keys [identity parameters body-params] :as _request}]
  (let [user-id   (:sub identity)
        system-id (get-in parameters [:path :id])
        system    (system/fetch system-id)]
    (if-not (user-can-modify-system? user-id system)
      (-> (response/response {:error "Not authorized"})
          (response/status 403))
      (let [changes (if (sequential? body-params) body-params [body-params])
            results (events/apply-changes!
                     db/datasource
                     {:system-id (db/->uuid system-id)
                      :actor-id  user-id
                      :changes   changes})]
        (mulog/log ::process-changes
                   :user-id      user-id
                   :system-id    system-id
                   :change-count (count changes))
        (-> (response/response {:success true :results results})
            (response/status 200))))))

;; TODO: Implement PDF export endpoint once we have the PDF generation service
(defn export-pdf
  "Generate PDF export of a system"
  [_request]
  (-> (response/response {:error "Not implemented"})
      (response/status 501)))
