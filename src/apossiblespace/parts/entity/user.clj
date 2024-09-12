(ns apossiblespace.parts.entity.user
  (:require
   [apossiblespace.parts.db :as db]))

(defn fetch
  "Retrieve a user record from the database, or throw if not found"
  [id]
  (if-let [user (db/query-one
                 (db/sql-format
                  {:select [:id :email :username :display_name :role]
                   :from [:users]
                   :where [:= :id id]}))]
    user
    (throw (ex-info "User not found" {:type :not-found}))))
