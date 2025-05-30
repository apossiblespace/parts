(ns parts.entity.part-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [parts.entity.part :as part]
   [parts.entity.system :as system]
   [parts.helpers.utils :refer [with-test-db register-test-user]]))

(use-fixtures :once with-test-db)

(deftest test-part-crud
  (let [user (register-test-user)
        system (system/create! {:title "Test System" :owner_id (:id user)})
        part-data {:system_id (:id system)
                   :type "manager"
                   :label "Test Part"
                   :position_x 100
                   :position_y 100}]

    (testing "create!"
      (let [created (part/create! part-data)]
        (is (string? (:id created)))
        (is (= (:type part-data) (:type created)))
        (is (= (:label part-data) (:label created)))
        (is (= (:position_x part-data) (:position_x created)))
        (is (= (:position_y part-data) (:position_y created)))))

    (testing "fetch"
      (let [created (part/create! part-data)
            fetched (part/fetch (:id created))]
        (is (= created fetched))))

    (testing "update!"
      (let [created (part/create! part-data)
            updated (part/update! (:id created)
                                  (assoc part-data
                                         :label "Updated Label"
                                         :position_x 200
                                         :notes "Updated notes"))]
        (is (= "Updated Label" (:label updated)))
        (is (= 200 (:position_x updated)))
        (is (= "Updated notes" (:notes updated)))
        (is (= (:id created) (:id updated)))))

    (testing "delete!"
      (let [created (part/create! part-data)
            result (part/delete! (:id created))]
        (is (:deleted result))
        (is (= (:id created) (:id result)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Part not found"
                              (part/fetch (:id created))))))))

(deftest test-part-validations
  (testing "create fails with invalid data"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Validation failed"
                          (part/create! {}))))

  (testing "create fails with invalid type"
    (let [user (register-test-user)
          system (system/create! {:title "Test System" :owner_id (:id user)})
          part-data {:system_id (:id system)
                     :type "invalid-type"
                     :label "Test Part"
                     :position_x 100
                     :position_y 100}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Validation failed"
                            (part/create! part-data)))))

  (testing "update fails with invalid data"
    (let [user (register-test-user)
          system (system/create! {:title "Test System" :owner_id (:id user)})
          part (part/create! {:system_id (:id system)
                              :type "manager"
                              :label "Test Part"
                              :position_x 100
                              :position_y 100})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Validation failed"
                            (part/update! (:id part) {:type "invalid-type"}))))))
