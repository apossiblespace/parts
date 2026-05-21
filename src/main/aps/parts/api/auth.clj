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
        ;; Merge :identity into the *existing* session — a bare
        ;; `{:identity ...}` would drop ring's anti-forgery token, which
        ;; lives in the same session, and break the SPA's CSRF header.
        (-> (response/response user)
            (response/status 200)
            (assoc :session (assoc (:session request)
                                   :identity (auth/session-identity (:id user))))))
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
      ;; Drop the session and expire the cookie immediately, rather than
      ;; leaving an empty session cookie to linger for the full Max-Age.
      (assoc :session nil)
      (assoc :session-cookie-attrs {:max-age 0})))
