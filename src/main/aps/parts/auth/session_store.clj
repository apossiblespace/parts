(ns aps.parts.auth.session-store
  "DB-backed ring session store (TASK-023, superseding ADR-0007's encrypted
   cookie store). The cookie holds only an opaque random UUID; data lives in
   `auth_sessions`, giving the server what a cookie store can't: revocation.
   \"Log out everywhere\" deletes the user's rows, and account deletion
   cascades them via the users FK.

   Expiry is ABSOLUTE from creation (ADR-0007's 14-day bound): a write
   refreshes the data, never the deadline. Writing to an expired or unknown
   id starts that id afresh — an expired session is indistinguishable from
   no session."
  (:require
   [aps.parts.db :as db]
   [clojure.edn :as edn]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]
   [ring.middleware.session.store :as store])
  (:import
   [java.util UUID]))

(defn- ->uuid-or-nil
  "The cookie value is untrusted input — anything that isn't a UUID (or is
   a stale encrypted blob from the old cookie store) reads as no session."
  [s]
  (when (string? s)
    (try (UUID/fromString s) (catch IllegalArgumentException _ nil))))

(deftype DbSessionStore [ds max-age-seconds]
  store/SessionStore
  (read-session [_ key]
    (or (when-let [id (->uuid-or-nil key)]
          (some-> (jdbc/execute-one!
                   ds
                   ["SELECT data FROM auth_sessions
                     WHERE id = ? AND expires_at > now()" id]
                   {:builder-fn rs/as-unqualified-maps})
                  :data
                  edn/read-string))
        {}))
  (write-session [_ key data]
    (let [id      (or (->uuid-or-nil key) (UUID/randomUUID))
          user-id (some-> (get-in data [:identity :sub]) ->uuid-or-nil)]
      (jdbc/execute!
       ds
       ["INSERT INTO auth_sessions (id, user_id, data, expires_at)
         VALUES (?, ?, ?, now() + make_interval(secs => ?))
         ON CONFLICT (id) DO UPDATE
         SET data    = EXCLUDED.data,
             user_id = EXCLUDED.user_id,
             expires_at = CASE WHEN auth_sessions.expires_at <= now()
                               THEN EXCLUDED.expires_at
                               ELSE auth_sessions.expires_at END"
        id user-id (pr-str data) (double max-age-seconds)])
      (str id)))
  (delete-session [_ key]
    (when-let [id (->uuid-or-nil key)]
      (jdbc/execute! ds ["DELETE FROM auth_sessions WHERE id = ?" id]))
    nil))

(defn db-store
  "A SessionStore over `auth_sessions` with an absolute `max-age-seconds`."
  [ds max-age-seconds]
  (->DbSessionStore ds max-age-seconds))

(defn revoke-for-user!
  "Delete every auth session belonging to `user-id` — \"log out everywhere\".
   Returns the number of sessions revoked."
  [ds user-id]
  (::jdbc/update-count
   (jdbc/execute-one!
    ds
    ["DELETE FROM auth_sessions WHERE user_id = ?" (db/->uuid user-id)])))

(defn delete-expired!
  "Sweep expired sessions; returns the number removed. Correctness doesn't
   depend on this (reads filter on expires_at) — it only reclaims rows."
  [ds]
  (::jdbc/update-count
   (jdbc/execute-one! ds ["DELETE FROM auth_sessions WHERE expires_at <= now()"])))
