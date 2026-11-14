(ns aps.parts.billing-test
  (:require
   [aps.parts.billing :as billing]
   [aps.parts.config :as conf]
   [aps.parts.db :as db]
   [aps.parts.db.erasure :as erasure]
   [aps.parts.helpers.utils :refer [create-test-user! silently
                                    stripe-api-error with-test-db]]
   [aps.parts.stripe :as stripe]
   [clojure.test :refer [deftest is testing use-fixtures]])
  (:import
   (java.time LocalDate)))

(use-fixtures :once with-test-db)

(defn- stripe-link [email]
  (:stripe_customer_id
   (db/query-one (db/sql-format {:select [:stripe_customer_id]
                                 :from   [:users]
                                 :where  [:= :email email]}))))

(defn- paid-through-date [email]
  (:paid_through_date
   (db/query-one (db/sql-format {:select [:paid_through_date]
                                 :from   [:users]
                                 :where  [:= :email email]}))))

(deftest account-standing-test
  (let [today (LocalDate/parse "2026-06-15")]
    (testing "paid: whole days remaining until the paid-through date"
      (is (= {:status :paid :paid_through_date "2026-07-08" :days_remaining 23}
             (billing/account-standing {:paid_through_date "2026-07-08"} today))))

    (testing "paid: zero days remaining on the final paid day"
      (is (= {:status :paid :paid_through_date "2026-06-15" :days_remaining 0}
             (billing/account-standing {:paid_through_date "2026-06-15"} today))))

    (testing "overdue: a past date is overdue with a negative day count"
      (is (= {:status :overdue :paid_through_date "2026-05-01" :days_remaining -45}
             (billing/account-standing {:paid_through_date "2026-05-01"} today))))

    (testing "never-paid: an unset date carries nils"
      (is (= {:status :never-paid :paid_through_date nil :days_remaining nil}
             (billing/account-standing {:paid_through_date nil} today))))))

(deftest standing-test
  (let [start    (LocalDate/parse "2026-11-18")
        beta     {:created_at (java.sql.Timestamp/from (java.time.Instant/parse "2026-08-01T09:00:00Z"))}
        standing (fn [row today] (billing/operator-standing row start (LocalDate/parse today)))]
    (testing "a trial counts the days to its last day"
      (is (= {:kind :trial :days 5} (standing beta "2026-11-13")))
      (is (= {:kind :trial :days 0} (standing beta "2026-11-18"))))
    (testing "a subscriber counts the days to the end of the paid period"
      (is (= {:kind :subscribed :plan "yearly" :days 10}
             (standing (assoc beta :stripe_subscription_status "active" :stripe_plan "yearly"
                              :paid_through_date "2026-12-01")
                       "2026-11-21"))))
    (testing "a past due subscription reads payment failing"
      (is (= :payment-failing
             (:kind (standing (assoc beta :stripe_subscription_status "past_due") "2026-12-01")))))
    (testing "paid time reads paid with a Stripe link and comp without one"
      (let [paid (assoc beta :paid_through_date "2026-12-31")]
        (is (= {:kind :paid :days 30} (standing (assoc paid :stripe_customer_id "cus_1") "2026-12-01")))
        (is (= {:kind :comp :days 30} (standing paid "2026-12-01")))))
    (testing "a lapsed account gives its first read-only day"
      (is (= {:kind :lapsed :since (LocalDate/parse "2026-11-19")} (standing beta "2026-12-01")))
      (is (= {:kind :lapsed :since (LocalDate/parse "2026-12-11")}
             (standing (assoc beta :paid_through_date "2026-12-10") "2026-12-20"))))
    (testing "without a payment start date every account is free"
      (is (= {:kind :free} (billing/operator-standing beta nil (LocalDate/parse "2026-11-13")))))))

(deftest entitlement-test
  (let [start  (LocalDate/parse "2026-11-18")
        at     (fn [created] (java.sql.Timestamp/from (java.time.Instant/parse created)))
        beta   {:created_at (at "2026-08-01T09:00:00Z")}
        reason (fn [row today] (:reason (billing/entitlement row start (LocalDate/parse today))))]
    (testing "before the payment start date every account can edit"
      (is (= {:can_edit true :reason :trial :trial_ends_on "2026-11-18" :payment_failing false}
             (billing/entitlement beta start (LocalDate/parse "2026-11-17")))))

    (testing "a beta account's trial ends on the start date, its last free day"
      (is (= :trial (reason beta "2026-11-18")))
      (is (= :lapsed (reason beta "2026-11-19"))))

    (testing "an account created near the start date still gets 30 days"
      (let [late {:created_at (at "2026-11-10T23:30:00Z")}]
        (is (= "2026-12-10" (:trial_ends_on (billing/entitlement late start start))))
        (is (= :trial (reason late "2026-12-10")))
        (is (= :lapsed (reason late "2026-12-11")))))

    (testing "a live subscription can edit, whatever the trial"
      (doseq [status ["active" "trialing" "canceling" "past_due"]]
        (is (= :subscribed (reason (assoc beta :stripe_subscription_status status) "2027-03-01"))
            status)))

    (testing "paid time left can edit; the day after it ends, the account lapses"
      (let [paid (assoc beta :stripe_subscription_status "canceled" :paid_through_date "2027-02-01")]
        (is (= :paid (reason paid "2027-02-01")))
        (is (= :lapsed (reason paid "2027-02-02")))))

    (testing "unpaid (Stripe stopped retrying) lapses, and the payment is failing"
      (is (= {:can_edit false :reason :lapsed :trial_ends_on "2026-11-18" :payment_failing true}
             (billing/entitlement (assoc beta :stripe_subscription_status "unpaid")
                                  start (LocalDate/parse "2027-03-01")))))

    (testing "past due still edits, and tells the account the payment is failing"
      (is (:payment_failing (billing/entitlement (assoc beta :stripe_subscription_status "past_due")
                                                 start (LocalDate/parse "2027-03-01")))))

    (testing "without a payment start date no account needs to pay"
      (is (= {:can_edit true :reason :not-required :trial_ends_on nil :payment_failing false}
             (billing/entitlement beta nil (LocalDate/parse "2030-01-01")))))))

(deftest set-paid-through!-test
  (testing "an explicit ISO date string is recorded on the account"
    (create-test-user! {:email "explicit@example.com"})
    (let [result (silently #(billing/set-paid-through! "explicit@example.com" "2027-05-22"))]
      (is (= "2027-05-22" (str (paid-through-date "explicit@example.com"))))
      (is (= :paid (:status result)))))

  (testing "returns nil when no account has that email"
    (is (nil? (silently #(billing/set-paid-through! "ghost@example.com" "2027-01-01")))))

  (testing "never moves an account's date backwards — an adjustment
            after a self-serve year keeps the later date"
    (create-test-user! {:email "paid-ahead@example.com"})
    (silently #(billing/set-paid-through! "paid-ahead@example.com" "2027-08-01"))
    (let [result (silently #(billing/set-paid-through! "paid-ahead@example.com" "2026-09-01"))]
      (is (= "2027-08-01" (str (paid-through-date "paid-ahead@example.com"))))
      (is (= "2027-08-01" (str (:paid_through_date result))))))

  (testing "clear-paid-through! then set is the deliberate claw-back path"
    (create-test-user! {:email "clawback@example.com"})
    (silently #(billing/set-paid-through! "clawback@example.com" "2027-08-01"))
    (silently #(billing/clear-paid-through! "clawback@example.com"))
    (silently #(billing/set-paid-through! "clawback@example.com" "2026-08-01"))
    (is (= "2026-08-01" (str (paid-through-date "clawback@example.com"))))))

(deftest extend-paid-through!-test
  (testing "extends a never-paid account and returns the updated row"
    (let [user (create-test-user! {:email "extend-fresh@example.com"})
          row  (billing/extend-paid-through! (:id user) "2026-12-01")]
      (is (= "2026-12-01" (str (paid-through-date "extend-fresh@example.com"))))
      (is (some? (:paid_through_date row)))))

  (testing "moves forward but never backwards"
    (let [user (create-test-user! {:email "extend-mono@example.com"})]
      (billing/extend-paid-through! (:id user) "2026-12-01")
      (billing/extend-paid-through! (:id user) "2027-06-01")
      (is (= "2027-06-01" (str (paid-through-date "extend-mono@example.com"))))
      (billing/extend-paid-through! (:id user) "2026-01-01")
      (is (= "2027-06-01" (str (paid-through-date "extend-mono@example.com"))))))

  (testing "returns nil when no account has that id"
    (is (nil? (billing/extend-paid-through! (random-uuid) "2027-01-01")))))

(deftest release-stripe-customer!-test
  (testing "deletes the linked Stripe customer before erasure"
    (let [user     (create-test-user! {:email "release@example.com"})
          captured (atom nil)]
      (db/update! :users {:stripe_customer_id "cus_release"} [:= :id (:id user)])
      (with-redefs [conf/stripe-secret-key  (constantly "rk_test_key")
                    stripe/delete-customer! (fn [_cfg customer]
                                              (reset! captured customer)
                                              {:id customer :deleted true})]
        (billing/release-stripe-customer! (:id user)))
      (is (= "cus_release" @captured))
      (is (nil? (stripe-link "release@example.com"))
          "release must clear the link, arming the purge guard")))

  (testing "no-ops for an account with no Stripe link"
    (let [user     (create-test-user! {:email "unlinked-release@example.com"})
          captured (atom nil)]
      (with-redefs [conf/stripe-secret-key  (constantly "rk_test_key")
                    stripe/delete-customer! (fn [_ customer] (reset! captured customer))]
        (billing/release-stripe-customer! (:id user)))
      (is (nil? @captured))))

  (testing "a customer already gone on Stripe's side (404) counts as released"
    (let [user (create-test-user! {:email "gone-release@example.com"})]
      (db/update! :users {:stripe_customer_id "cus_gone"} [:= :id (:id user)])
      (with-redefs [conf/stripe-secret-key  (constantly "rk_test_key")
                    stripe/delete-customer! (fn [_ _] (throw (stripe-api-error 404)))]
        (is (nil? (billing/release-stripe-customer! (:id user)))))
      (is (nil? (stripe-link "gone-release@example.com")))))

  (testing "any other Stripe failure propagates so the purge aborts and retries"
    (let [user (create-test-user! {:email "flaky-release@example.com"})]
      (db/update! :users {:stripe_customer_id "cus_flaky"} [:= :id (:id user)])
      (with-redefs [conf/stripe-secret-key  (constantly "rk_test_key")
                    stripe/delete-customer! (fn [_ _] (throw (stripe-api-error 500)))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Stripe API error"
                              (billing/release-stripe-customer! (:id user)))))
      (is (= "cus_flaky" (stripe-link "flaky-release@example.com"))
          "an aborted release must keep the link on record for the retry")))

  (testing "a linked account on a host without Stripe config does not block
            erasure — the stranded link is the operator's work item"
    (let [user     (create-test-user! {:email "unconfigured-release@example.com"})
          captured (atom nil)]
      (db/update! :users {:stripe_customer_id "cus_unconf"} [:= :id (:id user)])
      (with-redefs [conf/stripe-secret-key  (constantly nil)
                    stripe/delete-customer! (fn [_ customer] (reset! captured customer))]
        (billing/release-stripe-customer! (:id user)))
      (is (nil? @captured))
      (is (nil? (stripe-link "unconfigured-release@example.com"))
          "the logged work item is the pointer; erasure proceeds unlinked"))))

(deftest clear-paid-through!-test
  (testing "clears a recorded date back to NULL"
    (create-test-user! {:email "clear-me@example.com"})
    (silently #(billing/set-paid-through! "clear-me@example.com" "2027-05-22"))
    (let [result (silently #(billing/clear-paid-through! "clear-me@example.com"))]
      (is (nil? (paid-through-date "clear-me@example.com")))
      (is (= :never-paid (:status result)))))

  (testing "returns nil when no account has that email"
    (is (nil? (silently #(billing/clear-paid-through! "ghost@example.com"))))))

(deftest billing-status!-test
  (testing "classifies accounts as never-paid, overdue, and paid"
    (create-test-user! {:email "never@example.com"})
    (create-test-user! {:email "overdue@example.com"})
    (create-test-user! {:email "paid-up@example.com"})
    (db/update! :users {:paid_through_date (.minusDays (LocalDate/now) 1)}
                [:= :email "overdue@example.com"])
    (db/update! :users {:paid_through_date (.plusYears (LocalDate/now) 1)}
                [:= :email "paid-up@example.com"])
    (let [status-of (into {} (map (juxt :email :status)) (silently billing/billing-status!))]
      (is (= :never-paid (status-of "never@example.com")))
      (is (= :overdue (status-of "overdue@example.com")))
      (is (= :paid (status-of "paid-up@example.com")))))

  (testing "surfaces the Founding Circle flag"
    (create-test-user! {:email "founder@example.com"})
    (db/update! :users {:is_founding_circle true} [:= :email "founder@example.com"])
    (let [line-of (into {} (map (juxt :email identity)) (silently billing/billing-status!))]
      (is (true? (:is_founding_circle (line-of "founder@example.com"))))
      (is (false? (:is_founding_circle (line-of "never@example.com"))))))

  (testing "excludes the tombstone user — it is not a real account"
    (let [tombstone-email (:email (db/query-one
                                   (db/sql-format
                                    {:select [:email]
                                     :from   [:users]
                                     :where  [:= :id [:cast (str erasure/tombstone-id) :uuid]]})))
          emails          (set (map :email (silently billing/billing-status!)))]
      (is (some? tombstone-email) "sanity: the tombstone user exists in the test db")
      (is (not (contains? emails tombstone-email))))))
