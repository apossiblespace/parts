(ns aps.parts.api.account
  (:require
   [aps.parts.auth :as auth]
   [aps.parts.entity.system :as system]
   [aps.parts.entity.user :as user]
   [com.brunobonacci.mulog :as mulog]
   [ring.util.response :as response]))

(defn get-account
  "Retrieve own account info"
  [request]
  (let [user-id     (get-in request [:identity :sub])
        user-record (user/fetch user-id)]
    (-> (response/response user-record)
        (response/status 200))))

(defn update-account
  "Update own account info"
  [request]
  (let [user-id      (get-in request [:identity :sub])
        body         (:body-params request)
        updated-user (user/update! user-id body)]
    (mulog/log ::update-account-success :user-id user-id)
    (-> (response/response updated-user)
        (response/status 200))))

(defn register-account
  "Creates a record for a new user account.
   - Hardcodes role to 'therapist'
   - Creates a default system for the user
   - Returns auth tokens for auto-login"
  [request]
  (let [params       (-> (:body-params request)
                         (assoc :role "therapist"))
        account      (user/create! params)
        system-title (str (:username account) "'s System")
        new-system   (system/create! {:title    system-title
                                      :owner_id (:id account)})
        tokens       (auth/authenticate {:email    (:email params)
                                         :password (:password params)})]
    (mulog/log ::register
               :email (:email account)
               :username (:username account)
               :system-id (:id new-system)
               :status :success)
    (-> (response/response (merge account
                                  tokens
                                  {:system_id (:id new-system)}))
        (response/status 201))))

(defn delete-account
  "Delete own account"
  [request]
  (let [user-id (get-in request [:identity :sub])
        user    (user/fetch user-id)
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
