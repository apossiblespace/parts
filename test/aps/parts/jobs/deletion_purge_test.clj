(ns aps.parts.jobs.deletion-purge-test
  (:require
   [aps.parts.config :as conf]
   [aps.parts.db :as db]
   [aps.parts.helpers.utils :refer [create-test-user! expire-deletion-grace!
                                    stripe-api-error with-test-db]]
   [aps.parts.jobs.deletion-purge :as purge]
   [aps.parts.stripe :as stripe]
   [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :once with-test-db)

(defn- user-exists? [user-id]
  (some? (db/query-one (db/sql-format {:select [:id]
                                       :from   [:users]
                                       :where  [:= :id user-id]}))))

(deftest run-once!-releases-stripe-link-test
  (testing "purging a linked account deletes its Stripe customer first"
    (let [user     (create-test-user! {:email "purge-linked@example.com"})
          captured (atom nil)]
      (db/update! :users {:stripe_customer_id "cus_purge"} [:= :id (:id user)])
      (expire-deletion-grace! (:id user))
      (with-redefs [conf/stripe-secret-key  (constantly "rk_test_key")
                    stripe/delete-customer! (fn [_cfg customer]
                                              (reset! captured customer)
                                              {:id customer :deleted true})]
        (is (= 1 (purge/run-once!))))
      (is (= "cus_purge" @captured))
      (is (not (user-exists? (:id user))))))

  (testing "an unlinked account purges without any Stripe call"
    (let [user     (create-test-user! {:email "purge-unlinked@example.com"})
          captured (atom nil)]
      (expire-deletion-grace! (:id user))
      (with-redefs [conf/stripe-secret-key  (constantly "rk_test_key")
                    stripe/delete-customer! (fn [_ customer] (reset! captured customer))]
        (is (= 1 (purge/run-once!))))
      (is (nil? @captured))
      (is (not (user-exists? (:id user))))))

  (testing "a Stripe outage postpones the purge; the next run completes it"
    (let [user (create-test-user! {:email "purge-postponed@example.com"})]
      (db/update! :users {:stripe_customer_id "cus_postponed"} [:= :id (:id user)])
      (expire-deletion-grace! (:id user))
      (with-redefs [conf/stripe-secret-key  (constantly "rk_test_key")
                    stripe/delete-customer! (fn [_ _] (throw (stripe-api-error 500)))]
        (is (= 0 (purge/run-once!))))
      (is (user-exists? (:id user))
          "the account must survive until its Stripe link can be released")
      (with-redefs [conf/stripe-secret-key  (constantly "rk_test_key")
                    stripe/delete-customer! (constantly {:deleted true})]
        (is (= 1 (purge/run-once!))))
      (is (not (user-exists? (:id user)))))))
