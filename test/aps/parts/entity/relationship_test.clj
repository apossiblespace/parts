(ns aps.parts.entity.relationship-test
  (:require
   [aps.parts.entity.part :as part]
   [aps.parts.entity.relationship :as relationship]
   [aps.parts.entity.system :as system]
   [aps.parts.helpers.utils :refer [with-test-db create-test-user!]]
   [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :once with-test-db)

(deftest test-relationship-crud
  (let [user              (create-test-user!)
        system            (system/create! {:title "Test System" :owner_id (:id user)} (:id user))
        part1             (part/create! {:system_id  (:id system)
                                         :type       "manager"
                                         :label      "Source Part"
                                         :position_x 100
                                         :position_y 100}
                                        (:id user))
        part2             (part/create! {:system_id  (:id system)
                                         :type       "exile"
                                         :label      "Target Part"
                                         :position_x 200
                                         :position_y 200}
                                        (:id user))
        relationship-data {:system_id (:id system)
                           :source_id (:id part1)
                           :target_id (:id part2)
                           :type      "protective"}]

    (testing "create!"
      (let [created (relationship/create! relationship-data (:id user))]
        (is (uuid? (:id created)))
        (is (= (:type relationship-data) (:type created)))
        (is (= (:source_id relationship-data) (:source_id created)))
        (is (= (:target_id relationship-data) (:target_id created)))))

    (testing "fetch"
      (let [created (relationship/create! relationship-data (:id user))
            fetched (relationship/fetch (:id created))]
        (is (= created fetched))))

    (testing "update!"
      (let [created (relationship/create! relationship-data (:id user))
            updated (relationship/update! (:id created)
                                          {:type  "alliance"
                                           :notes "Updated notes"}
                                          (:id user))]
        (is (= "alliance" (:type updated)))
        (is (= "Updated notes" (:notes updated)))
        (is (= (:id created) (:id updated)))))

    (testing "delete!"
      (let [created (relationship/create! relationship-data (:id user))
            result  (relationship/delete! (:id created) (:id user))]
        (is (:deleted result))
        (is (= (:id created) (:id result)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Relationship not found"
                              (relationship/fetch (:id created))))))))

(deftest test-relationship-validations
  (testing "create fails with invalid data"
    (let [user (create-test-user!)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Validation failed"
                            (relationship/create! {} (:id user))))))

  (testing "create fails with invalid type"
    (let [user              (create-test-user!)
          system            (system/create! {:title "Test System" :owner_id (:id user)} (:id user))
          part1             (part/create! {:system_id  (:id system)
                                           :type       "manager"
                                           :label      "Source Part"
                                           :position_x 100
                                           :position_y 100}
                                          (:id user))
          part2             (part/create! {:system_id  (:id system)
                                           :type       "exile"
                                           :label      "Target Part"
                                           :position_x 200
                                           :position_y 200}
                                          (:id user))
          relationship-data {:system_id (:id system)
                             :source_id (:id part1)
                             :target_id (:id part2)
                             :type      "invalid-type"}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Validation failed"
                            (relationship/create! relationship-data (:id user))))))

  (testing "update fails with invalid data"
    (let [user         (create-test-user!)
          system       (system/create! {:title "Test System" :owner_id (:id user)} (:id user))
          part1        (part/create! {:system_id  (:id system)
                                      :type       "manager"
                                      :label      "Source Part"
                                      :position_x 100
                                      :position_y 100}
                                     (:id user))
          part2        (part/create! {:system_id  (:id system)
                                      :type       "exile"
                                      :label      "Target Part"
                                      :position_x 200
                                      :position_y 200}
                                     (:id user))
          relationship (relationship/create! {:system_id (:id system)
                                              :source_id (:id part1)
                                              :target_id (:id part2)
                                              :type      "protective"}
                                             (:id user))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Validation failed"
                            (relationship/update! (:id relationship) {:type "invalid-type"} (:id user)))))))
