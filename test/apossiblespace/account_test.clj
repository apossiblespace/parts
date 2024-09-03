(ns apossiblespace.account-test
  (:require  [clojure.test :refer [deftest is testing use-fixtures]]
             [apossiblespace.test-helpers :refer [with-test-db]]
             [apossiblespace.test-factory :as factory]))

(use-fixtures :once with-test-db)

;; TODO: Use register-test-user from the helpers
(deftest test-get-account
  (testing "disallows access without a valid token" (is true))
  (testing "allows access with a valid token" (is true))
  (testing "returns correct user information" (is true)))

(deftest test-update-account
  (testing "disallows access without a valid token" (is true))
  (testing "allows access with a valid token" (is true))
  (testing "correctly updates the user data" (is true))
  (testing "returns updated user information" (is true)))

(deftest delete-account
  (testing "disallows access without a valid token" (is true))
  (testing "allows access with a valid token" (is true))
  (testing "does not delete account without confirmation param" (is true))
  (testing "deletes account with confirmation param" (is true)))
