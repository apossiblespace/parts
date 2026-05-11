(ns aps.parts.entity.user
  (:require
   [aps.parts.auth :as auth]
   [aps.parts.common.models.user :as model]
   [aps.parts.common.utils :refer [normalize-email]]
   [aps.parts.db :as db]
   [aps.parts.entity.system :as system]
   [clojure.spec.alpha :as s]
   [com.brunobonacci.mulog :as mulog]))

(def allowed-update-fields #{:email :display_name :password})
(def sensitive-fields #{:password_hash})
(def valid-roles #{"client" "therapist"})

(defn- normalize-attrs
  "Canonicalize fields that have a stable storage form (e.g. emails are
   case-insensitive; we store the lowercased+trimmed form). Runs before
   validation so the spec sees what will actually be persisted."
  [attrs]
  (cond-> attrs
    (:email attrs) (update :email normalize-email)))

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
      (when-not (s/valid? ::model/password password)
        (throw (ex-info (str "Password must be between "
                             model/password-min-length
                             " and "
                             model/password-max-length
                             " characters")
                        {:type :validation})))
      (when (not= password password_confirmation)
        (throw (ex-info "Password and confirmation do not match" {:type :validation})))))
  attrs)

(defn- sanitize-attrs
  "Ensure we are not trying to save attributes that cannot be updated"
  [attrs]
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
  "Retrieve a user record from the database, including their system_id"
  [id]
  (if-let [user (db/query-one
                 (db/sql-format
                  {:select    [[:u.id :id]
                               [:u.email :email]
                               [:u.username :username]
                               [:u.display_name :display_name]
                               [:u.role :role]
                               [:s.id :system_id]]
                   :from      [[:users :u]]
                   :left-join [[:systems :s] [:= :s.owner_id :u.id]]
                   :where     [:= :u.id (db/->uuid id)]}))]
    (remove-sensitive-data user)
    (throw (ex-info "User not found" {:type :not-found :id id}))))

(defn update!
  "Update a user record with provided attributes"
  [id attrs]
  (when (not id) (throw (ex-info "Missing User ID" {:type :validation})))
  (let [sanitized-attrs (-> attrs
                            sanitize-attrs
                            normalize-attrs
                            validate-attrs
                            set-password-hash)]
    (remove-sensitive-data
     (first (db/update! :users sanitized-attrs [:= :id (db/->uuid id)])))))

(defn create!
  "Create a new user record with the provided attributes.
   Accepts an optional datasource-or-transaction to participate in a surrounding tx."
  ([attrs] (create! attrs db/datasource))
  ([attrs tx]
   (let [validated-attrs (-> attrs
                             normalize-attrs
                             validate-attrs
                             set-password-hash)]
     (remove-sensitive-data
      (db/insert! :users validated-attrs tx)))))

;; TODO: Add unit tests
(defn delete!
  "Delete a user record and all associated data:
  - All systems owned by the user
  - All parts and relationships in those systems
  - All refresh tokens for the user"
  [id]
  (let [uuid-id (db/->uuid id)
        systems (db/query
                 (db/sql-format
                  {:select [:id]
                   :from   [:systems]
                   :where  [:= :owner_id uuid-id]}))]
    (doseq [system systems]
      (system/delete! (:id system)))

    (db/delete! :refresh_tokens [:= :user_id uuid-id])

    (let [result  (db/delete! :users [:= :id uuid-id])
          deleted (pos? (or (:next.jdbc/update-count (first result)) 0))]
      (mulog/log ::delete-user-complete
                 :user-id id
                 :success deleted
                 :systems-deleted (count systems))
      {:id id :deleted deleted})))
