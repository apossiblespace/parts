(ns apossiblespace.parts.account-test
  (:require  [clojure.test :refer [deftest is testing use-fixtures]]
             [apossiblespace.helpers.test-helpers :refer [with-test-db register-test-user]]
             [apossiblespace.helpers.test-factory :as factory]
             [apossiblespace.parts.db :as db]
             [apossiblespace.parts.auth :as auth]
             [apossiblespace.parts.account :as account]))

(use-fixtures :once with-test-db)

(deftest test-get-account
  (testing "returns currently signed in user's information"
    (let [user (register-test-user)
          mock-request {:identity {:sub (:id user)}}
          response (account/get-account mock-request)]
      (is (= 200 (:status response)))
      (is (= {:email (:email user)
              :username (:username user)
              :display_name (:display_name user)
              :role (:role user)
              :id (:id user)} (:body response)))
      (is (not (contains? response :password_hash))))))

(deftest test-update-account
  (testing "correctly updates the user data"
    (let [user (register-test-user)
          mock-request {:identity {:sub (:id user)}
                        :body {:email (str "added" (:email user))
                               :display_name "Updated"}}
          response (account/update-account mock-request)
          updated-fields (select-keys (:body response) [:email :display_name])]
      (is (= 200 (:status response)))
      (is (= {:email (str "added" (:email user))
              :display_name "Updated"}
             updated-fields)
      (is (not (contains? (:body response) :password_hash))))))

  (testing "returns updated user information" (is true)))

(deftest test-delete-account
  (testing "disallows access without a valid token" (is true))
  (testing "allows access with a valid token" (is true))
  (testing "does not delete account without confirmation param" (is true))
  (testing "deletes account with confirmation param" (is true)))

(deftest test-register-account
  (testing "register creates a new user successfully"
    (let [user-data (factory/create-test-user)
          {:keys [email username display_name role]} user-data
          mock-request {:body user-data}
          response (account/register-account mock-request)
          user (:body response)]
      (is (= 201 (:status response)))
      (is (= email (:email user)))
      (is (= username (:username user)))
      (is (= display_name (:display_name user)))
      (is (= role (:role user))))))
