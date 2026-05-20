(ns aps.parts.db.erasure
  "Right-to-erasure: account deletion that exempts a single user from the
   no-DELETE invariant on temporal tables.

   Two-phase: `request-deletion!` marks the account, the user can
   `cancel-deletion!` within 30 days, after which the deletion-purge job
   calls `purge-account!` to hard-delete everything. Audit-log entries
   referencing the deleted user are pseudonymized to a tombstone UUID
   rather than deleted, preserving operational accountability.

   This is the *only* namespace that issues `DELETE FROM` on temporal
   tables — enforced by `aps.parts.architecture-test`."
  (:require
   [aps.parts.db :as db]
   [aps.parts.db.bitemporal :as bt]
   [com.brunobonacci.mulog :as mulog]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]))

(def tombstone-id
  "Permanent placeholder UUID for the deleted-user tombstone row.
   Inserted by migration 20260511000000."
  #uuid "00000000-0000-0000-0000-000000000000")

(def grace-period-days
  "How long an account stays in deletion-pending state before it's hard-deleted."
  30)

(defn request-deletion!
  "Mark the account for deletion. Auth middleware should refuse logins for
   users with `deletion_requested_at IS NOT NULL`. Idempotent: if already
   set, leaves the existing timestamp in place."
  [ds user-id]
  (mulog/log ::deletion-requested :user-id user-id)
  (db/update! :users
              {:deletion_requested_at [:coalesce :deletion_requested_at [:now]]}
              [:= :id (db/->uuid user-id)]
              ds))

(defn cancel-deletion!
  "Clear the deletion-pending state. Allowed only while
   `deletion_completed_at IS NULL` — once the purge job runs, this is moot."
  [ds user-id]
  (mulog/log ::deletion-cancelled :user-id user-id)
  (db/update! :users
              {:deletion_requested_at nil}
              [:and
               [:= :id (db/->uuid user-id)]
               [:= :deletion_completed_at nil]]
              ds))

(defn pending-deletions
  "Return user-ids whose grace window has expired and are ready to purge.
   The deletion-purge job iterates this and calls `purge-account!` for each."
  [ds]
  (->> (jdbc/execute!
        ds
        (db/sql-format
         {:select [:id]
          :from   [:users]
          :where  [:and
                   [:not= :deletion_requested_at nil]
                   [:= :deletion_completed_at nil]
                   [:< :deletion_requested_at
                    [:- [:now] [:raw (str "interval '" grace-period-days " days'")]]]
                   [:not= :id [:cast (str tombstone-id) :uuid]]]})
        {:builder-fn rs/as-unqualified-maps})
       (map :id)))

(defn purge-account!
  "Hard-delete a user account and all data they own.

   Inside one transaction:
     1. Set the session actor to the tombstone so audit triggers on the
        DELETEs below write rows that don't FK-reference the dying user.
     2. Hard-DELETE relationships / parts / maps owned by the user.
     3. Pseudonymize any historical `audit_log` rows still attributing
        pre-deletion activity to this user — they survive but are anonymous.
     4. Mark `deletion_completed_at` (sentinel for log correlation).
     5. Hard-DELETE the user row.

   For the v1 owner-only model, every part/relationship in a user's map
   was authored by that same user, so the pseudonymization in step 3 makes
   the map's audit history anonymous — that's the design. Other users'
   audit entries that *also* reference this user (as actor on rows in
   another user's map, in a future multi-user world) are pseudonymized
   the same way."
  [ds user-id]
  (let [user-uuid (db/->uuid user-id)]
    (when (= user-uuid tombstone-id)
      (throw (ex-info "Refusing to purge the tombstone user"
                      {:type :forbidden :user-id user-id})))
    (mulog/log ::purge-account-start :user-id user-id)
    (jdbc/with-transaction [tx ds]
      (bt/set-actor! tx tombstone-id)
      ;; Child cascade: mirror in `entity.map/delete-impl!`.
      (jdbc/execute! tx
                     ["DELETE FROM relationships
                       WHERE map_id IN (SELECT id FROM maps WHERE owner_id = ?)"
                      user-uuid])
      (jdbc/execute! tx
                     ["DELETE FROM parts
                       WHERE map_id IN (SELECT id FROM maps WHERE owner_id = ?)"
                      user-uuid])
      (jdbc/execute! tx
                     ["DELETE FROM map_metadata
                       WHERE map_id IN (SELECT id FROM maps WHERE owner_id = ?)"
                      user-uuid])
      (jdbc/execute! tx
                     ["DELETE FROM maps WHERE owner_id = ?" user-uuid])
      (db/update! :audit_log
                  {:actor_id [:cast (str tombstone-id) :uuid]}
                  [:= :actor_id user-uuid]
                  tx)
      (db/update! :users
                  {:deletion_completed_at [:now]}
                  [:= :id user-uuid]
                  tx)
      (jdbc/execute! tx ["DELETE FROM users WHERE id = ?" user-uuid]))
    (mulog/log ::purge-account-complete :user-id user-id)
    {:purged true :user-id user-id}))
