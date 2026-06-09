(ns aps.parts.entity.part-test
  (:require
   [aps.parts.db :as db]
   [aps.parts.entity.map :as parts-map]
   [aps.parts.entity.part :as part]
   [aps.parts.helpers.utils :refer [with-test-db create-test-user!]]
   [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :once with-test-db)

(deftest test-part-crud
  (let [user      (create-test-user!)
        the-map   (parts-map/create! {:title "Test Map" :owner_id (:id user)} (:id user))
        part-data {:map_id     (:id the-map)
                   :type       "manager"
                   :label      "Test Part"
                   :position_x 100
                   :position_y 100}]

    (testing "create!"
      (let [created (part/create! part-data (:id user))]
        (is (uuid? (:id created)))
        (is (= (:type part-data) (:type created)))
        (is (= (:label part-data) (:label created)))
        (is (= (:position_x part-data) (:position_x created)))
        (is (= (:position_y part-data) (:position_y created)))))

    (testing "fetch"
      (let [created (part/create! part-data (:id user))
            fetched (part/fetch (:id created))]
        (is (= created fetched))))

    (testing "update!"
      (let [created (part/create! part-data (:id user))
            updated (part/update! (:id created)
                                  {:label      "Updated Label"
                                   :position_x 200
                                   :notes      "Updated notes"}
                                  (:id user))]
        (is (= "Updated Label" (:label updated)))
        (is (= 200 (:position_x updated)))
        (is (= "Updated notes" (:notes updated)))
        (is (= (:id created) (:id updated)))))

    (testing "delete!"
      (let [created (part/create! part-data (:id user))
            result  (part/delete! (:id created) (:id user))]
        (is (:deleted result))
        (is (= (:id created) (:id result)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Part not found"
                              (part/fetch (:id created))))))))

(deftest test-part-validations
  (testing "create fails with invalid data"
    (let [user (create-test-user!)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Validation failed"
                            (part/create! {} (:id user))))))

  (testing "create fails with invalid type"
    (let [user      (create-test-user!)
          the-map   (parts-map/create! {:title "Test Map" :owner_id (:id user)} (:id user))
          part-data {:map_id     (:id the-map)
                     :type       "invalid-type"
                     :label      "Test Part"
                     :position_x 100
                     :position_y 100}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Validation failed"
                            (part/create! part-data (:id user))))))

  (testing "update fails with invalid data"
    (let [user    (create-test-user!)
          the-map (parts-map/create! {:title "Test Map" :owner_id (:id user)} (:id user))
          part    (part/create! {:map_id     (:id the-map)
                                 :type       "manager"
                                 :label      "Test Part"
                                 :position_x 100
                                 :position_y 100}
                                (:id user))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Validation failed"
                            (part/update! (:id part) {:type "invalid-type"} (:id user)))))))

(deftest test-part-map-scoping
  ;; Guards the cross-Map IDOR fix: an entity write carrying a Map scope must
  ;; not touch a Part outside that Map, even when the actor owns both Maps.
  (let [user   (create-test-user!)
        map-a  (parts-map/create! {:title "Map A" :owner_id (:id user)} (:id user))
        map-b  (parts-map/create! {:title "Map B" :owner_id (:id user)} (:id user))
        part-b (part/create! {:map_id     (:id map-b)
                              :type       "manager"
                              :label      "B Part"
                              :position_x 10
                              :position_y 10}
                             (:id user))]
    (testing "update! scoped to another Map is not-found and changes nothing"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Row not found"
                            (part/update! (:id part-b) {:label "hijacked"}
                                          (:id user) db/datasource (:id map-a))))
      (is (= "B Part" (:label (part/fetch (:id part-b))))))

    (testing "delete! scoped to another Map retracts nothing"
      (is (= {:id (:id part-b) :deleted false}
             (part/delete! (:id part-b) (:id user) db/datasource (:id map-a))))
      (is (= "B Part" (:label (part/fetch (:id part-b))))))

    (testing "the same write scoped to the correct Map succeeds"
      (is (= "ok" (:label (part/update! (:id part-b) {:label "ok"}
                                        (:id user) db/datasource (:id map-b))))))))
