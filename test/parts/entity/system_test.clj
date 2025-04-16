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

    (testing "create!"
      (let [created (system/create! system-data)]
        (is (string? (:id created)))
        (is (= (:title system-data) (:title created)))
        (is (= (:owner_id system-data) (:owner_id created)))
        (is (some? (:created_at created)))
        (is (some? (:last_modified created)))))

    (testing "fetch"
      (let [created (system/create! system-data)
            fetched (system/fetch (:id created))]
        (is (= (:id created) (:id fetched)))
        (is (= (:title created) (:title fetched)))
        (is (vector? (:parts fetched)))
        (is (vector? (:relationships fetched)))))

    (testing "index"
      (let [_ (system/create! system-data)
            systems (system/index (:id user))]
        (is (seq systems))
        (is (every? #(= (:owner_id %) (:id user)) systems))))

    (testing "update!"
      (let [created (system/create! system-data)
            updated (system/update! (:id created) {:title "Updated Title"
                                                   :owner_id (:id user)})]
        (is (= "Updated Title" (:title updated)))
        (is (= (:id created) (:id updated)))))

    (testing "delete!"
      (let [created (system/create! system-data)
            result (system/delete! (:id created))]
        (is (:deleted result))
        (is (= (:id created) (:id result)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"System not found"
                              (system/fetch (:id created))))))))

(deftest test-system-validations
  (testing "creates fails with invalid data"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Validation failed"
                          (system/create! {}))))

  (testing "update fails with invalid data"
    (let [user (register-test-user)
          system (system/create! {:title "Test" :owner_id (:id user)})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Validation failed"
                            (system/update! (:id system) {:title nil}))))))
