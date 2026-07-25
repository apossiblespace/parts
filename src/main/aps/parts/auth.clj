(ns aps.parts.auth
  "The authentication module.

   Owns everything authentication: proving a credential (`authenticate`),
   the shape and lifecycle of the auth session (`session-config`,
   `establish-session`, `clear-session`), and reading the authenticated
   identity back off a request (`current-user-id`).

   The ring middleware that enforces auth on routes (`wrap-session-auth`,
   `require-auth`, `wrap-map-access`) lives separately and depends on this
   namespace."
  (:require
   [aps.parts.auth.session-store :as session-store]
   [aps.parts.common.utils :refer [normalize-email]]
   [aps.parts.config :as conf]
   [aps.parts.db :as db]
   [buddy.auth.backends :as backends]
   [buddy.hashers :as hashers]
   [clojure.string :as str]))

;; -- credentials ----------------------------------------------------------

(defn hash-password
  [password]
  (hashers/derive password))

(defn check-password
  [password hash]
  (:valid (hashers/verify password hash)))

(def ^:private timing-decoy-hash
  "A throwaway bcrypt hash verified on the absent-user path, so a login
   attempt takes the same time whether or not the email exists — response
   timing must not enumerate accounts."
  (delay (hash-password "timing-equalization-decoy")))

(defn authenticate
  "Verify EMAIL + PASSWORD against the stored user. Returns the user map
   (without `password_hash`) on success, nil on a missing user or a wrong
   password. The caller establishes the auth session from the returned id."
  [{:keys [email password]}]
  (let [normalized-email (normalize-email email)
        user             (db/query-one
                          (db/sql-format {:select [:*]
                                          :from   [:users]
                                          :where  [:= :email normalized-email]}))]
    (if user
      (when (check-password password (:password_hash user))
        (dissoc user :password_hash))
      (do (check-password (or password "") @timing-decoy-hash)
          nil))))

(defn current-password-valid?
  "True when `password` matches the stored hash for `user-id`; blank or
   missing input is never valid. Step-up re-auth for credential changes —
   holding the session must not suffice to rotate the login credentials.
   Queries the hash directly because `user/fetch` strips it."
  [user-id password]
  (boolean
   (and (not (str/blank? password))
        (when-let [user (db/query-one
                         (db/sql-format {:select [:password_hash]
                                         :from   [:users]
                                         :where  [:= :id (db/->uuid user-id)]}))]
          (check-password password (:password_hash user))))))

;; -- the auth session -----------------------------------------------------

(def backend
  "buddy session backend. With `wrap-authentication`, it lifts
   `request[:session][:identity]` into `request[:identity]` — so a handler
   reads the authenticated user via `current-user-id`."
  (backends/session))

(def ^:private session-max-age
  "Absolute auth-session lifetime — 14 days, in seconds (ADR-0007). The
   DB-backed store enforces it server-side (`expires_at`); the cookie
   Max-Age merely mirrors it for the browser."
  (* 14 24 60 60))

(defn session-config
  "Ring session config for the one auth session shared by the HTML routes
   and /api: a DB-backed store (`aps.parts.auth.session-store` — opaque id
   in the cookie, data + server-side revocation in postgres), httpOnly,
   SameSite=Lax, Secure in production only (dev is plain HTTP). Supersedes
   ADR-0007's encrypted cookie store; the rest of that design (buddy
   session backend, cookie attributes, anti-forgery) is unchanged."
  []
  {:store        (session-store/db-store db/datasource session-max-age)
   :cookie-name  "parts-session"
   :cookie-attrs {:http-only true
                  :same-site :lax
                  :secure    (conf/prod?)
                  :max-age   session-max-age}})

(defn session-identity
  "The value stored under `[:session :identity]` for `user-id`: a map with
   `:sub` (stringified id). `current-user-id` is its inverse."
  [user-id]
  {:sub (str user-id)})

(defn establish-session
  "Attach an authenticated session for `user-id` to `response`.

   Merges `:identity` into the request's *existing* session rather than
   replacing it — a bare `{:identity ...}` would drop ring's anti-forgery
   token (which lives in the same session) and break the SPA's CSRF check."
  [response request user-id]
  (assoc response :session
         (assoc (:session request) :identity (session-identity user-id))))

(defn clear-session
  "Drop the auth session from `response` and expire its cookie immediately,
   rather than leaving an empty session cookie to linger for the full
   Max-Age. Used by logout and account deletion."
  [response]
  (-> response
      (assoc :session nil)
      (assoc :session-cookie-attrs {:max-age 0})))

(defn current-user-id
  "The authenticated user's id (a string), read from the session identity
   that `wrap-session-auth` lifted onto the request — the inverse of
   `session-identity`. nil when the request is unauthenticated."
  [request]
  (get-in request [:identity :sub]))
