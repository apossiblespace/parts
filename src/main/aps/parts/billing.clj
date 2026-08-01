(ns aps.parts.billing
  "Billing standing: operator REPL tooling, and the storage vocabulary the
   self-serve layer (`aps.parts.api.billing`) writes through — every read
   or write of the `users` billing columns lives here, so \"which columns
   are the billing columns\" has one home — with one deliberate
   exception: `erasure/purge-account!`'s refuse-while-linked guard reads
   the link column itself, because a guard must not depend on the caller
   it defends against. Erasure's Stripe step
   (`release-stripe-customer!`) lives here too: the one outbound Stripe
   call outside the API layer.

   Billing is self-serve (TASK-046): the Stripe webhook is the ordinary
   writer of `paid_through_date`. The REPL helpers below are operator
   *adjustment* tools — goodwill extensions, corrections, and the
   abuse-response claw-back — not a parallel billing lane (the concierge
   hand-invoicing workflow was retired 2026-07-31). Standing is
   *recorded* here, not *enforced* — gating access on standing is left to
   a later task.

   Operator adjustments (production REPL):

     (billing-status!)                      ; standing of every account
     (set-paid-through! email \"2027-05-22\") ; extend to an explicit date
     (clear-paid-through! email)            ; back to never-paid (NULL)

   The paid-through date is monotonic: the webhook and `set-paid-through!`
   only ever move it forward, so an adjustment can't silently erase paid
   months. A deliberate claw-back (refund, abuse) is the explicit
   two-step `clear-paid-through!` then `set-paid-through!`."
  (:require
   [aps.parts.config :as config]
   [aps.parts.db :as db]
   [aps.parts.db.erasure :as erasure]
   [aps.parts.stripe :as stripe]
   [com.brunobonacci.mulog :as mulog])
  (:import
   (java.time LocalDate)
   (java.time.temporal ChronoUnit)))

(defn- ->local-date
  "Coerce a date-ish value to a `java.time.LocalDate`: a LocalDate passes
   through, a `java.sql.Date` (how JDBC hands back a DATE column) is
   converted, and an ISO-8601 string such as \"2027-05-22\" is parsed.
   `nil` passes through."
  [d]
  (cond
    (nil? d)                    nil
    (instance? LocalDate d)     d
    (instance? java.sql.Date d) (.toLocalDate ^java.sql.Date d)
    (string? d)                 (LocalDate/parse d)
    :else (throw (ex-info "Cannot read value as a date"
                          {:type :invalid-date :value d}))))

(defn- standing
  "Date-only billing standing of a `paid-through` value as of `today`:
   `:never-paid` when unset, `:overdue` when in the past, else `:paid`.
   Deliberately ignores `is_founding_circle` — `billing-status!` surfaces
   that flag separately for the operator to weigh."
  [paid-through today]
  (if-let [^LocalDate d (->local-date paid-through)]
    (if (.isBefore d today) :overdue :paid)
    :never-paid))

(defn account-standing
  "Good-standing summary for a user `row` (which must carry
   `:paid_through_date`), shaped for the account page and served as part of
   `GET /api/account`. Returns:

     {:status            :never-paid | :paid | :overdue
      :paid_through_date \"YYYY-MM-DD\" or nil
      :days_remaining    whole days from `today` until paid-through, or nil}

   `:days_remaining` is 0 on the final paid day and goes negative once
   overdue; the page reads `:status` for the wording and `:days_remaining`
   for the count. Date-only on purpose (see `standing`) so the answer can't
   drift with clock time or timezone, and `:paid_through_date` is a plain
   ISO string for the same reason — the client renders it without ever
   reinterpreting it as an instant. Founding-circle is intentionally not
   considered, mirroring `standing`.

   The single-arity form reads `today` from the system clock, like the
   operator helpers; the two-arity form takes `today` for testability."
  ([row] (account-standing row (LocalDate/now)))
  ([row today]
   (let [d (->local-date (:paid_through_date row))]
     {:status            (standing (:paid_through_date row) today)
      :paid_through_date (some-> d str)
      :days_remaining    (some->> d (.between ChronoUnit/DAYS today))})))

(defn- ->status-line
  "Operator-facing billing summary for a user `row` as of `today`. Carries
   only billing fields — never the password hash or other account data."
  [row today]
  {:email              (:email row)
   :paid_through_date  (->local-date (:paid_through_date row))
   :is_founding_circle (:is_founding_circle row)
   :status             (standing (:paid_through_date row) today)})

(def ^:private status-rank
  "Report sort order — accounts that need attention come first."
  {:never-paid 0 :overdue 1 :paid 2})

(defn- extend-paid-through-set
  "The `:set` map for a monotonic paid-through move: GREATEST in SQL, so
   the date never travels backwards and concurrent writers (two webhook
   deliveries, webhook + operator) can't interleave a stale read."
  [^LocalDate date]
  {:paid_through_date [:greatest [:coalesce :paid_through_date date] date]})

(defn- coerce-paid-through
  "An updated row with its `:paid_through_date` as a LocalDate, so the
   JDBC DATE type never escapes this namespace. nil passes through."
  [row]
  (some-> row (update :paid_through_date ->local-date)))

(defn extend-paid-through!
  "Move an account's `paid_through_date` forward to `date`, never
   backwards — an account already paid further ahead keeps its later date.
   Returns the updated row (`:paid_through_date` as a LocalDate), or nil
   when no account has that `user-id`."
  [user-id date]
  (coerce-paid-through
   (first (db/update! :users
                      (extend-paid-through-set (->local-date date))
                      [:= :id (db/->uuid user-id)]))))

(defn extend-paid-through-for-customer!
  "The same monotonic move, keyed by the Stripe customer link — the
   webhook's one-statement path for renewal invoices. Returns the updated
   row (`:paid_through_date` as a LocalDate), or nil when no account is
   linked to `customer` — the webhook's signal that the invoice matches
   nothing we know."
  [customer date]
  (coerce-paid-through
   (first (db/update! :users
                      (extend-paid-through-set (->local-date date))
                      [:= :stripe_customer_id customer]))))

(defn record-subscription-status!
  "Record the linked account's live Stripe subscription status — and,
   when known, which plan it is on (plan switches arrive this way). UI
   facts (subscribe vs manage, the renewal amount), never billing inputs.
   Returns the updated row, or nil when no account is linked to
   `customer`."
  [customer status plan]
  (first (db/update! :users
                     (cond-> {:stripe_subscription_status status}
                       plan (assoc :stripe_plan plan))
                     [:= :stripe_customer_id customer])))

(defn record-checkout!
  "One atomic row move for a completed self-serve Checkout: link the
   Stripe customer, record the subscription status, and — when the paid
   period's end is known — extend paid-through, monotonically like every
   other paid-through move. Returns the updated row (`:paid_through_date`
   as a LocalDate), or nil when no account has that `user-id`."
  [user-id customer status plan period-end]
  (coerce-paid-through
   (first (db/update! :users
                      (cond-> {:stripe_customer_id         customer
                               :stripe_subscription_status status
                               :stripe_plan                plan}
                        period-end (merge (extend-paid-through-set
                                           (->local-date period-end))))
                      [:= :id (db/->uuid user-id)]))))

(defn billing-facts
  "The billing columns the API layer reads for an account — checkout
   guard, portal lookup, and the Account page's `:billing` booleans all
   go through this one projection. Returns nil when no account has that
   `user-id`."
  [user-id]
  (coerce-paid-through
   (db/query-one
    (db/sql-format {:select [:email :stripe_customer_id
                             :stripe_subscription_status :stripe_plan
                             :paid_through_date]
                    :from   [:users]
                    :where  [:= :id (db/->uuid user-id)]}))))

(defn release-stripe-customer!
  "Erasure step for an account's Stripe link (TASK-108), run *before* the
   DB purge: delete the linked Stripe Customer, which immediately cancels
   its subscriptions — no charge may outlive the account. Stripe retains
   the customer's past invoices as the financial records it is required
   to keep; the identity leaves our customer list, which is the erasure
   stance this codebase commits to.

   Ordering is the safety: a Stripe failure here throws, the caller
   aborts the purge, and the next attempt retries with the link still on
   record — never a purged row with a live subscription nobody can find.
   A 404 (customer already deleted) counts as released. A linked account
   on a host with no Stripe config cannot call Stripe: that is logged as
   an operator work item and deliberately does not block erasure."
  [user-id]
  (when-let [customer (:stripe_customer_id (billing-facts user-id))]
    ;; Only the secret key is needed — a host mid-rotation on the webhook
    ;; secret or price ids must still release, not silently degrade to
    ;; "no Stripe at all" and purge the only pointer to a live customer.
    (if-let [secret-key (config/stripe-secret-key)]
      (if (stripe/delete-customer-if-present! {:secret-key secret-key} customer)
        (mulog/log ::stripe-customer-released :user-id user-id :customer customer)
        (mulog/log ::stripe-customer-already-gone :user-id user-id :customer customer))
      (mulog/log ::stripe-link-remains
                 :user-id user-id
                 :customer customer))
    ;; Clearing the columns is what arms `erasure/purge-account!`'s
    ;; refuse-while-linked guard; a Stripe throw above leaves them set.
    (db/update! :users
                {:stripe_customer_id         nil
                 :stripe_subscription_status nil
                 :stripe_plan                nil}
                [:= :id (db/->uuid user-id)])
    nil))

(defn set-paid-through!
  "Extend an account's paid-through to an explicit `date` — a
   `java.time.LocalDate` or an ISO-8601 string like \"2027-05-22\".
   Returns the updated billing summary, or nil if no account has that
   `email`.

   Never moves the date backwards (see the namespace docstring): recording
   a shorter period than the account already has keeps the later date and
   says so. Claw a date back deliberately with `clear-paid-through!` first."
  ([email date]
   (let [paid-through (->local-date date)]
     (if-let [row (first (db/update! :users
                                     (extend-paid-through-set paid-through)
                                     [:= :email email]))]
       (let [recorded (->local-date (:paid_through_date row))]
         (mulog/log ::paid-through-set
                    :email email
                    :requested (str paid-through)
                    :recorded (str recorded))
         (if (.isAfter ^LocalDate recorded paid-through)
           (println (str email " is already paid through " recorded
                         " — date not moved backwards"
                         " (clear-paid-through! first to claw back)"))
           (println (str "Set " email " paid through " recorded)))
         (->status-line row (LocalDate/now)))
       (do (println (str "No account found for " email))
           nil)))))

(defn clear-paid-through!
  "Clear an account's `paid_through_date` back to NULL (never-paid); return
   its updated billing summary, or nil if no account has that `email`."
  [email]
  (if-let [row (first (db/update! :users
                                  {:paid_through_date nil}
                                  [:= :email email]))]
    (do (mulog/log ::paid-through-cleared :email email)
        (println (str "Cleared paid-through date for " email))
        (->status-line row (LocalDate/now)))
    (do (println (str "No account found for " email))
        nil)))

(defn billing-status!
  "Print and return the billing standing of every account — email,
   paid-through date, Founding Circle flag, and a date-only status
   (:never-paid / :overdue / :paid). Accounts needing attention sort
   first. What a Founding Circle member's standing *means* is the
   operator's call; this report only reports."
  []
  (let [today (LocalDate/now)
        rows  (db/query
               (db/sql-format
                {:select [:email :paid_through_date :is_founding_circle]
                 :from   [:users]
                 :where  (erasure/exclude-tombstone :id)}))
        lines (sort-by (juxt (comp status-rank :status) :email)
                       (map #(->status-line % today) rows))]
    (doseq [{:keys [email paid_through_date is_founding_circle status]} lines]
      (println (format "%-32s %-12s %-11s %s"
                       email
                       (str (or paid_through_date "—"))
                       (name status)
                       (if is_founding_circle "founding-circle" ""))))
    lines))
