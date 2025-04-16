(ns parts.entity.relationship-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [parts.entity.relationship :as relationship]
   [parts.entity.part :as part]
   [parts.entity.system :as system]
   [parts.helpers.utils :refer [with-test-db register-test-user]]))

(use-fixtures :once with-test-db)

(deftest test-relationship-crud
  (let [user (register-test-user)
        system (system/create! {:title "Test System" :owner_id (:id user)})
        part1 (part/create! {:system_id (:id system)
                             :type "manager"
                             :label "Source Part"
                             :position_x 100
                             :position_y 100})
        part2 (part/create! {:system_id (:id system)
                             :type "exile"
                             :label "Target Part"
                             :position_x 200
                             :position_y 200})
        relationship-data {:system_id (:id system)
                           :source_id (:id part1)
                           :target_id (:id part2)
                           :type "protective"}]

    (testing "create!"
      (let [created (relationship/create! relationship-data)]
        (is (string? (:id created)))
        (is (= (:type relationship-data) (:type created)))
        (is (= (:source_id relationship-data) (:source_id created)))
        (is (= (:target_id relationship-data) (:target_id created)))))

    (testing "fetch"
      (let [created (relationship/create! relationship-data)
            fetched (relationship/fetch (:id created))]
        (is (= created fetched))))

    (testing "update!"
      (let [created (relationship/create! relationship-data)
            updated (relationship/update! (:id created)
                                          (assoc relationship-data
                                                 :type "alliance"
                                                 :notes "Updated notes"))]
        (is (= "alliance" (:type updated)))
        (is (= "Updated notes" (:notes updated)))
        (is (= (:id created) (:id updated)))))

    (testing "delete!"
      (let [created (relationship/create! relationship-data)
            result (relationship/delete! (:id created))]
        (is (:deleted result))
        (is (= (:id created) (:id result)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Relationship not found"
                              (relationship/fetch (:id created))))))))

(deftest test-relationship-validations
  (testing "create fails with invalid data"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Validation failed"
                          (relationship/create! {}))))

  (testing "create fails with invalid type"
    (let [user (register-test-user)
          system (system/create! {:title "Test System" :owner_id (:id user)})
          part1 (part/create! {:system_id (:id system)
                               :type "manager"
                               :label "Source Part"
                               :position_x 100
                               :position_y 100})
          part2 (part/create! {:system_id (:id system)
                               :type "exile"
                               :label "Target Part"
                               :position_x 200
                               :position_y 200})
          relationship-data {:system_id (:id system)
                             :source_id (:id part1)
                             :target_id (:id part2)
                             :type "invalid-type"}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Validation failed"
                            (relationship/create! relationship-data)))))

  (testing "update fails with invalid data"
    (let [user (register-test-user)
          system (system/create! {:title "Test System" :owner_id (:id user)})
          part1 (part/create! {:system_id (:id system)
                               :type "manager"
                               :label "Source Part"
                               :position_x 100
                               :position_y 100})
          part2 (part/create! {:system_id (:id system)
                               :type "exile"
                               :label "Target Part"
                               :position_x 200
                               :position_y 200})
          relationship (relationship/create! {:system_id (:id system)
                                              :source_id (:id part1)
                                              :target_id (:id part2)
                                              :type "protective"})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Validation failed"
                            (relationship/update! (:id relationship) {:type "invalid-type"}))))))
