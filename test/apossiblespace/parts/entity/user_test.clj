(ns apossiblespace.parts.entity.user-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [apossiblespace.helpers.test-helpers :refer [with-test-db register-test-user]]
            [apossiblespace.helpers.test-factory :as factory]
            [apossiblespace.parts.entity.user :as user]
            [apossiblespace.parts.db :as db]))

(use-fixtures :once with-test-db)

(deftest test-fetch
  (testing "returns a user entity when a valid ID is passed"
    (let [db-user (register-test-user)
          fetched-user (user/fetch (:id db-user))]
      (is (= (:id db-user) (:id fetched-user)))))

  (testing "throws when an invalid ID is passed"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"User not found"
                          (user/fetch "random")))))
