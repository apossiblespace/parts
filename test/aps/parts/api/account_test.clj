(ns aps.parts.api.account-test
  (:require
   [aps.parts.api.account :as account]
   [aps.parts.db :as db]
   [aps.parts.helpers.test-factory :as factory]
   [aps.parts.helpers.utils :refer [register-test-user with-test-db]]
   [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :once with-test-db)

(deftest test-get-account
  (testing "returns currently signed in user's information"
    (let [user         (register-test-user)
          mock-request {:identity {:sub (:id user)}}
          response     (account/get-account mock-request)]
      (is (= 200 (:status response)))
      (is (= {:email        (:email user)
              :username     (:username user)
              :display_name (:display_name user)
              :role         (:role user)
              :id           (:id user)
              :system_id    nil} (:body response)))
      (is (not (contains? response :password_hash))))))

(deftest test-update-account
  (testing "correctly updates the user data"
    (let [user           (register-test-user)
          mock-request   {:identity    {:sub (:id user)}
                          :body-params {:email        (str "added" (:email user))
                                        :display_name "Updated"}}
          response       (account/update-account mock-request)
          updated-fields (select-keys (:body response) [:email :display_name])]
      (is (= 200 (:status response)))
      (is (= {:email        (str "added" (:email user))
              :display_name "Updated"}
             updated-fields)
          (is (not (contains? (:body response) :password_hash))))))

  (testing "does not update where no updatable data is passed"
    (let [user         (register-test-user)
          mock-request {:identity    {:sub (:id user)}
                        :body-params {:username "something"}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Nothing to update"
                            (account/update-account mock-request))))))

(deftest test-delete-account
  (testing "does not delete without a confirmation param"
    (let [user         (register-test-user)
          mock-request {:identity {:sub (:id user)}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Confirmation needed"
                            (account/delete-account mock-request)))))

  (testing "does not delete with a confirmation param that does not match the username"
    (let [user         (register-test-user)
          mock-request {:identity {:sub (:id user)} :query-params {"confirm" "random"}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Confirmation needed"
                            (account/delete-account mock-request)))))

  (testing "deletes the account with a confirmation param"
    (let [user         (register-test-user)
          mock-request {:identity {:sub (:id user)} :query-params {"confirm" (:username user)}}
          response     (account/delete-account mock-request)
          db-user      (db/query-one (db/sql-format {:select [:id]
                                                     :from   [:users]
                                                     :where  [:= :id (:id user)]}))]
      (is (= 204 (:status response)))
      (is (= nil db-user)))))

;; TODO: When we allow roles other than "therapist" during registration,
;; update this test to verify the role from user-data is respected
(deftest test-register-account
  (testing "register creates a new user with therapist role and a default system"
    (let [user-data                             (factory/build-test-user)
          {:keys [email username display_name]} user-data
          mock-request                          {:body-params user-data}
          response                              (account/register-account mock-request)
          user                                  (:body response)]
      (is (= 201 (:status response)))
      (is (= email (:email user)))
      (is (= username (:username user)))
      (is (= display_name (:display_name user)))
      ;; Role is always hardcoded to "therapist" regardless of input
      (is (= "therapist" (:role user)))
      ;; Registration should return auth tokens
      (is (some? (:access_token user)))
      (is (some? (:refresh_token user)))
      (is (= "Bearer" (:token_type user)))
      ;; Registration should create a default system
      (is (some? (:system_id user))))))
