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

(defn- cancelled-message
  "The line for a cancelled subscription. The paid window survives a
   cancellation (billing decision 7), so while it lasts the message names
   the date; once it has run out, just the fact."
  [{:keys [status paid_through_date]}]
  (if-let [through (and (= :paid status) (fmt-iso-date paid_through_date))]
    (str "You've cancelled your subscription. Parts keeps working until "
         through ", and you won't be charged again.")
    "You've cancelled your subscription — you won't be charged again."))

(defn- subscription-status
  "The at-a-glance state for the Billing section's status dot:
   {:tone :success|:info|:warning|:error|:neutral, :label \"…\"}. The
   tone maps to a daisyUI status colour in the component. Accounts
   extended by hand (no self-serve subscription) read as active too —
   the dot reflects standing, not how it was paid."
  [action {:keys [status]}]
  (case action
    :manage      {:tone :success :label "Active"}
    :activating  {:tone :info :label "Finalising"}
    :resubscribe {:tone :warning :label "Cancelled"}
    (case status
      :paid    {:tone :success :label "Active"}
      :overdue {:tone :error :label "Lapsed"}
      {:tone :neutral :label "No subscription"})))

(defn billing-view
  "View-model for the Billing card, from the server's `:billing` facts and
   `:standing` summary. `:action` is what the card offers:

   - `:loading` — the account record hasn't loaded yet (the login response
     carries no `:billing`; the mount-time check-auth refresh fills it in)
   - `:manage` — a live subscription exists: the Customer Portal button
   - `:activating` — just back from a completed Checkout
     (`checkout-pending?`) but the webhook hasn't landed yet: no buttons.
     Subscribe would contradict the payment that just went through, and
     Manage would mint a portal session the backend still refuses. The
     page polls until the server reports the subscription live.
   - `:resubscribe` — the subscription was cancelled: the line says so
     (cancelled, works until X, no further charges) and the subscribe
     buttons offer the way back in — never \"active\" over a Subscribe CTA
   - `:subscribe` — self-serve is enabled and there's no subscription
     history to explain
   - `:none` — self-serve is off

   `:standing-line` is the good-standing sentence, except that the
   never-paid line is dropped when subscribe buttons are shown (the beta
   pitch says it better) or while activating (it lags the payment), and a
   cancelled subscription gets its own line in place of one that would
   claim the subscription is active."
  [{:keys [billing standing checkout-pending?]}]
  (if (nil? billing)
    {:action :loading :standing-line nil}
    (let [action (cond
                   ;; A live status implies a linked customer — the server
                   ;; only ever writes the two together.
                   (:subscription_active billing) :manage

                   (and checkout-pending?
                        (:self_serve_enabled billing)) :activating

                   (and (:subscription_cancelled billing)
                        (:self_serve_enabled billing)) :resubscribe

                   (:self_serve_enabled billing) :subscribe
                   :else                         :none)]
      {:action        action
       :standing-line (cond
                        (= :resubscribe action)
                        (cancelled-message standing)

                        (and (contains? #{:subscribe :activating} action)
                             (= :never-paid (:status standing)))
                        nil

                        :else (standing-message standing))
       :status        (subscription-status action standing)})))
