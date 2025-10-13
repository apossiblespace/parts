(ns aps.parts.api.systems
  (:require
   [com.brunobonacci.mulog :as mulog]
   [next.jdbc :as jdbc]
   [aps.parts.db :as db]
   [aps.parts.entity.system :as system]
   [aps.parts.api.systems-events :as events]
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
  (let [user-id (:sub identity)
        system-data (assoc body-params :owner_id user-id)
        created (system/create! system-data)]
    (-> (response/response created)
        (response/status 201))))

(defn get-system
  "Get a system by ID"
  [{:keys [identity parameters] :as _request}]
  (let [user-id (:sub identity)
        system-id (get-in parameters [:path :id])
        system (system/fetch system-id)]
    (if (= user-id (:owner_id system))
      (-> (response/response system)
          (response/status 200))
      (-> (response/response {:error "Not authorized"})
          (response/status 403)))))

(defn update-system
  "Update an existing system"
  [{:keys [identity parameters body-params] :as _request}]
  (let [user-id (:sub identity)
        system-id (get-in parameters [:path :id])
        existing (system/fetch system-id)]
    (if (= user-id (:owner_id existing))
      (let [updated (system/update! system-id
                                    (assoc body-params :owner_id (:owner_id existing)))]
        (-> (response/response updated)
            (response/status 200)))
      (-> (response/response {:error "Not authorized"})
          (response/status 403)))))

(defn delete-system
  "Delete a system"
  [{:keys [identity parameters] :as _request}]
  (let [user-id (:sub identity)
        system-id (get-in parameters [:path :id])
        existing (system/fetch system-id)]
    (if (= user-id (:owner_id existing))
      (do
        (system/delete! system-id)
        (response/status 204))
      (-> (response/response {:error "Not authorized"})
          (response/status 403)))))

(defn- user-can-modify-system?
  "Check if the user has permission to modify the system.
   Currently always returns true as permissions are not implemented."
  [user-id system]
  (= user-id (:owner_id system)))

(defn process-changes
  "Process batches of changes sent by client

  Handles batches of change events for a system, applying them in a transaction.
  Each change is processed according to its entity type and operation.

  The request body should be an array of change objects, each with:
  - entity: The entity type (part, relationship)
  - id: The entity ID
  - type: The operation type (create, update, remove, position)
  - data: Operation-specific data

  Returns:
  - 200 OK with results if all changes succeed
  - 403 Forbidden if user doesn't own the system
  - 207 Multi-Status if some changes fail
  - 400 Bad Request for invalid input
  "
  [{:keys [identity parameters body-params] :as _request}]
  (let [user-id (:sub identity)
        system-id (get-in parameters [:path :id])]
    (try
      (let [system (system/fetch system-id)]
        (if (user-can-modify-system? user-id system)
          (try
            (let [changes (if (sequential? body-params) body-params [body-params])
                  results (jdbc/with-transaction [_tx (db/write-pool)]
                            (mapv #(events/process-change system-id %) changes))]

              (mulog/log ::process-changes
                         :user-id user-id
                         :system-id system-id
                         :change-count (count changes)
                         :success-count (count (filter :success results)))

              (if (every? :success results)
                (-> (response/response {:success true :results results})
                    (response/status 200))
                (-> (response/response {:success false
                                        :results results
                                        :error "Some changes failed"})
                    (response/status 207))))

            (catch Exception e
              (mulog/log ::process-changes-error
                         :user-id user-id
                         :system-id system-id
                         :error (.getMessage e))
              (-> (response/response {:error "Failed to process changes"
                                      :details (.getMessage e)})
                  (response/status 500))))

          (-> (response/response {:error "Not authorized"})
              (response/status 403))))

      (catch Exception e
        (mulog/log ::system-access-error
                   :user-id user-id
                   :system-id system-id
                   :error (.getMessage e))
        (-> (response/response {:error "System not found"})
            (response/status 404))))))

;; TODO: Implement PDF export endpoint once we have the PDF generation service
(defn export-pdf
  "Generate PDF export of a system"
  [_request]
  (-> (response/response {:error "Not implemented"})
      (response/status 501)))
