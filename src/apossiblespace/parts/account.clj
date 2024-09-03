(ns apossiblespace.parts.account
  (:require
   [com.brunobonacci.mulog :as mulog]
   [apossiblespace.parts.db :as db]
   [apossiblespace.parts.auth :as auth]))

(defn get-account
  "Retrieve own account info"
  []
  {:success "GET account"})

(defn update-account
  "Update own account info"
  [account-data]
  {:success "PUT account"})

(defn delete-account
  "Delete own account"
  [confirm]
  {:success "DELETE account"})

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
