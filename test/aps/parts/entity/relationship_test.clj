(ns aps.parts.entity.relationship-test
  (:require
   [aps.parts.entity.map :as parts-map]
   [aps.parts.entity.part :as part]
   [aps.parts.entity.relationship :as relationship]
   [aps.parts.helpers.utils :refer [with-test-db create-test-user!]]
   [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :once with-test-db)

(deftest test-relationship-crud
  (let [user              (create-test-user!)
        the-map           (parts-map/create! {:title "Test Map" :owner_id (:id user)} (:id user))
        part1             (part/create! {:map_id     (:id the-map)
                                         :type       "manager"
                                         :label      "Source Part"
                                         :position_x 100
                                         :position_y 100}
                                        (:id user))
        part2             (part/create! {:map_id     (:id the-map)
                                         :type       "exile"
                                         :label      "Target Part"
                                         :position_x 200
                                         :position_y 200}
                                        (:id user))
        relationship-data {:map_id    (:id the-map)
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
          the-map           (parts-map/create! {:title "Test Map" :owner_id (:id user)} (:id user))
          part1             (part/create! {:map_id     (:id the-map)
                                           :type       "manager"
                                           :label      "Source Part"
                                           :position_x 100
                                           :position_y 100}
                                          (:id user))
          part2             (part/create! {:map_id     (:id the-map)
                                           :type       "exile"
                                           :label      "Target Part"
                                           :position_x 200
                                           :position_y 200}
                                          (:id user))
          relationship-data {:map_id    (:id the-map)
                             :source_id (:id part1)
                             :target_id (:id part2)
                             :type      "invalid-type"}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Validation failed"
                            (relationship/create! relationship-data (:id user))))))

  (testing "update fails with invalid data"
    (let [user         (create-test-user!)
          the-map      (parts-map/create! {:title "Test Map" :owner_id (:id user)} (:id user))
          part1        (part/create! {:map_id     (:id the-map)
                                      :type       "manager"
                                      :label      "Source Part"
                                      :position_x 100
                                      :position_y 100}
                                     (:id user))
          part2        (part/create! {:map_id     (:id the-map)
                                      :type       "exile"
                                      :label      "Target Part"
                                      :position_x 200
                                      :position_y 200}
                                     (:id user))
          relationship (relationship/create! {:map_id    (:id the-map)
                                              :source_id (:id part1)
                                              :target_id (:id part2)
                                              :type      "protective"}
                                             (:id user))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Validation failed"
                            (relationship/update! (:id relationship) {:type "invalid-type"} (:id user)))))))

(deftest test-relationship-endpoint-scoping
  ;; Guards the cross-Map IDOR fix: a relationship can't connect to a Part
  ;; that lives in a different Map, even when the actor owns both Maps.
  (testing "create rejects an endpoint Part from another Map"
    (let [user   (create-test-user!)
          map-a  (parts-map/create! {:title "Map A" :owner_id (:id user)} (:id user))
          map-b  (parts-map/create! {:title "Map B" :owner_id (:id user)} (:id user))
          a-part (part/create! {:map_id     (:id map-a)
                                :type       "manager"
                                :label      "A"
                                :position_x 1
                                :position_y 1}
                               (:id user))
          b-part (part/create! {:map_id     (:id map-b)
                                :type       "exile"
                                :label      "B"
                                :position_x 2
                                :position_y 2}
                               (:id user))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a Part in this Map"
                            (relationship/create! {:map_id    (:id map-a)
                                                   :source_id (:id a-part)
                                                   :target_id (:id b-part)
                                                   :type      "protective"}
                                                  (:id user)))))))
