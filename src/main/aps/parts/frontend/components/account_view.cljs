(ns aps.parts.frontend.components.account-view
  "Pure view logic for the Account page's Billing section. Deliberately free
   of uix/React so it can be unit-tested directly under the cljs suite —
   the `account` component is a thin shell over `billing-view`."
  (:require
   [aps.parts.common.constants :as c]
   [aps.parts.frontend.components.entitlement-view :as entitlement-view]
   [aps.parts.frontend.copy.billing :as copy]))

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
   no \"Cancelled\" badge: nothing was cancelled. A failing payment
   (Stripe is retrying) replaces Active and the next payment."
  [action billing {:keys [status paid_through_date]} payment-failing?]
  (let [plan    (plan-for (:subscription_plan billing))
        through (entitlement-view/long-date paid_through_date)
        named   (if plan (str (:title plan) " · " (:price plan) (:cadence plan)) copy/subscription-fallback)]
    (case action
      :manage     (if payment-failing?
                    {:plan            named
                     :badge           [:warning copy/badge-payment-failed]
                     :payment-failed? true}
                    {:plan         named
                     :badge        [:success copy/badge-active]
                     :next-payment (when through
                                     (if plan (copy/next-payment (:price plan) through) through))})
      :cancelling {:plan    named
                   :badge   [:warning copy/badge-ending]
                   :ends-on through}
      (:subscribe :resubscribe :none)
      (let [badge (when (:subscription_cancelled billing) [:neutral copy/badge-cancelled])]
        (case status
          :paid    {:status {:badge  badge
                             :detail (copy/paid-until through)}}
          :overdue {:status {:badge  [:neutral copy/badge-ended]
                             :detail (copy/ended-on (if badge copy/subscription-subject copy/paid-time-subject)
                                                    through)}}
          (when badge
            {:status {:badge badge :detail copy/not-charged-again}})))
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
   - `:resubscribe` — a subscription was cancelled: a status row says
     what happened and the plan buttons read Resubscribe
   - `:subscribe` — self-serve is enabled and there's nothing to explain
   - `:none` — self-serve is off

   The facts for the rows are merged in (see `facts`); `:cta` is the
   plan buttons' label. From the `entitlement` (ADR-0021), on the
   subscribe states: `:lapsed?` (read-only: the section shows one message
   and the plans), `:trial` {:days-left :ends-on} during the free trial,
   and `:beta-banner?` when no payment is needed at all. `today` is a UTC
   ISO date."
  [{:keys [billing standing entitlement checkout-pending? today]}]
  (if (nil? billing)
    {:action :loading}
    (let [action (cond
                   ;; Pending cancellation outranks plain active: the page
                   ;; must not promise a renewal that will never come.
                   (:subscription_cancelling billing) :cancelling

                   ;; A live status implies a linked customer — the server
                   ;; only ever writes the two together.
                   (:subscription_active billing) :manage

                   (and checkout-pending?
                        (:self_serve_enabled billing)) :activating

                   (and (:subscription_cancelled billing)
                        (:self_serve_enabled billing)) :resubscribe

                   (:self_serve_enabled billing) :subscribe
                   :else                         :none)]
      (merge {:action action
              :cta    (if (= :resubscribe action) copy/resubscribe copy/subscribe)}
             (facts action billing standing (:payment_failing entitlement))
             (when (#{:subscribe :resubscribe} action)
               (let [{:keys [reason can_edit trial_ends_on]} entitlement]
                 (cond
                   (false? can_edit)
                   {:lapsed? true}

                   (and (= :trial reason) trial_ends_on today)
                   {:trial {:days-left (max 0 (entitlement-view/days-left trial_ends_on today))
                            :ends-on   (entitlement-view/long-date trial_ends_on)}}

                   (= :not-required reason)
                   {:beta-banner? true})))))))
