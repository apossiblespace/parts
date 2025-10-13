(ns aps.parts.api.auth
  (:require
   [com.brunobonacci.mulog :as mulog]
   [aps.parts.auth :as auth]
   [ring.util.response :as response]))

(defn login
  "Handler for the POST /api/auth/login endpoint.
  Generate new access and refresh tokens by authenticating via email/password"
  [request]
  (let [{:keys [email password]} (:body-params request)]
    (if-let [tokens (auth/authenticate {:email email :password password})]
      (do
        (mulog/log ::login :email email :status :success)
        (-> (response/response tokens)
            (response/status 200)))
      (do
        (mulog/log ::login :email email :status :failure)
        (-> (response/response {:error "Invalid credentials"})
            (response/status 401))))))

(defn refresh
  "Handler for the POST /api/auth/refresh endpoint.
  Generate new access and refresh tokens using a valid refresh token"
  [request]
  (let [refresh-token (get-in request [:body-params :refresh_token])]
    (if-let [new-tokens (auth/refresh-auth-tokens refresh-token)]
      (do
        (mulog/log ::refresh :status :success)
        (-> (response/response new-tokens)
            (response/status 200)))
      (do
        (mulog/log ::refresh :status :failure)
        (-> (response/response {:error "Invalid refresh token"})
            (response/status 401))))))

(defn logout
  "Handler for the POST /api/auth/logout endpoint.
  Invalidate the refresh token to prevent its future use"
  [request]
  (let [refresh-token (get-in request [:body-params :refresh_token])]
    (if refresh-token
      (do
        (auth/invalidate-refresh-token refresh-token)
        (mulog/log ::logout :status :success)
        (-> (response/response {:message "Logged out successfully"})
            (response/status 200)))
      (-> (response/response {:message "Logged out successfully"})
          (response/status 200)))))
