(ns parts.entity.user
  (:require
   [com.brunobonacci.mulog :as mulog]
   [parts.auth :as auth]
   [parts.db :as db]
   [parts.entity.system :as system]))

(def allowed-update-fields #{:email :display_name :password})
(def sensitive-fields #{:password_hash})
(def valid-roles #{"client" "therapist"})

(defn- validate-attrs
  "Perform validations to ensure the user attributes are ready to be persisted"
  [attrs]
  (when (empty? attrs)
    (throw (ex-info "Nothing to update" {:type :validation})))
  (when-let [role (:role attrs)]
    (when-not (contains? valid-roles role)
      (throw (ex-info "Invalid role" {:type :validation}))))
  (let [{:keys [password password_confirmation]} attrs]
    (when password
      (when (not= password password_confirmation)
        (throw (ex-info "Password and confirmation do not match" {:type :validation})))))
  attrs)

(defn- sanitize-attrs
  "Ensure we are not trying to save attributes that cannot be updated"
  [attrs]
  (-> attrs
      (select-keys allowed-update-fields))
  (select-keys attrs allowed-update-fields))

(defn- set-password-hash
  "Prepare a user record to be persisted by removing the password attribute and
  inserting a password hash instead."
  [attrs]
  (if-let [password (:password attrs)]
    (-> attrs
        (dissoc :password :password_confirmation)
        (assoc :password_hash (auth/hash-password password)))
    attrs))

(defn- remove-sensitive-data
  "Ensure we are not echoing back sensitive informatin (eg password hash)"
  [attrs]
  (apply dissoc attrs sensitive-fields))

(defn fetch
  "Retrieve a user record from the database"
  [id]
  (if-let [user (db/query-one
                 (db/sql-format
                  {:select [:id :email :username :display_name :role]
                   :from [:users]
                   :where [:= :id id]}))]
    (remove-sensitive-data user)
    (throw (ex-info "User not found" {:type :not-found :id id}))))

(defn update!
  "Update a user record with provided attributes"
  [id attrs]
  (when (not id) (throw (ex-info "Missing User ID" {:type :validation})))
  (let [sanitized-attrs (-> attrs
                            sanitize-attrs
                            validate-attrs
                            set-password-hash)]
    (remove-sensitive-data
     (first (db/update! :users sanitized-attrs [:= :id id])))))

(defn create!
  "Create a new user record with the provided attributes"
  [attrs]
  (let [validated-attrs (-> attrs
                            validate-attrs
                            set-password-hash)]
    (remove-sensitive-data
     (db/insert! :users validated-attrs))))

;; TODO: Add unit tests
(defn delete!
  "Delete a user record and all associated data:
  - All systems owned by the user
  - All parts and relationships in those systems
  - All refresh tokens for the user"
  [id]
  (let [systems (db/query
                 (db/sql-format
                  {:select [:id]
                   :from [:systems]
                   :where [:= :owner_id id]}))]
    (doseq [system systems]
      (system/delete! (:id system)))

    (db/delete! :refresh_tokens [:= :user_id id])

    (let [result (db/delete! :users [:= :id id])
          deleted (pos? (or (:next.jdbc/update-count (first result)) 0))]
      (mulog/log ::delete-user-complete
                 :user-id id
                 :success deleted
                 :systems-deleted (count systems))
      {:id id :deleted deleted})))
