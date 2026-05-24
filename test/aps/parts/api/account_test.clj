(ns aps.parts.api.account-test
  (:require
   [aps.parts.api.account :as account]
   [aps.parts.auth :as auth]
   [aps.parts.db :as db]
   [aps.parts.entity.map :as parts-map]
   [aps.parts.helpers.test-factory :as factory]
   [aps.parts.helpers.utils :refer [create-test-user! with-test-db]]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :once with-test-db)

(deftest test-get-account
  (testing "returns currently signed in user's information"
    (let [user         (create-test-user!)
          mock-request {:identity {:sub (:id user)}}
          response     (account/get-account mock-request)]
      (is (= 200 (:status response)))
      (is (= {:email        (:email user)
              :username     (:username user)
              :display_name (:display_name user)
              :role         (:role user)
              :id           (:id user)
              :map_id       nil} (:body response)))
      (is (not (contains? response :password_hash))))))

(deftest test-update-account
  (testing "correctly updates the user data"
    (let [user           (create-test-user!)
          mock-request   {:identity    {:sub (:id user)}
                          :body-params {:email        (str "added" (:email user))
                                        :display_name "Updated"}}
          response       (account/update-account mock-request)
          updated-fields (select-keys (:body response) [:email :display_name])]
      (is (= 200 (:status response)))
      (is (= {:email        (str "added" (:email user))
              :display_name "Updated"}
             updated-fields))
      (is (not (contains? (:body response) :password_hash)))))

  (testing "does not update where no updatable data is passed"
    (let [user         (create-test-user!)
          mock-request {:identity    {:sub (:id user)}
                        :body-params {:username "something"}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Nothing to update"
                            (account/update-account mock-request))))))

(deftest test-delete-account
  (testing "does not delete without a confirmation param"
    (let [user         (create-test-user!)
          mock-request {:identity {:sub (:id user)}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Confirmation needed"
                            (account/delete-account mock-request)))))

  (testing "does not delete with a confirmation param that does not match the username"
    (let [user         (create-test-user!)
          mock-request {:identity {:sub (:id user)} :query-params {"confirm" "random"}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Confirmation needed"
                            (account/delete-account mock-request)))))

  (testing "deletes the account with a confirmation param"
    (let [user         (create-test-user!)
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
  (testing "register creates a new user with therapist role and a default map"
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
      ;; Registration establishes the auth session for auto-login
      (is (= {:sub (str (:id user))} (get-in response [:session :identity])))
      ;; Registration should create a default map
      (is (some? (:map_id user)))))

  (testing "registration creates a map for the new account, titled after the display name"
    (let [user-data (factory/build-test-user)
          response  (account/register-account {:body-params user-data})
          user      (:body response)
          the-map   (parts-map/fetch (:map_id user))]
      (is (some? the-map) "the map exists in the database")
      (is (= (:id user) (:owner_id the-map)) "the map is owned by the new account")))

  (testing "if a write inside the tx throws, the user insert is rolled back"
    (let [user-data    (factory/build-test-user)
          mock-request {:body-params user-data}]
      (with-redefs [parts-map/create! (fn [_ _]
                                        (throw (ex-info "Simulated tx error" {})))]
        (is (thrown? Exception
                     (account/register-account mock-request))))
      (let [db-user (db/query-one
                     (db/sql-format {:select [:id]
                                     :from   [:users]
                                     :where  [:= :email (:email user-data)]}))]
        (is (= nil db-user) "User row should have been rolled back")))))

(deftest test-register-then-authenticate
  (testing "a registered user can immediately authenticate with their credentials"
    (let [user-data (factory/build-test-user)
          _         (account/register-account {:body-params user-data})
          result    (auth/authenticate
                     (select-keys user-data [:email :password]))]
      (is (some? result) "authenticate returns the user")
      (is (= (:email user-data) (:email result)))))

  (testing "authenticates case-insensitively against the registered email"
    (let [user-data (factory/build-test-user)
          _         (account/register-account {:body-params user-data})
          result    (auth/authenticate
                     {:email    (str/upper-case (:email user-data))
                      :password (:password user-data)})]
      (is (some? result)
          "Uppercased email should still find the (lowercased) stored row"))))
