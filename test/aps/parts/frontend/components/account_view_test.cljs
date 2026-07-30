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
  ;; Same shape as enabled-unused — a cancelled subscriber is simply
  ;; someone with no live subscription; the fixture keeps the scenario
  ;; named in the tests.
  {:self_serve_enabled true :subscription_active false})

(def ^:private concierge-only
  {:self_serve_enabled false :subscription_active false})

(deftest billing-view-action-test
  (testing "a live subscription is offered the portal"
    (is (= :manage (:action (billing-view {:billing  enabled-subscribed
                                           :standing paid})))))

  (testing "self-serve enabled but unused offers the subscribe buttons"
    (is (= :subscribe (:action (billing-view {:billing  enabled-unused
                                              :standing never-paid})))))

  (testing "a cancelled subscription is offered subscribing again, not a
            portal for a subscription that no longer exists"
    (is (= :subscribe (:action (billing-view {:billing  enabled-cancelled
                                              :standing paid})))))

  (testing "a concierge-only host offers nothing"
    (is (= :none (:action (billing-view {:billing  concierge-only
                                         :standing never-paid})))))

  (testing "an account record without billing facts is still loading —
            the login response carries none"
    (is (= :loading (:action (billing-view {:billing nil :standing nil}))))
    (is (= :loading (:action (billing-view {}))))))

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

  (testing "a cancelled-but-paid account reads: active through X, plus
            the subscribe offer"
    (let [{:keys [action standing-line]}
          (billing-view {:billing enabled-cancelled :standing paid})]
      (is (= :subscribe action))
      (is (str/includes? standing-line "8 September 2026"))))

  (testing "a subscriber keeps their standing line"
    (is (str/includes? (:standing-line (billing-view {:billing  enabled-subscribed
                                                      :standing paid}))
                       "8 September 2026")))

  (testing "a concierge-only host keeps the never-paid line"
    (is (some? (:standing-line (billing-view {:billing  concierge-only
                                              :standing never-paid}))))))
