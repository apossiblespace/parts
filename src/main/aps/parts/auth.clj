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
   [aps.parts.common.utils :refer [normalize-email]]
   [aps.parts.config :as conf]
   [aps.parts.db :as db]
   [buddy.auth.backends :as backends]
   [buddy.hashers :as hashers]
   [ring.middleware.session.cookie :refer [cookie-store]]))

;; -- credentials ----------------------------------------------------------

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

;; -- the auth session -----------------------------------------------------

(def backend
  "buddy session backend. With `wrap-authentication`, it lifts
   `request[:session][:identity]` into `request[:identity]` — so a handler
   reads the authenticated user via `current-user-id`."
  (backends/session))

(def ^:private session-max-age
  "Absolute auth-session lifetime — 14 days, in seconds (ADR-0007). With the
   encrypted cookie store there is no server-side revocation, so this is the
   one browser-enforced bound on a compromised cookie."
  (* 14 24 60 60))

(defn session-config
  "Ring session config for the one auth session shared by the HTML routes
   and /api: an encrypted (AES) cookie store, httpOnly, SameSite=Lax, Secure
   in production only (dev is plain HTTP). The 16-byte key comes from config
   and must be stable in prod — rotating it invalidates every session. See
   ADR-0007."
  []
  {:store        (cookie-store {:key (.getBytes ^String (conf/session-key) "UTF-8")})
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
