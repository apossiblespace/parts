(ns apossiblespace.parts.auth
  (:require
   [buddy.sign.jwt :as jwt]
   [buddy.auth :refer [authenticated?]]
   [buddy.auth.backends :as backends]
   [buddy.auth.middleware :refer [wrap-authentication wrap-authorization]]
   [buddy.hashers :as hashers]
   [ring.util.response :as response]
   [apossiblespace.parts.db :as db]
   [apossiblespace.parts.config :as conf]
   [com.brunobonacci.mulog :as mulog])
  (:import
   [java.time Instant]))

(def secret
  (conf/jwt-secret (conf/config)))

(def auth-backend (backends/jws {:secret secret}))

(defn create-token
  "Create a JWT token that will expire in 1 hour"
  [user-id]
  (let [now (Instant/now)
        exp (.plusSeconds now 3600)
        claims {:user-id user-id
                :exp (.getEpochSecond exp)}]
    (jwt/sign claims secret)))

(defn hash-password
  [password]
  (hashers/derive password))

(defn check-password
  [password hash]
  (:valid (hashers/verify password hash)))

(defn authenticate
  "Checks if a user represented by EMAIL exists in db, checks their PASSWORD if so"
  [{:keys [email password]}]
  (when-let [user (db/query-one (db/sql-format {:select [:*]
                                                :from [:users]
                                                :where [:= :email email]}))]
    (when (check-password password (:password_hash user))
      (create-token (:id user)))))

(defn prepare-user-record
  "Prepares a User record for database writing by removing the PASSWORD key and
  replacing it with a PASSWORD_HASH key containing a hashed password."
  [user-data]
  (let [{:keys [password]} user-data
        hashed-password (hash-password password)]
    (-> user-data
        (dissoc :password)
        (assoc :password-hash hashed-password))))

;; TODO: Probably need to set up some kind of rate limiting so that we don't get
;; flooded with spam registrations once this is public
(defn register
  "Creates a record for a new user"
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
        (db/insert! :users (prepare-user-record user-data))
        {:success "User registered successfully"}))))

(defn login
  [request]
  (let [{:keys [email password]} (:body request)]
    (if-let [token (authenticate {:email email :password password})]
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

(defn wrap-jwt-authentication
  "Middleware adding JWT authentication to a route"
  [handler]
  (-> handler
      (wrap-authentication auth-backend)
      (wrap-authorization auth-backend)))

(defn jwt-auth
  "Middleware ensuring a route is only accessible to authenticated users"
  [handler]
  (fn [request]
    (if (authenticated? request)
      (handler request)
      (-> (response/response {:error "Unauthorized"})
          (response/status 401)))))

(defn get-user-id-from-token
  [request]
  (get-in request [:identity :user-id]))

(comment
  ;; Example usage in REPL
  (def user {:email "test@example.com"
             :username "testuser"
             :display-name "Test User"
             :password "password123"
             :role "client"})
  (register user)

  (def token (authenticate {:email "test@example.com" :password "password123"}))

  (def invalid-token (authenticate {:email "test@example.com" :password "wrongpassword"}))

  (when token (jwt/unsign token secret))
  #_())
