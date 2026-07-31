(ns aps.parts.frontend.components.account-view-test
  (:require
   [aps.parts.frontend.components.account-view :refer [billing-view]]
   [cljs.test :refer-macros [deftest is testing]]
   [clojure.string :as str]))

(def ^:private never-paid
  {:status :never-paid :paid_through_date nil :days_remaining nil})

(def ^:private paid
  {:status :paid :paid_through_date "2026-09-08" :days_remaining 30})

(def ^:private enabled-unused
  {:self_serve_enabled true :subscription_active false})

(def ^:private enabled-subscribed
  {:self_serve_enabled true :subscription_active true})

(def ^:private enabled-cancelled
  {:self_serve_enabled true :subscription_active false :subscription_cancelled true})

(def ^:private concierge-only
  {:self_serve_enabled false :subscription_active false})

(deftest billing-view-action-test
  (testing "a live subscription is offered the portal"
    (is (= :manage (:action (billing-view {:billing  enabled-subscribed
                                           :standing paid})))))

  (testing "self-serve enabled but unused offers the subscribe buttons"
    (is (= :subscribe (:action (billing-view {:billing  enabled-unused
                                              :standing never-paid})))))

  (testing "a cancelled subscription is offered the way back in — with the
            cancelled state named, never a portal for a subscription that
            no longer exists"
    (is (= :resubscribe (:action (billing-view {:billing  enabled-cancelled
                                                :standing paid})))))

  (testing "a concierge-only host offers nothing"
    (is (= :none (:action (billing-view {:billing  concierge-only
                                         :standing never-paid})))))

  (testing "an account record without billing facts is still loading —
            the login response carries none"
    (is (= :loading (:action (billing-view {:billing nil :standing nil}))))
    (is (= :loading (:action (billing-view {}))))))

(deftest billing-view-activating-test
  (testing "back from Checkout before the webhook lands: no buttons, just
            the activating state — subscribe would contradict the payment,
            manage would mint a portal session the backend still refuses"
    (is (= :activating (:action (billing-view {:billing           enabled-unused
                                               :standing          never-paid
                                               :checkout-pending? true})))))

  (testing "once the webhook lands the real subscribed state wins"
    (is (= :manage (:action (billing-view {:billing           enabled-subscribed
                                           :standing          paid
                                           :checkout-pending? true})))))

  (testing "the stale never-paid line is suppressed while activating"
    (is (nil? (:standing-line (billing-view {:billing           enabled-unused
                                             :standing          never-paid
                                             :checkout-pending? true})))))

  (testing "a concierge-only host never activates (self-serve is off)"
    (is (= :none (:action (billing-view {:billing           concierge-only
                                         :standing          never-paid
                                         :checkout-pending? true})))))

  (testing "resubscribing takes the activating path, not the cancelled one"
    (is (= :activating (:action (billing-view {:billing           enabled-cancelled
                                               :standing          paid
                                               :checkout-pending? true}))))))

(deftest billing-view-standing-line-test
  (testing "the never-paid line is dropped when subscribe buttons show —
            the beta pitch covers it"
    (is (nil? (:standing-line (billing-view {:billing  enabled-unused
                                             :standing never-paid})))))

  (testing "an account paid ahead by concierge keeps its standing line
            alongside the subscribe buttons"
    (let [{:keys [action standing-line]}
          (billing-view {:billing enabled-unused :standing paid})]
      (is (= :subscribe action))
      (is (str/includes? standing-line "8 September 2026"))))

  (testing "a cancelled-but-paid account says cancelled, names the date the
            paid window ends, and promises no further charges"
    (let [{:keys [action standing-line]}
          (billing-view {:billing enabled-cancelled :standing paid})]
      (is (= :resubscribe action))
      (is (str/includes? standing-line "cancelled"))
      (is (str/includes? standing-line "8 September 2026"))
      (is (str/includes? standing-line "won't be charged again"))))

  (testing "a cancelled account whose paid window has run out gets the short
            form"
    (let [{:keys [standing-line]}
          (billing-view {:billing  enabled-cancelled
                         :standing {:status         :overdue :paid_through_date "2026-06-01"
                                    :days_remaining -30}})]
      (is (str/includes? standing-line "cancelled"))
      (is (not (str/includes? standing-line "keeps working")))))

  (testing "a subscriber keeps their standing line"
    (is (str/includes? (:standing-line (billing-view {:billing  enabled-subscribed
                                                      :standing paid}))
                       "8 September 2026")))

  (testing "a concierge-only host keeps the never-paid line"
    (is (some? (:standing-line (billing-view {:billing  concierge-only
                                              :standing never-paid}))))))
