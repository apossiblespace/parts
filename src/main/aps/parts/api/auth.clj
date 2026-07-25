(ns aps.parts.api.auth
  (:require
   [aps.parts.auth :as auth]
   [aps.parts.auth.session-store :as session-store]
   [aps.parts.db :as db]
   [com.brunobonacci.mulog :as mulog]
   [ring.util.response :as response]))

(defn login
  "POST /api/auth/login — verify email/password and establish the auth
   session. On success the response carries `:session`, which the session
   middleware persists into the encrypted httpOnly cookie; the body is the
   authenticated user."
  [request]
  (let [{:keys [email password]} (:body-params request)]
    (if-let [user (auth/authenticate {:email email :password password})]
      (do
        (mulog/log ::login :email email :status :success)
        (-> (response/response user)
            (response/status 200)
            (auth/establish-session request (:id user))))
      (do
        (mulog/log ::login :email email :status :failure)
        (-> (response/response {:error "Invalid credentials"})
            (response/status 401))))))

(defn logout
  "POST /api/auth/logout — drop the auth session. `:session nil` tells the
   session middleware to clear the cookie (and the DB store to delete the
   row)."
  [_request]
  (mulog/log ::logout :status :success)
  (-> (response/response {:message "Logged out successfully"})
      (response/status 200)
      (auth/clear-session)))

(defn logout-everywhere
  "POST /api/auth/logout-everywhere — revoke every session belonging to the
   current user, this one included. The recovery move for a lost or shared
   device: any stolen cookie dies server-side, immediately."
  [request]
  (let [user-id (auth/current-user-id request)
        revoked (session-store/revoke-for-user! db/datasource user-id)]
    (mulog/log ::logout-everywhere :user-id user-id :revoked revoked)
    (-> (response/response {:message "Logged out everywhere" :revoked revoked})
        (response/status 200)
        (auth/clear-session))))
