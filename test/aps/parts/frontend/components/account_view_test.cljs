(ns aps.parts.frontend.components.account-view-test
  (:require
   [aps.parts.frontend.components.account-view :refer [billing-view]]
   [cljs.test :refer-macros [deftest is testing]]))

(def ^:private never-paid
  {:status :never-paid :paid_through_date nil :days_remaining nil})

(def ^:private paid
  {:status :paid :paid_through_date "2026-08-29" :days_remaining 29})

(def ^:private overdue
  {:status :overdue :paid_through_date "2026-06-01" :days_remaining -30})

(def ^:private enabled-unused
  {:self_serve_enabled true :subscription_active false})

(def ^:private enabled-subscribed
  {:self_serve_enabled     true
   :subscription_active    true
   :subscription_cancelled false
   :subscription_plan      "monthly"})

(def ^:private enabled-cancelled
  {:self_serve_enabled true :subscription_active false :subscription_cancelled true})

(def ^:private enabled-cancelling
  {:self_serve_enabled      true
   :subscription_active     true
   :subscription_cancelling true
   :subscription_plan       "monthly"})

(def ^:private self-serve-off
  {:self_serve_enabled false :subscription_active false :subscription_cancelled false})

(deftest billing-view-action-test
  (testing "a live subscription is offered the portal"
    (is (= :manage (:action (billing-view {:billing  enabled-subscribed
                                           :standing paid})))))

  (testing "no history at all offers the subscribe cards"
    (is (= :subscribe (:action (billing-view {:billing  enabled-unused
                                              :standing never-paid})))))

  (testing "a cancelled subscription is the resubscribe state"
    (is (= :resubscribe (:action (billing-view {:billing  enabled-cancelled
                                                :standing paid}))))
    (is (= :resubscribe (:action (billing-view {:billing  enabled-cancelled
                                                :standing never-paid})))))

  (testing "time paid by hand, never a subscription, is offered Subscribe —
            nothing to resubscribe to"
    (is (= :subscribe (:action (billing-view {:billing  enabled-unused
                                              :standing paid}))))
    (is (= :subscribe (:action (billing-view {:billing  enabled-unused
                                              :standing overdue})))))

  (testing "a host with self-serve off offers nothing"
    (is (= :none (:action (billing-view {:billing  self-serve-off
                                         :standing never-paid})))))

  (testing "still loading — the login response carries no billing facts"
    (is (= :loading (:action (billing-view {:billing nil :standing nil}))))
    (is (= :loading (:action (billing-view {})))))

  (testing "a pending cancellation outranks plain active — still managed,
            never promised a renewal"
    (is (= :cancelling (:action (billing-view {:billing  enabled-cancelling
                                               :standing paid})))))

  (testing "resubscribing takes the activating path"
    (is (= :activating (:action (billing-view {:billing           enabled-cancelled
                                               :standing          paid
                                               :checkout-pending? true}))))))

(deftest billing-view-cta-test
  (testing "the cards' button reads Resubscribe when there is history"
    (is (= "Resubscribe" (:cta (billing-view {:billing  enabled-cancelled
                                              :standing paid}))))
    (is (= "Subscribe" (:cta (billing-view {:billing  enabled-unused
                                            :standing never-paid}))))))

(deftest test-billing-view-facts
  (testing "a live subscription names the plan and the next charge — amount
            from the shared plan constants, date from the paid-through window"
    (is (= {:plan         "Monthly · £15/month"
            :badge        [:success "Active"]
            :next-payment "£15 on 29 August 2026"}
           (select-keys (billing-view {:billing enabled-subscribed :standing paid})
                        [:plan :badge :next-payment]))))

  (testing "an unknown plan still names the date, just without an amount"
    (let [view (billing-view {:billing  (dissoc enabled-subscribed :subscription_plan)
                              :standing paid})]
      (is (= "Subscription" (:plan view)))
      (is (= "29 August 2026" (:next-payment view)))))

  (testing "cancel-at-period-end says when it ends, never a renewal"
    (let [view (billing-view {:billing enabled-cancelling :standing paid})]
      (is (= [:warning "Ending"] (:badge view)))
      (is (= "29 August 2026" (:ends-on view)))
      (is (nil? (:next-payment view)))))

  (testing "no paid-through date yet: no half sentence"
    (is (nil? (:next-payment (billing-view {:billing  enabled-subscribed
                                            :standing never-paid})))))

  (testing "a cancelled subscription with paid time left: paid until, no
            further charges"
    (is (= {:badge  [:neutral "Cancelled"]
            :detail "Paid until 29 August 2026. You won’t be charged again."}
           (:status (billing-view {:billing enabled-cancelled :standing paid})))))

  (testing "paid by hand, with no Stripe subscription: no Cancelled badge"
    (is (= {:badge  nil
            :detail "Paid until 29 August 2026. You won’t be charged again."}
           (:status (billing-view {:billing enabled-unused :standing paid})))))

  (testing "a lapsed window reads ended, naming what ended"
    (is (= {:badge [:neutral "Ended"] :detail "Your subscription ended on 1 June 2026."}
           (:status (billing-view {:billing enabled-cancelled :standing overdue}))))
    (is (= {:badge [:neutral "Ended"] :detail "Your paid time ended on 1 June 2026."}
           (:status (billing-view {:billing enabled-unused :standing overdue})))))

  (testing "cancelled before anything was paid still explains itself"
    (is (= {:badge [:neutral "Cancelled"] :detail "You won’t be charged again."}
           (:status (billing-view {:billing enabled-cancelled :standing never-paid})))))

  (testing "a self-serve-off host gets the same facts"
    (is (= "Paid until 29 August 2026. You won’t be charged again."
           (get-in (billing-view {:billing self-serve-off :standing paid}) [:status :detail]))))

  (testing "nothing to report: no status"
    (is (nil? (:status (billing-view {:billing enabled-unused :standing never-paid}))))
    (is (nil? (:status (billing-view {:billing self-serve-off :standing never-paid}))))
    (is (nil? (:status (billing-view {:billing           enabled-unused
                                      :standing          never-paid
                                      :checkout-pending? true}))))))

(deftest test-billing-view-entitlement
  (let [today "2026-11-11"]
    (testing "a failing payment replaces Active and the next payment"
      (let [view (billing-view {:billing     enabled-subscribed
                                :standing    paid
                                :entitlement {:can_edit true :reason :subscribed :payment_failing true}})]
        (is (= [:warning "Payment failed"] (:badge view)))
        (is (:payment-failed? view))
        (is (nil? (:next-payment view)))))

    (testing "the free trial: days left and the end date"
      (is (= {:days-left 7 :ends-on "18 November 2026"}
             (:trial (billing-view {:billing     enabled-unused
                                    :standing    never-paid
                                    :today       today
                                    :entitlement {:can_edit true :reason :trial :trial_ends_on "2026-11-18"}})))))

    (testing "a stale trial past its last day reads 0 days, never negative"
      (is (= 0 (get-in (billing-view {:billing     enabled-unused
                                      :standing    never-paid
                                      :today       "2026-11-20"
                                      :entitlement {:can_edit true :reason :trial :trial_ends_on "2026-11-18"}})
                       [:trial :days-left]))))

    (testing "read-only: the section shows the lapsed message and the plans"
      (let [view (billing-view {:billing     enabled-cancelled
                                :standing    overdue
                                :today       today
                                :entitlement {:can_edit false :reason :lapsed}})]
        (is (:lapsed? view))
        (is (= "Resubscribe" (:cta view)))))

    (testing "the beta banner only while no payment is needed at all"
      (is (:beta-banner? (billing-view {:billing     enabled-unused
                                        :standing    never-paid
                                        :entitlement {:can_edit true :reason :not-required}})))
      (is (not (:beta-banner? (billing-view {:billing     enabled-unused
                                             :standing    never-paid
                                             :today       today
                                             :entitlement {:can_edit      true         :reason :trial
                                                           :trial_ends_on "2026-11-18"}})))))

    (testing "a live subscription carries no trial or lapse facts"
      (let [view (billing-view {:billing     enabled-subscribed
                                :standing    paid
                                :today       today
                                :entitlement {:can_edit true :reason :subscribed}})]
        (is (nil? (:trial view)))
        (is (nil? (:lapsed? view)))))))
