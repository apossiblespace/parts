(ns aps.parts.frontend.components.entitlement-view-test
  (:require
   [aps.parts.frontend.components.entitlement-view :as ev]
   [cljs.test :refer-macros [deftest is testing]]))

(defn- trial-user [ends-on]
  {:entitlement {:can_edit true :reason :trial :trial_ends_on ends-on :payment_failing false}
   :standing    {:status :never-paid}})

(deftest test-days-left
  (testing "whole UTC days, 0 on the last day"
    (is (= 0 (ev/days-left "2026-11-18" "2026-11-18")))
    (is (= 1 (ev/days-left "2026-11-18" "2026-11-17")))
    (is (= 31 (ev/days-left "2026-11-18" "2026-10-18")))
    (is (= 2 (ev/days-left "2026-03-30" "2026-03-28")) "across a clock change")))

(deftest test-trial-phrase
  (is (= "today" (ev/trial-phrase "2026-11-18" "2026-11-18")))
  (is (= "tomorrow, 18 November" (ev/trial-phrase "2026-11-18" "2026-11-17")))
  (is (= "in 7 days, on 18 November" (ev/trial-phrase "2026-11-18" "2026-11-11"))))

(deftest test-list-notice
  (testing "the trial banner starts 7 days before the end, not earlier"
    (is (nil? (ev/list-notice (trial-user "2026-11-18") "2026-11-10")))
    (is (= {:kind :trial-ending :when "in 7 days, on 18 November"}
           (ev/list-notice (trial-user "2026-11-18") "2026-11-11"))))

  (testing "a stale record past the trial's last day shows nothing, never
            \"in -1 days\""
    (is (nil? (ev/list-notice (trial-user "2026-11-18") "2026-11-19"))))

  (testing "a failing payment shows while the account can still edit"
    (is (= {:kind :payment-failed}
           (ev/list-notice {:entitlement {:can_edit true :reason :subscribed :payment_failing true}}
                           "2026-12-01"))))

  (testing "read-only outranks a failing payment"
    (is (= :lapsed
           (:kind (ev/list-notice {:entitlement {:can_edit false :reason :lapsed :payment_failing true}}
                                  "2026-12-01")))))

  (testing "nothing while subscribed, without a start date, or still loading"
    (is (nil? (ev/list-notice {:entitlement {:can_edit true :reason :subscribed}} "2026-12-01")))
    (is (nil? (ev/list-notice {:entitlement {:can_edit true :reason :not-required}} "2026-12-01")))
    (is (nil? (ev/list-notice nil "2026-12-01")))))

(deftest test-lapse-headlines
  (testing "says what ended"
    (is (= "Your free trial has ended."
           (ev/lapse-headline {:standing {:status :never-paid}})))
    (is (= "Your subscription has ended."
           (ev/lapse-headline {:billing {:subscription_cancelled true} :standing {:status :overdue}})))
    (is (= "Your paid time has ended."
           (ev/lapse-headline {:standing {:status :overdue}}))))
  (testing "the refused-save sentence"
    (is (= "Your free trial has ended, so your last change wasn’t saved."
           (ev/refused-headline {:standing {:status :never-paid}})))))

(deftest test-lapsed?
  (is (true? (ev/lapsed? {:entitlement {:can_edit false}})))
  (is (false? (ev/lapsed? {:entitlement {:can_edit true}})))
  (is (false? (ev/lapsed? nil)) "a loading record is not read-only"))
