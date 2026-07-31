(ns aps.parts.frontend.components.account-view-test
  (:require
   [aps.parts.frontend.components.account-view :refer [billing-view]]
   [cljs.test :refer-macros [deftest is testing]]
   [clojure.string :as str]))

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

  (testing "any paid or lapsed window without a live subscription is the
            resubscribe state — regardless of how the cancellation was
            recorded, it must never read as simply active over the cards"
    (is (= :resubscribe (:action (billing-view {:billing  enabled-cancelled
                                                :standing paid}))))
    (is (= :resubscribe (:action (billing-view {:billing  enabled-unused
                                                :standing paid}))))
    (is (= :resubscribe (:action (billing-view {:billing  enabled-unused
                                                :standing overdue}))))
    (is (= :resubscribe (:action (billing-view {:billing  enabled-cancelled
                                                :standing never-paid})))))

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

(deftest billing-view-status-line-test
  (testing "a live subscription names the next charge — amount from the
            shared plan constants, date from the paid-through window"
    (let [{:keys [tone headline body]}
          (:status-line (billing-view {:billing enabled-subscribed :standing paid}))]
      (is (= :success tone))
      (is (= "Subscription active." headline))
      (is (= " You will be next charged £15 on 29 August 2026." body))))

  (testing "an unknown plan still names the date, just without an amount"
    (let [{:keys [body]}
          (:status-line (billing-view {:billing  (dissoc enabled-subscribed :subscription_plan)
                                       :standing paid}))]
      (is (= " You will be next charged on 29 August 2026." body))))

  (testing "cancel-at-period-end reads will-expire with the way back via
            the manage button, not the cards"
    (let [{:keys [tone headline body]}
          (:status-line (billing-view {:billing enabled-cancelling :standing paid}))]
      (is (= :warning tone))
      (is (= "Subscription will expire" headline))
      (is (str/includes? body "You will not be charged again. Changed your mind?"))
      (is (str/includes? body "renew your subscription below"))))

  (testing "a paid window with no live subscription reads: will expire,
            no further charges, resubscribe below — the exact promise"
    (let [{:keys [tone headline body]}
          (:status-line (billing-view {:billing enabled-cancelled :standing paid}))]
      (is (= :warning tone))
      (is (= "Subscription will expire" headline))
      (is (= (str " in 29 days (29 August 2026). You will not be charged again."
                  " If you wish to continue using Parts after 29 August 2026,"
                  " please resubscribe below.")
             body))))

  (testing "a lapsed window reads expired"
    (let [{:keys [tone headline body]}
          (:status-line (billing-view {:billing enabled-unused :standing overdue}))]
      (is (= :error tone))
      (is (= "Subscription expired" headline))
      (is (str/starts-with? body " on 1 June 2026. You will not be charged again."))
      (is (str/includes? body "resubscribe below"))))

  (testing "cancelled before anything was paid still explains itself"
    (let [{:keys [tone headline]}
          (:status-line (billing-view {:billing enabled-cancelled :standing never-paid}))]
      (is (= :warning tone))
      (is (= "Subscription cancelled" headline))))

  (testing "activating reads finalising"
    (is (= {:tone :info :headline "Finalising your subscription" :body "…"}
           (:status-line (billing-view {:billing           enabled-unused
                                        :standing          never-paid
                                        :checkout-pending? true})))))

  (testing "a self-serve-off host gets the facts without the resubscribe
            invitation — there are no cards to point at"
    (let [{:keys [headline body]}
          (:status-line (billing-view {:billing self-serve-off :standing paid}))]
      (is (= "Subscription will expire" headline))
      (is (not (str/includes? body "resubscribe")))))

  (testing "nothing to report: no line"
    (is (nil? (:status-line (billing-view {:billing enabled-unused :standing never-paid}))))
    (is (nil? (:status-line (billing-view {:billing self-serve-off :standing never-paid}))))
    (is (nil? (:status-line (billing-view {:billing nil :standing nil}))))))
