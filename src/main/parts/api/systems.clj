(ns parts.api.systems
  (:require
   [parts.auth :as auth]
   [parts.entity.system :as system]
   [ring.util.response :as response]))

(defn list-systems
  "List all systems for the authenticated user"
  [{:keys [identity] :as _request}]
  (let [user-id (:sub identity)
        systems (system/list-systems user-id)]
    (-> (response/response systems)
        (response/status 200))))

(defn create-system
  "Create a new system"
  [{:keys [identity body] :as _request}]
  (let [user-id (:sub identity)
        system-data (assoc body :owner_id user-id)
        created (system/create-system! system-data)]
    (-> (response/response created)
        (response/status 201))))

(defn get-system
  "Get a system by ID"
  [{:keys [identity parameters] :as _request}]
  (let [user-id (:sub identity)
        system-id (get-in parameters [:path :id])
        system (system/get-system system-id)]
    (if (= user-id (:owner_id system))
      (-> (response/response system)
          (response/status 200))
      (-> (response/response {:error "Not authorized"})
          (response/status 403)))))

(defn update-system
  "Update an existing system"
  [{:keys [identity parameters body] :as _request}]
  (let [user-id (:sub identity)
        system-id (get-in parameters [:path :id])
        existing (system/get-system system-id)]
    (if (= user-id (:owner_id existing))
      (let [updated (system/update-system! system-id
                                           (assoc body :owner_id (:owner_id existing)))]
        (-> (response/response updated)
            (response/status 200)))
      (-> (response/response {:error "Not authorized"})
          (response/status 403)))))

(defn delete-system
  "Delete a system"
  [{:keys [identity parameters] :as _request}]
  (let [user-id (:sub identity)
        system-id (get-in parameters [:path :id])
        existing (system/get-system system-id)]
    (if (= user-id (:owner_id existing))
      (do
        (system/delete-system! system-id)
        (response/status 204))
      (-> (response/response {:error "Not authorized"})
          (response/status 403)))))

;; TODO: Implement PDF export endpoint once we have the PDF generation service
(defn export-pdf
  "Generate PDF export of a system"
  [_request]
  (-> (response/response {:error "Not implemented"})
      (response/status 501)))
