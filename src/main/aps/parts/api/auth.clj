(ns aps.parts.api.auth
  (:require
   [aps.parts.auth :as auth]
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
   session middleware to clear the cookie."
  [_request]
  (mulog/log ::logout :status :success)
  (-> (response/response {:message "Logged out successfully"})
      (response/status 200)
      (auth/clear-session)))
