(ns apossiblespace.parts.account
  (:require
   [com.brunobonacci.mulog :as mulog]
   [ring.util.response :as response]
   [clojure.string :as str]
   [apossiblespace.parts.db :as db]
   [apossiblespace.parts.auth :as auth]))

;; TODO: Should this be in a users namespace?
(defn- fetch-user
  "Retrieve a user record from the database"
  [id]
  (db/query-one (db/sql-format {:select [:id :email :username :display_name :role]
                                :from [:users]
                                :where [:= :id id]})))

(defn get-account
  "Retrieve own account info"
  [request]
  (let [user-id (get-in request [:identity :sub])
        user-record (fetch-user user-id)]
    (if user-record
      (-> (response/response user-record)
          (response/status 200))
      (do
        (mulog/log ::update-account-not-found :user-id user-id)
        (throw (ex-info "User not found" {:type :not-found}))))))

(def allowed-update-fields #{:email :display_name :password})

(defn- validate-password
  "PRIVATE: Validate password and confirmation matching"
  [password password-confirmation]
  (when (or (str/blank? password) (not= password password-confirmation))
    (throw (ex-info "Password and confirmation do not match" {:type :validation}))))

(defn- remove-disallowed-update-fields
  "PRIVATE: Strip keys that cannot be updated fom user data"
  [user-data]
  (select-keys user-data allowed-update-fields))

(defn prepare-user-data
  "Prepare a user record to be persisted by removing the password attribute and
  inserting a password hash instead."
  [body]
  (let [password (:password body)]
    (if (:password body)
      (do
        (validate-password (:password body) (:password_confirmation body))
        (-> body
            (dissoc :password :password_confirmation)
            (assoc :password_hash (auth/hash-password password))))
      body)))

(defn update-account
  "Update own account info"
  [request]
  (let [user-id (get-in request [:identity :sub])
        body (:body request)
        update-data (-> body
                        remove-disallowed-update-fields
                        prepare-user-data)]
    (if (= {} update-data)
      (do
        (mulog/log ::update-account-nothing-to-update :user-id user-id)
        (throw (ex-info "Nothing to update" {:type :validation})))
      (let [updated-user (first (db/update! :users update-data [:= :id user-id]))]
        (if updated-user
          (do
            (mulog/log ::update-account-success :user-id user-id)
            (-> (response/response (db/remove-sensitive-data updated-user))
                (response/status 200)))
          (do
            (mulog/log ::update-account-not-found :user-id user-id)
            (throw (ex-info "User not found" {:type :not-found}))))))))

(defn delete-account
  "Delete own account"
  [request]
  (let [user-id (get-in request [:identity :sub])
        user (fetch-user user-id)
        confirm (get-in request [:query-params "confirm"])]
    (if user
      (if (= (:username user) confirm)
        (do
          (db/delete! :users [:= :id user-id])
          (response/status 204))
        (throw (ex-info "Confirmation needed" {:type :validation})))
      (do
        (mulog/log ::update-account-not-found :user-id user-id)
        (throw (ex-info "User not found" {:type :not-found}))))))

(defn register-account
  "Creates a record for a new user account"
  [request]
  (let [account (db/insert! :users (prepare-user-data (:body request)))]
    (mulog/log ::register :email (:email account) :username (:username account) :status :success)
    (-> (response/response account)
        (response/status 201))))
