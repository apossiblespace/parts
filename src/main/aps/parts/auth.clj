(ns aps.parts.auth
  (:require
   [aps.parts.config :as conf]
   [aps.parts.db :as db]
   [buddy.auth.backends :as backends]
   [buddy.hashers :as hashers]
   [buddy.sign.jwt :as jwt]
   [com.brunobonacci.mulog :as mulog]
   [lambdaisland.config :as l-config])
  (:import
   (java.time Instant)
   (java.util UUID)))

(def secret
  (l-config/get conf/config :auth/secret))

(def backend
  (backends/jws
   {:secret     secret
    :options    {:alg :hs256}
    :on-error   (fn [_request ex]
                  (mulog/log ::auth-backend :error (.getMessage ex))
                  nil)
    :token-name "Bearer"
    :auth-fn    (fn [claims]
                  (mulog/log ::auth-backend-auth-fn :claims claims)
                  claims)}))

(defn create-access-token
  "Create a short-lived JWT access token (15 minutes)"
  [user-id]
  (let [now    (Instant/now)
        exp    (.plusSeconds now 900) ; 15 minutes
        host   (conf/host-uri)
        claims {:iss  (str host "/api")
                :sub  (str user-id)
                :aud  host
                :type "access"
                :iat  (.getEpochSecond now)
                :exp  (.getEpochSecond exp)}]
    (jwt/sign claims secret {:alg :hs256})))

(defn create-refresh-token
  "Create a long-lived refresh token (30 days)"
  [user-id]
  (let [now      (Instant/now)
        exp      (.plusSeconds now 2592000) ; 30 days
        token-id (str (UUID/randomUUID))
        host     (conf/host-uri)
        claims   {:iss  (str host "/api")
                  :sub  user-id
                  :aud  host
                  :type "refresh"
                  :jti  token-id
                  :iat  (.getEpochSecond now)
                  :exp  (.getEpochSecond exp)}
        token    (jwt/sign claims secret {:alg :hs256})]

    ;; Store refresh token in database for validation/revocation
    (db/insert! :refresh_tokens
                {:user_id    user-id
                 :token_id   token-id
                 :expires_at (.toEpochMilli exp)
                 :created_at (.toEpochMilli now)})

    token))

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
                                                :from   [:users]
                                                :where  [:= :email email]}))]
    (when (check-password password (:password_hash user))
      {:access_token  (create-access-token (:id user))
       :refresh_token (create-refresh-token (:id user))
       :token_type    "Bearer"})))

(defn validate-refresh-token
  "Validates a refresh token and returns user-id if valid"
  [refresh-token]
  (try
    (let [{:keys [sub jti type exp]} (jwt/unsign refresh-token secret)
          token-record               (db/query-one
                                      (db/sql-format
                                       {:select [:*]
                                        :from   [:refresh_tokens]
                                        :where  [:and
                                                 [:= :token_id jti]
                                                 [:= :user_id (db/->uuid sub)]]}))]
      (when (and token-record
                 (= type "refresh")
                 (< (System/currentTimeMillis) (* exp 1000)))
        sub))
    (catch Exception e
      (mulog/log ::refresh-token-validation :error (.getMessage e))
      nil)))

(defn refresh-auth-tokens
  "Creates new access and refresh tokens if the refresh token is valid"
  [refresh-token]
  (when-let [user-id-str (validate-refresh-token refresh-token)]
    ;; Convert string UUID to UUID object
    (let [user-id (db/->uuid user-id-str)]
      ;; Invalidate the old refresh token
      (db/delete! :refresh_tokens [:= :token_id (get (jwt/unsign refresh-token secret) :jti)])

      ;; Create new tokens
      {:access_token  (create-access-token user-id)
       :refresh_token (create-refresh-token user-id)
       :token_type    "Bearer"})))

(defn invalidate-refresh-token
  "Invalidate a refresh token when user logs out"
  [refresh-token]
  (try
    (let [{:keys [jti]} (jwt/unsign refresh-token secret)]
      (db/delete! :refresh_tokens [:= :token_id jti])
      true)
    (catch Exception _
      false)))

(defn get-user-id-from-token
  [request]
  (get-in request [:identity :sub]))

(defn cleanup-expired-tokens
  "Remove all expired refresh tokens from the database"
  []
  (let [now     (System/currentTimeMillis)
        deleted (db/delete! :refresh_tokens [:< :expires_at now])]
    (mulog/log ::cleanup-expired-tokens :count (count deleted))
    deleted))

(comment
  ;; Example usage in REPL
  (def user {:email        "test@example.com"
             :username     "testuser"
             :display-name "Test User"
             :password     "password123"
             :role         "client"})

  (def tokens (authenticate {:email "test@example.com" :password "password123"}))

  (def invalid-tokens (authenticate {:email "test@example.com" :password "wrongpassword"}))

  (when-let [access-token (:access_token tokens)]
    (jwt/unsign access-token secret))

  (when-let [refresh-token (:refresh_token tokens)]
    (jwt/unsign refresh-token secret))
  #_())
