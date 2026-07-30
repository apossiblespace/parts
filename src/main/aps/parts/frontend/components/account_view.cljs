(ns aps.parts.frontend.components.account-view
  "Pure view logic for the Account page's Billing card. Deliberately free
   of uix/React so it can be unit-tested directly under the cljs suite —
   the `account` component is a thin shell over `billing-view`."
  (:require
   [clojure.string :as str]))

(def ^:private month-names
  ["January" "February" "March" "April" "May" "June" "July"
   "August" "September" "October" "November" "December"])

(defn- fmt-iso-date
  "Render an ISO `YYYY-MM-DD` string as e.g. `8 July 2026` by splitting the
   string — never via `js/Date`, so a date-only value can't drift a day
   across timezones. Returns nil for anything that doesn't parse."
  [iso]
  (when iso
    (let [[y m d] (str/split iso #"-")
          mi      (when m (dec (js/parseInt m 10)))]
      (when (and y mi d (<= 0 mi 11))
        (str (js/parseInt d 10) " " (nth month-names mi) " " y)))))

(defn- standing-message
  "Plain-language good-standing line from the server's `:standing` summary
   (see `aps.parts.billing/account-standing`). Returns nil when the summary
   isn't loaded yet, so the caller can show a placeholder."
  [{:keys [status days_remaining paid_through_date]}]
  (let [through (fmt-iso-date paid_through_date)]
    (case status
      :paid       (cond
                    (nil? days_remaining)
                    (str "Your subscription is active through " through ".")

                    (zero? days_remaining)
                    (str "Your subscription is active through the end of today (" through ").")

                    :else
                    (str "Your subscription is active for another "
                         days_remaining (if (= 1 days_remaining) " day" " days")
                         " — through " through "."))
      :overdue    (str "Your subscription lapsed on " through ".")
      :never-paid "We don't have a renewal date on file for your account yet."
      nil)))

(defn billing-view
  "View-model for the Billing card, from the server's `:billing` facts and
   `:standing` summary. `:action` is what the card offers:

   - `:loading` — the account record hasn't loaded yet (the login response
     carries no `:billing`; the mount-time check-auth refresh fills it in)
   - `:manage` — a live subscription exists: the Customer Portal button
   - `:subscribe` — self-serve is enabled and there's no live
     subscription; a cancelled subscriber lands here again, so there is
     always a path back in
   - `:none` — self-serve is off (concierge-only hosts)

   `:standing-line` is the good-standing sentence, except that the
   never-paid line is dropped when subscribe buttons are shown — the beta
   pitch says it better."
  [{:keys [billing standing]}]
  (if (nil? billing)
    {:action :loading :standing-line nil}
    (let [action (cond
                   ;; A live status implies a linked customer — the server
                   ;; only ever writes the two together.
                   (:subscription_active billing) :manage
                   (:self_serve_enabled billing)  :subscribe
                   :else                          :none)]
      {:action        action
       :standing-line (when-not (and (= :subscribe action)
                                     (= :never-paid (:status standing)))
                        (standing-message standing))})))
