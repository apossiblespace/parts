(ns aps.parts.db.product-updates-migration-test
  "The data part of the product-updates-chosen-at migration: Users who had
   opted out count as having chosen, and no other existing account is
   subscribed. The test runs the migration's own UPDATE statement."
  (:require
   [aps.parts.db :as db]
   [aps.parts.entity.user :as user]
   [aps.parts.helpers.utils :refer [create-test-user! with-test-db]]
   [aps.parts.operator-email :as email]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [next.jdbc :as jdbc]))

(use-fixtures :each with-test-db)

(defn- data-statements
  "The UPDATE statements of the migration. The ALTER already ran when the
   test database was migrated."
  []
  (->> (str/split (slurp (io/resource "migrations/20261010000000-product-updates-chosen-at.up.sql"))
                  #"--;;")
       (map str/trim)
       (filter #(re-find #"(?m)^UPDATE" %))))

(defn- row [id]
  (db/query-one (db/sql-format {:select [:product_updates_opted_out_at :product_updates_chosen_at]
                                :from   [:users]
                                :where  [:= :id id]})))

(deftest test-existing-accounts-are-not-subscribed
  (let [never-chose (create-test-user!)
        opted-out   (create-test-user!)]
    ;; The state before the migration: an opt-out has no choice time yet.
    (db/update! :users {:product_updates_opted_out_at [:- [:now] [:raw "interval '1 day'"]]}
                [:= :id (:id opted-out)])
    (is (= 1 (count (data-statements))))
    (doseq [sql (data-statements)]
      (jdbc/execute! db/datasource [sql]))

    (testing "an account that never refused has no choice and no opt-out time"
      (is (nil? (:product_updates_opted_out_at (row (:id never-chose)))))
      (is (nil? (:product_updates_chosen_at (row (:id never-chose))))))

    (testing "an account that had opted out counts as having chosen, at that time"
      (let [{:keys [product_updates_opted_out_at product_updates_chosen_at]} (row (:id opted-out))]
        (is (= product_updates_opted_out_at product_updates_chosen_at))))

    (testing "no existing account gets a product update until the User subscribes"
      (is (zero? (:recipients (email/audience "product-update"))))
      (user/set-product-updates! (:id never-chose) true)
      (is (= 1 (:recipients (email/audience "product-update")))))))
