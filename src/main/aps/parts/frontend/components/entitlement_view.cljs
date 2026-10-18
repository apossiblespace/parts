(ns aps.parts.frontend.components.entitlement-view
  "Pure view logic for the trial and read-only notices (ADR-0021): the
   Map's lapsed strip, the Maps list banner and the Billing section's
   trial row. It reads the account record the server sends (`:entitlement`,
   `:standing`, `:billing`). Free of uix/React, for the cljs test suite.
   The wording is in `copy.billing`."
  (:require
   [aps.parts.frontend.copy.billing :as copy]))

(def ^:private month-names
  ["January" "February" "March" "April" "May" "June" "July"
   "August" "September" "October" "November" "December"])

(def trial-reminder-days
  "The Maps list shows the trial banner from this many days before the
   trial ends."
  7)

(defn- iso->utc-ms [iso]
  (let [[y m d] (map #(js/parseInt % 10) (.split iso "-"))]
    (js/Date.UTC y (dec m) d)))

(defn today
  "Today's UTC date as `YYYY-MM-DD` — the day the server counts in."
  []
  (subs (.toISOString (js/Date.)) 0 10))

(defn days-left
  "Whole days from `today` to the ISO date `ends-on`: 0 on the last day."
  [ends-on today]
  (js/Math.round (/ (- (iso->utc-ms ends-on) (iso->utc-ms today)) 86400000)))

(defn- parse-iso
  "[year month-index day] of an ISO `YYYY-MM-DD` date, by splitting the
   string — never via `js/Date`, so a date-only value cannot drift a day
   across time zones. nil for anything that does not parse."
  [iso]
  (when (string? iso)
    (let [[y m d] (.split iso "-")
          mi      (when m (dec (js/parseInt m 10)))]
      (when (and y d (<= 0 mi 11))
        [y mi (js/parseInt d 10)]))))

(defn- day-month
  "An ISO date as \"18 November\", or nil."
  [iso]
  (when-let [[_ mi d] (parse-iso iso)]
    (str d " " (nth month-names mi))))

(defn long-date
  "An ISO date as \"8 July 2026\", or nil."
  [iso]
  (when-let [[y mi d] (parse-iso iso)]
    (str d " " (nth month-names mi) " " y)))

(defn lapsed?
  "True when the account may no longer edit. False while the account
   record is still loading (no `:entitlement` yet): the server is the
   guard, and a loading page must not flash read-only."
  [user]
  (false? (get-in user [:entitlement :can_edit])))

(defn- lapse-subject
  "What ended: the trial, a subscription, or time paid by hand."
  [user]
  (cond
    (get-in user [:billing :subscription_cancelled])  copy/subscription-subject
    (= :never-paid (get-in user [:standing :status])) copy/trial-subject
    :else                                             copy/paid-time-subject))

(defn lapse-headline
  "Why the account is read-only, in one sentence."
  [user]
  (copy/ended (lapse-subject user)))

(defn refused-headline
  "The sentence after the server refused a change because the account
   has lapsed."
  [user]
  (copy/ended-change-refused (lapse-subject user)))

(defn trial-phrase
  "\"in 3 days, on 18 November\" — how far off the trial's end is."
  [ends-on today]
  (copy/trial-when (days-left ends-on today) (day-month ends-on)))

(defn list-notice
  "The Maps list banner for `user` on `today`, or nil: `:lapsed`, a
   failing payment (still editing), or the trial ending within
   `trial-reminder-days`."
  [user today]
  (let [{:keys [reason trial_ends_on payment_failing]} (:entitlement user)]
    (cond
      (lapsed? user)
      {:kind :lapsed :headline (lapse-headline user)}

      payment_failing
      {:kind :payment-failed}

      ;; A negative count is a stale record (the tab outlived the trial's
      ;; last day): say nothing rather than \"in -1 days\".
      (and (= :trial reason) trial_ends_on
           (<= 0 (days-left trial_ends_on today) trial-reminder-days))
      {:kind :trial-ending :when (trial-phrase trial_ends_on today)}

      :else nil)))
