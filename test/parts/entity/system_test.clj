(ns parts.entity.system-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [parts.db :as db]
   [parts.entity.system :as system]
   [parts.helpers.utils :refer [register-test-user with-test-db]]
   [parts.entity.part :as part]
   [parts.entity.relationship :as relationship]))

(use-fixtures :each with-test-db)

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
        (is (:success result))
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

(deftest test-delete-with-transaction
  (testing "Deleting a system with parts and relationships removes everything"
    (let [user (register-test-user)
          system (system/create! {:title "Transaction Test" :owner_id (:id user)})

          part1 (part/create! {:system_id (:id system)
                               :type "manager"
                               :label "Part 1"
                               :position_x 0
                               :position_y 0})

          part2 (part/create! {:system_id (:id system)
                               :type "exile"
                               :label "Part 2"
                               :position_x 100
                               :position_y 100})

          _relationship (relationship/create! {:system_id (:id system)
                                               :source_id (:id part1)
                                               :target_id (:id part2)
                                               :type "unknown"})
          system-before-deletion (system/fetch (:id system))
          result (system/delete! (:id system))]

      (is (= 2 (count (:parts system-before-deletion))))
      (is (= 1 (count (:relationships system-before-deletion))))

      (is (:success result))
      (is (= (:id system) (:id result)))
      (is (= 2 (:parts-deleted result)))
      (is (= 1 (:relationships-deleted result)))

      (is (thrown? Exception (system/fetch (:id system)))))))

(deftest test-transaction-rollback
  (testing "Transaction rolls back if an error occurs"
    (let [user (register-test-user)
          system (system/create! {:title "Transaction Test" :owner_id (:id user)})
          _part (part/create! {:system_id (:id system)
                               :type "manager"
                               :label "Test Part"
                               :position_x 0
                               :position_y 0})

          system-before-deletion (system/fetch (:id system))]

      (is (= 1 (count (:parts system-before-deletion))))

      (with-redefs [db/with-transaction (fn [f]
                                          (println "[REDEF] in redefined with-transaction")
                                          (throw (Exception. "Simulated transaction error")))]

        (is (thrown? Exception (system/delete! (:id system))))
        (let [system-after (system/fetch (:id system))]
          (is system-after "System should still exist")
          (is (= 1 (count (:parts system-after))) "Part should still exist"))))))
