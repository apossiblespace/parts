(ns aps.parts.auth
  "Authentication primitives.

   Auth is a server-side session (TASK-022, ADR-0007): on login the user's
   id is written into an encrypted, httpOnly session cookie; buddy's session
   backend copies `request[:session][:identity]` to `request[:identity]` on
   every request. No JWTs, no bearer tokens — see ADR-0007."
  (:require
   [aps.parts.common.utils :refer [normalize-email]]
   [aps.parts.db :as db]
   [buddy.auth.backends :as backends]
   [buddy.hashers :as hashers]))

(def backend
  "buddy session backend. With `wrap-authentication`, it lifts
   `request[:session][:identity]` into `request[:identity]` — so a handler
   reads the authenticated user via `(get-in request [:identity :sub])`,
   the same shape the old JWT claims used."
  (backends/session))

(defn hash-password
  [password]
  (hashers/derive password))

(defn check-password
  [password hash]
  (:valid (hashers/verify password hash)))

(defn authenticate
  "Verify EMAIL + PASSWORD against the stored user. Returns the user map
   (without `password_hash`) on success, nil on a missing user or a wrong
   password. The caller establishes the auth session from the returned id."
  [{:keys [email password]}]
  (let [normalized-email (normalize-email email)]
    (when-let [user (db/query-one
                     (db/sql-format {:select [:*]
                                     :from   [:users]
                                     :where  [:= :email normalized-email]}))]
      (when (check-password password (:password_hash user))
        (dissoc user :password_hash)))))

(defn session-identity
  "The value to store under `[:session :identity]` for `user-id`. A map with
   `:sub` (stringified id) — matching the claims shape handlers already read."
  [user-id]
  {:sub (str user-id)})
