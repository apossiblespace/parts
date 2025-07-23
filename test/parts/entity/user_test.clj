(ns parts.entity.user-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [parts.entity.user :as user]
   [parts.entity.system :as system]
   [parts.helpers.test-factory :as factory]
   [parts.helpers.utils :refer [register-test-user with-test-db]]))

(use-fixtures :once with-test-db)

(deftest test-fetch
  (testing "returns a user entity when a valid ID is passed"
    (let [db-user (register-test-user)
          fetched-user (user/fetch (:id db-user))]
      (is (= (:id db-user) (:id fetched-user)))))

  (testing "throws when an invalid ID is passed"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"User not found"
                          (user/fetch "random")))))

(deftest test-update!
  (testing "saves the user entity to the database"
    (let [db-user (register-test-user)
          updated-user (user/update! (:id db-user) {:display_name "Bobby"})]
      (is (= (:display_name updated-user) "Bobby"))))

  (testing "throws when an empty params map is passed"
    (let [db-user (register-test-user)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Nothing to update"
                            (user/update! (:id db-user) {})))))

  (testing "throws when passed a field that does not allow updates"
    (let [db-user (register-test-user)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Nothing to update"
                            (user/update! (:id db-user) {:username "emanresu"})))))

  (testing "throws when a password is passed without confirmation"
    (let [db-user (register-test-user)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Password and confirmation do not match"
                            (user/update! (:id db-user) {:password "password12345"})))))

  (testing "throws when the passed password and confirmation do not match"
    (let [db-user (register-test-user)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Password and confirmation do not match"
                            (user/update!
                             (:id db-user)
                             {:password "password12345"
                              :password_confirmation "wordpass54321"})))))

  (testing "throws when no ID is passed"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing User ID"
                          (user/update!
                           nil
                           {:password "password12345"
                            :password_confirmation "password12345"})))))

(deftest test-create!
  (testing "creates the user entity in the database"
    (let [attrs (factory/build-test-user)
          {:keys [email username display_name role]} attrs
          created-user (user/create! attrs)]
      (is (contains? created-user :id))
      (is (= email (:email created-user)))
      (is (= username (:username created-user)))
      (is (= display_name (:display_name created-user)))
      (is (= role (:role created-user)))))

  (testing "throws when an empty params map is passed"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Nothing to update"
                          (user/create! {}))))

  (testing "throws when a password is passed without confirmation"
    (let [attrs (factory/build-test-user)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Password and confirmation do not match"
                            (user/create! (dissoc attrs :password_confirmation))))))

  (testing "throws when the passed password and confirmation do not match"
    (let [attrs (factory/build-test-user)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Password and confirmation do not match"
                            (user/create!
                             (assoc attrs
                                    :password "password12345"
                                    :password_confirmation "wordpass54321")))))))

(deftest test-delete!
  (testing "deletes the user entity from the database"
    (let [user (register-test-user)
          id (:id user)]
      (user/delete! id)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"User not found"
                            (user/fetch id)))))

  (testing "deletes the system entity owned by the user from the database"
    (let [user (register-test-user)
          id (:id user)
          system-data {:title "System To Delete"
                       :owner_id id}
          system-created (system/create! system-data)]
      (user/delete! id)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"System not found"
                            (system/fetch (:id system-created)))))))