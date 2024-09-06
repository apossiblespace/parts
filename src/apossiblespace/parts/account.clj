(ns apossiblespace.parts.account
  (:require
   [com.brunobonacci.mulog :as mulog]
   [ring.util.response :as response]
   [apossiblespace.parts.db :as db]
   [apossiblespace.parts.auth :as auth]))

;; TODO: Should this be in a users namespace?
(defn- fetch-user
  "Retrieve a user record from the database"
  [id]
  (db/query-one (db/sql-format {:select [:id :email :username :display_name :role]
                                :from [:users]
                                :where [:= :id id]})))

;; TODO: It would be good to have an api namespace to do things like handling
;; standard errors (404, 403, etc) and whatever else code ends up being
;; boilerplate.
(defn get-account
  "Retrieve own account info"
  [request]
  (mulog/log ::get-account :request request)
  (let [user-id (get-in request [:identity :sub])
        user-record (fetch-user user-id)]
    (if user-record
      (-> (response/response user-record)
          (response/status 200))
      (-> (response/response {:error "User not found"})
          (response/status 404)))))

(defn update-account
  "Update own account info"
  [account-data]
  {:success "PUT account"})

(defn delete-account
  "Delete own account"
  [confirm]
  {:success "DELETE account"})

;; FIXME: This needs to be returning a Ring response!
(defn register-account
  "Creates a record for a new user account"
  [{:keys [email username] :as user-data}]
  (let [existing-user (db/query-one (db/sql-format {:select [*]
                                                    :from [:users]
                                                    :where [:or
                                                            [:= :email email]
                                                            [:= :username username]]}))]
    (if existing-user
      (do
        (mulog/log ::register :email email :username username :status :failure)
        {:error "User with this email or username already exists"})
      (do
        (mulog/log ::register :email email :username username :status :success)
        (db/insert! :users (auth/prepare-user-record user-data))
        {:success "User registered successfully"}))))

