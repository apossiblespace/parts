(ns apossiblespace.account-test
  (:require  [clojure.test :refer [deftest is testing use-fixtures]]
             [apossiblespace.test-helpers :refer [with-test-db]]
             [apossiblespace.test-factory :as factory]
             [apossiblespace.parts.db :as db]
             [apossiblespace.parts.auth :as auth]
             [apossiblespace.parts.account :as account]))

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

(deftest test-delete-account
  (testing "disallows access without a valid token" (is true))
  (testing "allows access with a valid token" (is true))
  (testing "does not delete account without confirmation param" (is true))
  (testing "deletes account with confirmation param" (is true)))

(deftest test-register-account
  (testing "register creates a new user successfully"
    (let [user-data (factory/create-test-user)
          {:keys [email password username display_name role]} user-data
          result (account/register-account user-data)]
      (is (= {:success "User registered successfully"} result))
      (let [user (db/query-one (db/sql-format {:select [:*]
                                               :from [:users]
                                               :where [:= :email email]}))]
        (is (= email (:email user)))
        (is (= username (:username user)))
        (is (= display_name (:display_name user)))
        (is (= role (:role user)))
        (is (auth/check-password password (:password_hash user))))))

  (testing "register fails with duplicate email"
    (let [user-data (factory/create-test-user)]
      (account/register-account user-data)
      (let [result (account/register-account (assoc user-data :username "anotheruser"))]
        (is (= {:error "User with this email or username already exists"} result)))))

  (testing "register fails with duplicate username"
    (let [user-data (factory/create-test-user)
          {:keys [email]} user-data]
      (account/register-account user-data)
      (let [result (account/register-account (assoc user-data :email (str "dup" email)))]
        (is (= {:error "User with this email or username already exists"} result))))))
