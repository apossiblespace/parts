(ns parts.entity.system-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [parts.entity.system :as system]
   [parts.helpers.utils :refer [with-test-db register-test-user]]))

(use-fixtures :once with-test-db)

(deftest test-system-crud
  (let [user (register-test-user)
        system-data {:title "Test System"
                     :owner_id (:id user)}]

    (testing "create-system!"
      (let [created (system/create-system! system-data)]
        (is (string? (:id created)))
        (is (= (:title system-data) (:title created)))
        (is (= (:owner_id system-data) (:owner_id created)))
        (is (some? (:created_at created)))
        (is (some? (:last_modified created)))))

    (testing "get-system"
      (let [created (system/create-system! system-data)
            fetched (system/get-system (:id created))]
        (is (= (:id created) (:id fetched)))
        (is (= (:title created) (:title fetched)))
        (is (vector? (:nodes fetched)))
        (is (vector? (:edges fetched)))))

    (testing "list-systems"
      (let [_ (system/create-system! system-data)
            systems (system/list-systems (:id user))]
        (is (seq systems))
        (is (every? #(= (:owner_id %) (:id user)) systems))))

    (testing "update-system!"
      (let [created (system/create-system! system-data)
            updated (system/update-system! (:id created) {:title "Updated Title"
                                                          :owner_id (:id user)})]
        (is (= "Updated Title" (:title updated)))
        (is (= (:id created) (:id updated)))))

    (testing "delete-system!"
      (let [created (system/create-system! system-data)
            result (system/delete-system! (:id created))]
        (is (:deleted result))
        (is (= (:id created) (:id result)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"System not found"
                              (system/get-system (:id created))))))))

(deftest test-system-validations
  (testing "creates fails with invalid data"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Validation failed"
                          (system/create-system! {}))))

  (testing "update fails with invalid data"
    (let [user (register-test-user)
          system (system/create-system! {:title "Test" :owner_id (:id user)})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Validation failed"
                            (system/update-system! (:id system) {:title nil}))))))
