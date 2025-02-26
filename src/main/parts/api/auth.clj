(ns parts.api.auth
  (:require
   [com.brunobonacci.mulog :as mulog]
   [parts.auth :as auth]
   [ring.util.response :as response]))

(defn login
  [request]
  (let [{:keys [email password]} (:body request)]
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
  "Generate new access and refresh tokens using a valid refresh token"
  [request]
  (let [refresh-token (get-in request [:body :refresh_token])]
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
  "Invalidate the refresh token to prevent its future use"
  [request]
  (let [refresh-token (get-in request [:body :refresh_token])]
    (if refresh-token
      (do
        (auth/invalidate-refresh-token refresh-token)
        (mulog/log ::logout :status :success)
        (-> (response/response {:message "Logged out successfully"})
            (response/status 200)))
      (-> (response/response {:message "Logged out successfully"})
          (response/status 200)))))
