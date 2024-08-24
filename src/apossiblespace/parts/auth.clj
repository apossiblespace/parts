(ns apossiblespace.parts.auth
  (:require
   [buddy.sign.jwt :as jwt]
   [buddy.auth :refer [authenticated?]]
   [buddy.auth.backends :as backends]
   [buddy.auth.middleware :refer [wrap-authentication wrap-authorization]]
   [buddy.hashers :as hashers]
   [ring.util.response :as response]
   [apossiblespace.parts.db :as db]
   [com.brunobonacci.mulog :as mulog])
  (:import
   [java.time Instant]))

;; FIXME: Move this into an env variable or something
;; .env file?
(def secret "some-secret-key")

(def auth-backend (backends/jws {:secret secret}))

(defn create-token
  "Create a JWT token that will expire on 1 hour"
  [user-id]
  (let [now (Instant/now)
        claims {:user-id user-id
                :exp (.plusSeconds now 3600)}]
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

;; TODO: Probably need to set up some kind of rate limiting so that we don't get
;; flooded with spam registrations once this is public
(defn register
  "Creates a record for a new user"
  [{:keys [email username display-name password role] :as user-data}]
  (let [existing-user (db/query-one (db/sql-format {:select [*]
                                                    :from [:users]
                                                    :where [:or
                                                            [:= :email email]
                                                            [:= :username username]]}))]
    (if existing-user
      {:error "User with this email or username already exists"}
      (let [hashed-password (hash-password password)
            new-user (-> user-data
                         (dissoc :password)
                         (assoc :password-hash hashed-password))]
        (db/insert! :users new-user)
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
