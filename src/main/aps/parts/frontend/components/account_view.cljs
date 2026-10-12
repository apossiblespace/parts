(ns aps.parts.frontend.components.account-view
  "Pure view logic for the Account page's Billing section. Deliberately free
   of uix/React so it can be unit-tested directly under the cljs suite —
   the `account` component is a thin shell over `billing-view`."
  (:require
   [aps.parts.common.constants :as c]
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

(defn- plan-for
  "The shared plan entry for a Stripe plan word (\"monthly\"), or nil."
  [plan]
  (some #(when (= (:plan %) (keyword plan)) %) c/subscription-plans))

(defn- facts
  "The short facts the Billing rows show for `action`. Subscribed states
   name the plan, a badge and the next date; the others carry an optional
   `:status` — a badge and one sentence about past paid time — above the
   plan rows. A badge is `[tone label]`, tone :success, :warning or
   :neutral. An account paid by hand, with no Stripe subscription, gets
   no \"Cancelled\" badge: nothing was cancelled."
  [action billing {:keys [status paid_through_date]}]
  (let [plan    (plan-for (:subscription_plan billing))
        through (fmt-iso-date paid_through_date)
        named   (if plan (str (:title plan) " · " (:price plan) (:cadence plan)) "Subscription")]
    (case action
      :manage     {:plan         named
                   :badge        [:success "Active"]
                   :next-payment (when through
                                   (if plan (str (:price plan) " on " through) through))}
      :cancelling {:plan    named
                   :badge   [:warning "Ending"]
                   :ends-on through}
      (:resubscribe :none)
      (let [badge (when (:subscription_cancelled billing) [:neutral "Cancelled"])]
        (case status
          :paid    {:status {:badge  badge
                             :detail (str "Paid until " through ". You won't be charged again.")}}
          :overdue {:status {:badge  [:neutral "Ended"]
                             :detail (str "Your subscription ended on " through ".")}}
          (when badge
            {:status {:badge badge :detail "You won't be charged again."}})))
      nil)))

(defn billing-view
  "View-model for the Billing section, from the server's `:billing` facts
   and `:standing` summary. `:action` is what the section offers:

   - `:loading` — the account record hasn't loaded yet (the login response
     carries no `:billing`; the mount-time check-auth refresh fills it in)
   - `:manage` — a live subscription exists: the Customer Portal button
   - `:cancelling` — cancelled at period end: still live and reversible
     in the portal (so the Manage button stays), but the rows say the
     window ends and no charge is coming
   - `:activating` — just back from a completed Checkout
     (`checkout-pending?`) but the webhook hasn't landed yet: no buttons.
     Subscribe would contradict the payment that just went through, and
     Manage would mint a portal session the backend still refuses. The
     page polls until the server reports the subscription live.
   - `:resubscribe` — no live subscription but there is history to
     explain (cancelled, or a paid/lapsed window): a status row says what
     happened and the plan buttons read Resubscribe
   - `:subscribe` — self-serve is enabled and there's nothing to explain
   - `:none` — self-serve is off

   The facts for the rows are merged in (see `facts`); `:cta` is the
   plan buttons' label."
  [{:keys [billing standing checkout-pending?]}]
  (if (nil? billing)
    {:action :loading}
    (let [history? (or (:subscription_cancelled billing)
                       (contains? #{:paid :overdue} (:status standing)))
          action   (cond
                     ;; Pending cancellation outranks plain active: the page
                     ;; must not promise a renewal that will never come.
                     (:subscription_cancelling billing) :cancelling

                     ;; A live status implies a linked customer — the server
                     ;; only ever writes the two together.
                     (:subscription_active billing) :manage

                     (and checkout-pending?
                          (:self_serve_enabled billing)) :activating

                     (and history?
                          (:self_serve_enabled billing)) :resubscribe

                     (:self_serve_enabled billing) :subscribe
                     :else                         :none)]
      (merge {:action action
              :cta    (if (= :resubscribe action) "Resubscribe" "Subscribe")}
             (facts action billing standing)))))
