(ns aps.parts.billing
  "Concierge billing — operator REPL tooling for account standing.

   The concierge launch bills out of band: the operator sends a Stripe
   invoice by hand, and once it clears moves the account's
   `paid_through_date` forward. These helpers are that move. Standing is
   *recorded* here, not *enforced* — gating access on standing is left to
   a later task.

   Operator workflow (production REPL):

     (billing-status!)                      ; standing of every account
     (set-paid-through! email)              ; paid one more month from today
     (set-paid-through! email \"2027-05-22\") ; paid through an explicit date
     (clear-paid-through! email)            ; back to never-paid (NULL)"
  (:require
   [aps.parts.db :as db]
   [aps.parts.db.erasure :as erasure]
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

(defn set-paid-through!
  "Record an account as paid through a date; return its updated billing
   summary, or nil if no account has that `email`.

   Two arities for the monthly concierge billing cycle:
   - `(set-paid-through! email)`      — paid through one month from today
   - `(set-paid-through! email date)` — paid through an explicit date: a
     `java.time.LocalDate` or an ISO-8601 string like \"2027-05-22\"."
  ([email]
   (set-paid-through! email (.plusMonths (LocalDate/now) 1)))
  ([email date]
   (let [paid-through (->local-date date)]
     (if-let [row (first (db/update! :users
                                     {:paid_through_date paid-through}
                                     [:= :email email]))]
       (do (mulog/log ::paid-through-set :email email :paid-through (str paid-through))
           (println (str "Set " email " paid through " paid-through))
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
                 :where  [:not= :id [:cast (str erasure/tombstone-id) :uuid]]}))
        lines (sort-by (juxt (comp status-rank :status) :email)
                       (map #(->status-line % today) rows))]
    (doseq [{:keys [email paid_through_date is_founding_circle status]} lines]
      (println (format "%-32s %-12s %-11s %s"
                       email
                       (str (or paid_through_date "—"))
                       (name status)
                       (if is_founding_circle "founding-circle" ""))))
    lines))
