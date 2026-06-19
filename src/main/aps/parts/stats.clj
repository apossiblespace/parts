(ns aps.parts.stats
  "Operator REPL tooling for fleet- and account-level figures — the helpers
   you reach for in the production REPL to answer \"what's going on with this
   account?\" and \"how are we doing overall?\".

   Mirrors `aps.parts.billing`: small helpers that `println` a readable
   report *and* return the same data as a map, so they're useful both at a
   glance and as a value to drill into.

   Operator workflow (production REPL):

     (user-stats! \"jane@example.com\")   ; one account, by email
     (user-stats! \"0000-…-uuid\")        ; or by id (e.g. an audit actor_id)
     (fleet-stats!)                       ; the whole fleet at a glance

   Two definitions are shared with the rest of the system rather than
   redefined here:
   - billing standing comes from `billing/account-standing`.
   - 'currently exists' for the bitemporal tables comes from
     `bt/count-current` — this namespace never touches temporal SQL itself
     (enforced by the architecture-fitness test)."
  (:require
   [aps.parts.billing :as billing]
   [aps.parts.db :as db]
   [aps.parts.db.bitemporal :as bt]
   [aps.parts.db.erasure :as erasure])
  (:import
   (java.time LocalDate ZoneOffset)
   (java.util UUID)))

;; -- Per-user --------------------------------------------------------------

(defn- ->uuid-or-nil
  "Parse `x` as a UUID, or nil if it isn't one. An email never parses, so this
   cleanly routes `user-stats!` between an id lookup and an email lookup."
  [x]
  (try (UUID/fromString (str x)) (catch Exception _ nil)))

(defn- find-user
  "The `users` row for an email or id, with the columns the report needs, or
   nil if no such account."
  [email-or-id]
  (let [uuid (->uuid-or-nil email-or-id)]
    (db/query-one
     (db/sql-format
      {:select [:id :email :display_name :created_at
                :is_founding_circle :paid_through_date]
       :from   [:users]
       :where  (if uuid [:= :id uuid] [:= :email email-or-id])}))))

(defn- owner-maps
  "Subquery selecting the ids of the User's currently-existing Maps. Used to
   scope the bitemporal Part/Relationship counts to one owner — Parts carry
   only a `map_id`, never an owner."
  [owner-id]
  {:select [:id]
   :from   [:maps]
   :where  [:and [:= :owner_id owner-id] [:= :deleted_at nil]]})

(defn- count-maps
  "Current Maps (soft-delete: `deleted_at IS NULL`) matching optional `where`.
   Counted off the `maps` identity table, not the bitemporal layer. nil `where`
   counts the whole fleet's Maps."
  [where]
  (-> (db/query-one
       (db/sql-format
        {:select [[[:count :*] :c]]
         :from   [:maps]
         :where  (into [:and [:= :deleted_at nil]] (when where [where]))}))
      :c))

(defn- last-active
  "Most recent moment `actor-id` made a change — `MAX(audit_log.occurred_at)`
   — or nil if they never have. The single activity signal (see CONTEXT.md,
   'Active user'): edits, not app opens. Returned as an `OffsetDateTime` (UTC)
   — JDBC hands a `timestamptz` back as a `java.sql.Timestamp`, which we don't
   want to leak to the operator."
  [actor-id]
  (when-let [ts (:t (db/query-one
                     (db/sql-format
                      {:select [[[:max :occurred_at] :t]]
                       :from   [:audit_log]
                       :where  [:= :actor_id actor-id]})))]
    (-> ^java.sql.Timestamp ts .toInstant (.atOffset ZoneOffset/UTC))))

(defn- counts-for [owner-id]
  (let [maps (owner-maps owner-id)]
    {:maps          (count-maps [:= :owner_id owner-id])
     :parts         (bt/count-current db/datasource :parts [:in :map_id maps])
     :relationships (bt/count-current db/datasource :relationships [:in :map_id maps])}))

(defn- yes-no [b] (if b "yes" "no"))

(defn- fmt-billing [{:keys [status days_remaining]}]
  (case status
    :paid       (str "paid (" days_remaining " days left)")
    :overdue    (str "overdue (" (- days_remaining) " days)")
    :never-paid "never-paid"))

(defn- print-user-report
  [{:keys [id email display_name created_at is_founding_circle
           billing last_active counts]}]
  (println (format "%s  (%s)" email display_name))
  (println (format "  id            %s" id))
  (println (format "  created       %s" created_at))
  (println (format "  founding      %s" (yes-no is_founding_circle)))
  (println (format "  billing       %s" (fmt-billing billing)))
  (println (format "  last active   %s" (or last_active "never")))
  (println (format "  maps %d   parts %d   relationships %d"
                   (:maps counts) (:parts counts) (:relationships counts))))

(defn user-stats!
  "Print and return a single account's standing. `email-or-id` is an email or
   a user id (auto-detected). Returns nil — printing 'No account found' — when
   no account matches.

   The returned map:

     {:id :email :display_name :created_at :is_founding_circle
      :billing     {:status :paid_through_date :days_remaining}  ; billing/account-standing
      :last_active <OffsetDateTime or nil>
      :counts      {:maps :parts :relationships}}                ; current rows only"
  [email-or-id]
  (if-let [u (find-user email-or-id)]
    (let [report (assoc (select-keys u [:id :email :display_name
                                        :created_at :is_founding_circle])
                        :billing     (billing/account-standing u)
                        :last_active (last-active (:id u))
                        :counts      (counts-for (:id u)))]
      (print-user-report report)
      report)
    (do (println (str "No account found for " email-or-id))
        nil)))

;; -- Fleet -----------------------------------------------------------------

(defn- fleet-users
  "Every real account (tombstone excluded), with just the columns the fleet
   figures need. The fleet is small (concierge launch), so totals, founding,
   pending and the billing breakdown are all folded from this one pass rather
   than several COUNT queries; the heavier per-entity counts stay in SQL."
  []
  (db/query
   (db/sql-format
    {:select [:paid_through_date :is_founding_circle :deletion_requested_at]
     :from   [:users]
     :where  (erasure/exclude-tombstone :id)})))

(defn- billing-breakdown
  "Frequencies of billing standing across `users`, reusing
   `billing/account-standing` so there's one definition of paid/overdue/
   never-paid. `today` is read once and shared so every account is classified
   at the same instant."
  [users]
  (let [today (LocalDate/now)
        f     (frequencies (map #(:status (billing/account-standing % today)) users))]
    {:paid       (get f :paid 0)
     :overdue    (get f :overdue 0)
     :never_paid (get f :never-paid 0)}))

(defn- active-count
  "Distinct Users (tombstone excluded) with any activity in the last
   `interval` — a rolling window ending now. `interval` is a Postgres
   interval literal body, e.g. \"24 hours\" or \"7 days\"."
  [interval]
  (-> (db/query-one
       (db/sql-format
        {:select [[[:count [:distinct :actor_id]] :c]]
         :from   [:audit_log]
         :where  [:and
                  [:>= :occurred_at [:- [:now] [:raw (str "interval '" interval "'")]]]
                  (erasure/exclude-tombstone :actor_id)]}))
      :c))

(defn- pct
  "`count` as a percentage of `total`, rounded to one decimal. 0.0 when the
   fleet is empty (no division by zero)."
  [count total]
  (if (zero? total)
    0.0
    (/ (Math/round (* (/ count (double total)) 1000.0)) 10.0)))

(defn- print-fleet-report
  [{:keys [users active totals founding_circle billing]}]
  (println (format "Fleet: %d users  (%d pending deletion,  %d founding)"
                   (:total users) (:pending_deletion users) founding_circle))
  (println (format "  active 24h    %d  (%.1f%%)"
                   (-> active :last_24h :count) (-> active :last_24h :pct)))
  (println (format "  active 7d     %d  (%.1f%%)"
                   (-> active :last_7d :count) (-> active :last_7d :pct)))
  (println (format "  totals        maps %d   parts %d   relationships %d"
                   (:maps totals) (:parts totals) (:relationships totals)))
  (println (format "  billing       paid %d   overdue %d   never-paid %d"
                   (:paid billing) (:overdue billing) (:never_paid billing))))

(defn fleet-stats!
  "Print and return the whole fleet at a glance.

     {:users   {:total :pending_deletion}        ; tombstone excluded; pending still counted
      :active  {:last_24h {:count :pct}           ; distinct actors in a rolling window
                :last_7d  {:count :pct}}
      :totals  {:maps :parts :relationships}      ; current rows across all owners
      :founding_circle <n>
      :billing {:paid :overdue :never_paid}}      ; reuses billing/account-standing

   'Active' means *made a change* in the window (see CONTEXT.md, 'Active
   user') — edits, not app opens. Purged accounts have no `users` row and
   their past activity is re-attributed to the tombstone, so they fall out
   of every figure automatically."
  []
  (let [users  (fleet-users)
        total  (count users)
        a24    (active-count "24 hours")
        a7     (active-count "7 days")
        report {:users           {:total            total
                                  :pending_deletion (count (filter :deletion_requested_at users))}
                :active          {:last_24h {:count a24 :pct (pct a24 total)}
                                  :last_7d  {:count a7 :pct (pct a7 total)}}
                :totals          {:maps          (count-maps nil)
                                  :parts         (bt/count-current db/datasource :parts)
                                  :relationships (bt/count-current db/datasource :relationships)}
                :founding_circle (count (filter :is_founding_circle users))
                :billing         (billing-breakdown users)}]
    (print-fleet-report report)
    report))
