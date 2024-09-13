(ns apossiblespace.parts.entity.user
  (:require
   [apossiblespace.parts.db :as db]))

(def allowed-update-fields #{:email :display_name :password})

(defn- validate-attrs [attrs]
  (when (= {} attrs) (throw (ex-info "Nothing to update" {:type :validation})))
  (when-let [{:keys [password password_confirmation]} attrs]
    (when (and password (not= password password_confirmation))
      (throw (ex-info "Password and confirmation do not match" {:type :validation})))
  attrs))

(defn- sanitize-attrs [attrs]
  (-> attrs
      (select-keys allowed-update-fields)
      )
  (select-keys attrs allowed-update-fields))

(defn fetch
  "Retrieve a user record from the database"
  [id]
  (if-let [user (db/query-one
                 (db/sql-format
                  {:select [:id :email :username :display_name :role]
                   :from [:users]
                   :where [:= :id id]}))]
    user
    (throw (ex-info "User not found" {:type :not-found}))))

(defn update!
  "Update a user record with provided attributes"
  [attrs]
  (when (not (contains? attrs :id)) (throw (ex-info "Missing User ID" {:type :validation})))
  (let [id (:id attrs)
        sanitized-attrs (-> attrs
                            sanitize-attrs
                            validate-attrs)
        updated-user (first (db/update! :users sanitized-attrs [:= :id id]))]
    updated-user))
