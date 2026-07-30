(ns aps.parts.db.erasure
  "Right-to-erasure: account deletion that exempts a single user from the
   no-DELETE invariant on temporal tables.

   Two-phase: `request-deletion!` marks the account, the user can
   `cancel-deletion!` within 30 days, after which the deletion-purge job
   hard-deletes everything through `aps.parts.entity.user/delete!` — the
   path that first releases the account's Stripe link, then calls
   `purge-account!`. Audit-log entries referencing the deleted user are
   pseudonymized to a tombstone UUID rather than deleted, preserving
   operational accountability.

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

(defn exclude-tombstone
  "HoneySQL WHERE fragment excluding the tombstone User, matched on `col` —
   `:id` on `users`, `:actor_id` on `audit_log`. One definition of the cast so
   the fleet/billing reports and the purge sweep can't drift."
  [col]
  [:not= col [:cast (str tombstone-id) :uuid]])

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
   The deletion-purge job iterates this and calls
   `aps.parts.entity.user/delete!` for each — never `purge-account!`
   directly, which would skip the Stripe-link release."
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
                    [:- [:now] [:cast (str grace-period-days " days") :interval]]]
                   (exclude-tombstone :id)]})
        {:builder-fn rs/as-unqualified-maps})
       (map :id)))

(defn purge-account!
  "Hard-delete a user account and all data they own.

   Inside one transaction:
     1. Set the session actor to the tombstone so audit triggers on the
        DELETEs below write rows that don't FK-reference the dying user.
     2. Capture the ids of every entity about to be deleted (for step 5's
        audit scrub, while the rows still exist).
     3. Hard-DELETE the user's email-keyed rows in invitations and
        waitlist_signups (resolving the email before the users row goes).
     4. Hard-DELETE relationships / parts / sessions (with their
        activation links) / maps owned by the user.
     5. Scrub `before_row`/`after_row` from every audit_log row describing
        those entities. The DELETEs in step 4 each fire the audit trigger,
        writing a fresh full snapshot of the just-erased content, and older
        rows hold its entire edit history — GDPR Art. 17 erasure of
        special-category health data must remove both. The rows themselves
        survive (who/when/what-table) for operational accountability;
        scrubbing after the fact covers historical and purge-generated
        rows in one statement, which is why capture isn't suppressed
        instead.
     6. Pseudonymize any historical `audit_log` rows still attributing
        pre-deletion activity to this user — they survive but are anonymous.
     7. Mark `deletion_completed_at` (sentinel for log correlation).
     8. Hard-DELETE the user row.

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
    ;; `billing/release-stripe-customer!` clears this column; a purge past
    ;; a still-set link would destroy the only pointer to a live
    ;; subscription.
    (when (:stripe_customer_id
           (jdbc/execute-one! ds
                              (db/sql-format
                               {:select [:stripe_customer_id]
                                :from   [:users]
                                :where  [:= :id user-uuid]})
                              {:builder-fn rs/as-unqualified-maps}))
      (throw (ex-info "Refusing to purge while a Stripe customer is linked — release it first (entity.user/delete! does)"
                      {:type :stripe-link-present :user-id user-id})))
    (mulog/log ::purge-account-start :user-id user-id)
    (jdbc/with-transaction [tx ds]
      ;; Assume the erasure-only role for the whole transaction (SET LOCAL
      ;; resets at commit/rollback, so the pooled connection comes back
      ;; unchanged). The everyday app role holds no DELETE on the temporal
      ;; tables (migration 20260726000000) — this is the one deliberate
      ;; path that does.
      (jdbc/execute! tx ["SET LOCAL ROLE deletion_role"])
      (bt/set-actor! tx tombstone-id)
      ;; audit_log identifies entities by UUID in row_pk, so ids alone pin
      ;; down the rows to scrub — immune to table renames in table_name.
      (jdbc/execute! tx
                     ["CREATE TEMP TABLE purge_audit_targets ON COMMIT DROP AS
                       SELECT id::text AS row_id FROM maps WHERE owner_id = ?
                       UNION
                       SELECT id::text FROM map_metadata
                        WHERE map_id IN (SELECT id FROM maps WHERE owner_id = ?)
                       UNION
                       SELECT id::text FROM parts
                        WHERE map_id IN (SELECT id FROM maps WHERE owner_id = ?)
                       UNION
                       SELECT id::text FROM relationships
                        WHERE map_id IN (SELECT id FROM maps WHERE owner_id = ?)
                       UNION
                       SELECT id::text FROM sessions
                        WHERE map_id IN (SELECT id FROM maps WHERE owner_id = ?)"
                      user-uuid user-uuid user-uuid user-uuid user-uuid])
      ;; Resolve the email before the users row is deleted: invitations and
      ;; waitlist_signups are keyed by email, not user-id.
      (let [email (:email (jdbc/execute-one!
                           tx
                           ["SELECT email FROM users WHERE id = ?" user-uuid]
                           {:builder-fn rs/as-unqualified-maps}))]
        (when email
          (jdbc/execute! tx ["DELETE FROM invitations WHERE email = ?" email])
          (jdbc/execute! tx ["DELETE FROM waitlist_signups WHERE email = ?" email])))
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
      ;; Sessions carry clinical trigger text and sit outside the
      ;; bitemporal machinery — deleted deliberately here (ADR-0014);
      ;; their activation links go with them via the sessions FK
      ;; cascade. NOT mirrored in entity.map/delete-impl!: a
      ;; soft-deleted Map keeps its Sessions, restorable.
      (jdbc/execute! tx
                     ["DELETE FROM sessions
                       WHERE map_id IN (SELECT id FROM maps WHERE owner_id = ?)"
                      user-uuid])
      (jdbc/execute! tx
                     ["DELETE FROM maps WHERE owner_id = ?" user-uuid])
      (jdbc/execute! tx
                     ["UPDATE audit_log a
                       SET before_row = NULL, after_row = NULL
                       FROM purge_audit_targets t
                       WHERE a.row_pk->>'id' = t.row_id
                         AND (a.before_row IS NOT NULL
                              OR a.after_row IS NOT NULL)"])
      (db/update! :audit_log
                  {:actor_id [:cast (str tombstone-id) :uuid]}
                  [:= :actor_id user-uuid]
                  tx)
      (db/update! :users
                  {:deletion_completed_at [:now]}
                  [:= :id user-uuid]
                  tx)
      (jdbc/execute! tx ["DELETE FROM policy_acceptances WHERE user_id = ?" user-uuid])
      (jdbc/execute! tx ["DELETE FROM users WHERE id = ?" user-uuid]))
    (mulog/log ::purge-account-complete :user-id user-id)
    {:purged true :user-id user-id}))
