(ns tools.ifs.parts.api.account
  (:require
   [com.brunobonacci.mulog :as mulog]
   [ring.util.response :as response]
   [tools.ifs.parts.entity.user :as user]))

(defn get-account
  "Retrieve own account info"
  [request]
  (let [user-id (get-in request [:identity :sub])
        user-record (user/fetch user-id)]
    (-> (response/response user-record)
        (response/status 200))))

(defn update-account
  "Update own account info"
  [request]
  (let [user-id (get-in request [:identity :sub])
        body (:body request)
        updated-user (user/update! user-id body)]
    (mulog/log ::update-account-success :user-id user-id)
    (-> (response/response updated-user)
        (response/status 200))))

(defn register-account
  "Creates a record for a new user account"
  [request]
  (let [account (user/create! (:body request))]
    (mulog/log ::register :email (:email account) :username (:username account) :status :success)
    (-> (response/response account)
        (response/status 201))))

(defn delete-account
  "Delete own account"
  [request]
  (let [user-id (get-in request [:identity :sub])
        user (user/fetch user-id)
        confirm (get-in request [:query-params "confirm"])]
    (if user
      (if (= (:username user) confirm)
        (do
          (user/delete! user-id)
          (response/status 204))
        (throw (ex-info "Confirmation needed" {:type :validation})))
      (do
        (mulog/log ::update-account-not-found :user-id user-id)
        (throw (ex-info "User not found" {:type :not-found}))))))
