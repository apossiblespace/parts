(ns apossiblespace.parts.api.auth
  (:require
   [apossiblespace.parts.auth :as auth]
   [ring.util.response :as response]
   [com.brunobonacci.mulog :as mulog]))

(defn login
  [request]
  (let [{:keys [email password]} (:body request)]
    (if-let [token (auth/authenticate {:email email :password password})]
      (do
        (mulog/log ::login :email email :status :success)
        (-> (response/response {:token token})
            (response/status 200)))
      (do
        (mulog/log ::login :email email :status :failure)
        (-> (response/response {:error "Invalid credentials"})
            (response/status 401))))))

;; In a stateless JWT setup, we don't need to do anything server-side for logout
;; The client should discard the token
;;
;; FIXME: For enhanced security, consider implementing token invalidation or
;; using short-lived tokens with refresh tokens
(defn logout
  [_]
  (-> (response/response {:message "Logged out successfully"})
      (response/status 200)))
