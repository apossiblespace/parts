(ns aps.parts.entity.part-test
  (:require
   [aps.parts.entity.part :as part]
   [aps.parts.entity.system :as system]
   [aps.parts.helpers.utils :refer [with-test-db create-test-user!]]
   [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :once with-test-db)

(deftest test-part-crud
  (let [user      (create-test-user!)
        system    (system/create! {:title "Test System" :owner_id (:id user)} (:id user))
        part-data {:system_id  (:id system)
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
                                  (assoc part-data
                                         :label "Updated Label"
                                         :position_x 200
                                         :notes "Updated notes")
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
          system    (system/create! {:title "Test System" :owner_id (:id user)} (:id user))
          part-data {:system_id  (:id system)
                     :type       "invalid-type"
                     :label      "Test Part"
                     :position_x 100
                     :position_y 100}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Validation failed"
                            (part/create! part-data (:id user))))))

  (testing "update fails with invalid data"
    (let [user   (create-test-user!)
          system (system/create! {:title "Test System" :owner_id (:id user)} (:id user))
          part   (part/create! {:system_id  (:id system)
                                :type       "manager"
                                :label      "Test Part"
                                :position_x 100
                                :position_y 100}
                               (:id user))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Validation failed"
                            (part/update! (:id part) {:type "invalid-type"} (:id user)))))))
