(ns tools.ifs.parts.auth
  (:require
   [buddy.sign.jwt :as jwt]
   [buddy.auth.backends :as backends]
   [buddy.hashers :as hashers]
   [tools.ifs.parts.db :as db]
   [tools.ifs.parts.config :as conf]
   [com.brunobonacci.mulog :as mulog])
  (:import
   [java.time Instant]))

(def secret
  (conf/jwt-secret (conf/config)))

(def backend
  (backends/jws
   {:secret secret
    :options {:alg :hs256}
    :on-error (fn [_request ex]
                (mulog/log ::auth-backend :error (.getMessage ex))
                nil)
    :token-name "Bearer"
    :auth-fn (fn [claims]
               (mulog/log ::auth-backend-auth-fn :claims claims)
               claims)}))

(defn create-token
  "Create a JWT token that will expire in 1 hour"
  [user-id]
  (let [now (Instant/now)
        exp (.plusSeconds now 3600)
        claims {:iss "http://localhost:3000/api" ;; TODO: Set this from configuration?
                :sub user-id
                :aud "http://localhost:3000"
                :iat (.getEpochSecond now)
                :exp (.getEpochSecond exp)}]
    (jwt/sign claims secret {:alg :hs256})))

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

  (def token (authenticate {:email "test@example.com" :password "password123"}))

  (def invalid-token (authenticate {:email "test@example.com" :password "wrongpassword"}))

  (when token (jwt/unsign token secret))
  #_())
