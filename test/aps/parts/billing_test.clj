(ns aps.parts.billing-test
  (:require
   [aps.parts.billing :as billing]
   [aps.parts.db :as db]
   [aps.parts.db.erasure :as erasure]
   [aps.parts.helpers.utils :refer [create-test-user! with-test-db]]
   [clojure.test :refer [deftest is testing use-fixtures]])
  (:import
   (java.time LocalDate)))

(use-fixtures :once with-test-db)

(defn- silently
  "Run `f`, discarding anything it prints to stdout. Returns f's value."
  [f]
  (binding [*out* (java.io.StringWriter.)]
    (f)))

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

(deftest set-paid-through!-test
  (testing "an explicit ISO date string is recorded on the account"
    (create-test-user! {:email "explicit@example.com"})
    (let [result (silently #(billing/set-paid-through! "explicit@example.com" "2027-05-22"))]
      (is (= "2027-05-22" (str (paid-through-date "explicit@example.com"))))
      (is (= :paid (:status result)))))

  (testing "the no-date arity records one month from today"
    (create-test-user! {:email "monthly@example.com"})
    (let [result (silently #(billing/set-paid-through! "monthly@example.com"))]
      (is (= (.plusMonths (LocalDate/now) 1) (:paid_through_date result)))))

  (testing "returns nil when no account has that email"
    (is (nil? (silently #(billing/set-paid-through! "ghost@example.com" "2027-01-01"))))))

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
