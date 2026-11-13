(ns aps.parts.trial-reminders-test
  (:require
   [aps.parts.db :as db]
   [aps.parts.entity.user :as user]
   [aps.parts.helpers.utils :refer [create-test-user! with-test-db]]
   [aps.parts.mail :as mail]
   [aps.parts.trial-reminders :as trial-reminders]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]])
  (:import
   (java.sql Timestamp)
   (java.time Instant LocalDate)))

(use-fixtures :each with-test-db)

(def ^:private start (LocalDate/parse "2026-11-18"))

(defn- day [s] (LocalDate/parse s))

(defn- created [s] {:created_at (Instant/parse (str s "T09:00:00Z"))})

(defn- kind-on [row today]
  (:kind (trial-reminders/due-reminder row start (day today))))

(deftest test-due-reminder-beta-account
  (let [beta (created "2026-08-01")]
    (testing "a beta account is reminded from 7 days before the start date"
      (is (nil? (kind-on beta "2026-11-10")))
      (is (= :ending (kind-on beta "2026-11-11")))
      (is (= :ending (kind-on beta "2026-11-18")))
      (is (= (day "2026-11-18") (:ends (trial-reminders/due-reminder beta start (day "2026-11-13"))))))
    (testing "a beta account gets no ended reminder"
      (is (nil? (kind-on beta "2026-11-19"))))))

(deftest test-due-reminder-new-account
  (let [fresh (created "2026-12-01")]
    (testing "a new account is reminded 7 days before its own trial ends"
      (is (nil? (kind-on fresh "2026-12-23")))
      (is (= :ending (kind-on fresh "2026-12-24")))
      (is (= :ending (kind-on fresh "2026-12-31"))))
    (testing "a new account gets the ended reminder for 7 days after the trial"
      (is (= :ended (kind-on fresh "2027-01-01")))
      (is (= :ended (kind-on fresh "2027-01-07")))
      (is (nil? (kind-on fresh "2027-01-08"))))
    (testing "an account created on the start date counts as new"
      (is (= :ended (kind-on (created "2026-11-18") "2026-12-19"))))))

(deftest test-due-reminder-paying-accounts
  (testing "a live subscription or paid time gets no reminder"
    (doseq [status ["active" "trialing" "past_due"]]
      (is (nil? (kind-on (assoc (created "2026-12-01") :stripe_subscription_status status)
                         "2026-12-28"))))
    (is (nil? (kind-on (assoc (created "2026-12-01") :paid_through_date (day "2027-02-01"))
                       "2027-01-02"))))
  (testing "without a payment start date nothing is due"
    (is (nil? (trial-reminders/due-reminder (created "2026-08-01") nil (day "2026-11-13"))))))

(deftest test-message
  (let [m (trial-reminders/message :ending "a@example.test" (day "2026-11-18"))]
    (is (= "Your Parts free trial ends on 18 November 2026" (:subject m)))
    (is (str/includes? (-> m :body second :content) "ends on 18 November 2026"))
    (is (str/includes? (-> m :body second :content) "/app/account"))))

(defn- create-user-at! [s]
  (let [u (create-test-user!)]
    (db/update! :users {:created_at (Timestamp/from (Instant/parse (str s "T09:00:00Z")))}
                [:= :id (:id u)])
    u))

(defn- log-rows []
  (db/query (db/sql-format {:select [:user_id :kind] :from [:trial_reminders]})))

(deftest test-send-due
  (let [sent  (atom [])
        beta  (create-user-at! "2026-08-01")
        payer (create-user-at! "2026-08-01")]
    (db/update! :users {:stripe_subscription_status "active"} [:= :id (:id payer)])
    (with-redefs [mail/send-personal! (fn [m] (swap! sent conj m) m)]
      (testing "sends each due reminder once, and none to a subscriber"
        (is (= 1 (trial-reminders/send-due! start (day "2026-11-13"))))
        (is (= [(:email beta)] (map :to @sent)))
        (is (= 0 (trial-reminders/send-due! start (day "2026-11-14"))))
        (is (= 1 (count @sent))))
      (testing "sends nothing without a payment start date"
        (is (= 0 (trial-reminders/send-due! nil (day "2026-11-13"))))))))

(deftest test-send-due-failure-is-retried
  (let [u (create-user-at! "2026-08-01")]
    (with-redefs [mail/send-personal! (fn [_] (throw (ex-info "relay down" {})))]
      (is (= 0 (trial-reminders/send-due! start (day "2026-11-13"))))
      (is (empty? (log-rows)) "a failed send leaves no log row"))
    (with-redefs [mail/send-personal! identity]
      (is (= 1 (trial-reminders/send-due! start (day "2026-11-13"))))
      (is (= [{:user_id (:id u) :kind "ending"}] (log-rows))))))

(deftest test-send-log-is-erased-with-the-account
  (let [u (create-user-at! "2026-08-01")]
    (with-redefs [mail/send-personal! identity]
      (trial-reminders/send-due! start (day "2026-11-13")))
    (is (= 1 (count (log-rows))))
    (user/delete! (:id u))
    (is (empty? (log-rows)))))
