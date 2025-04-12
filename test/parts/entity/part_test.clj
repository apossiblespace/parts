(ns parts.entity.part-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [parts.entity.part :as part]
   [parts.entity.system :as system]
   [parts.helpers.utils :refer [with-test-db register-test-user]]))

(use-fixtures :once with-test-db)

(deftest test-part-crud
  (let [user (register-test-user)
        system (system/create-system! {:title "Test System" :owner_id (:id user)})
        part-data {:system_id (:id system)
                   :type "manager"
                   :label "Test Part"
                   :position_x 100
                   :position_y 100}]

    (testing "create-part!"
      (let [created (part/create-part! part-data)]
        (is (string? (:id created)))
        (is (= (:type part-data) (:type created)))
        (is (= (:label part-data) (:label created)))
        (is (= (:position_x part-data) (:position_x created)))
        (is (= (:position_y part-data) (:position_y created)))))

    (testing "get-part"
      (let [created (part/create-part! part-data)
            fetched (part/get-part (:id created))]
        (is (= created fetched))))

    (testing "update-part!"
      (let [created (part/create-part! part-data)
            updated (part/update-part! (:id created)
                                       (assoc part-data
                                              :label "Updated Label"
                                              :position_x 200))]
        (is (= "Updated Label" (:label updated)))
        (is (= 200 (:position_x updated)))
        (is (= (:id created) (:id updated)))))

    (testing "delete-part!"
      (let [created (part/create-part! part-data)
            result (part/delete-part! (:id created))]
        (is (:deleted result))
        (is (= (:id created) (:id result)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Part not found"
                              (part/get-part (:id created))))))))

(deftest test-part-validations
  (testing "create fails with invalid data"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Validation failed"
                          (part/create-part! {}))))

  (testing "create fails with invalid type"
    (let [user (register-test-user)
          system (system/create-system! {:title "Test System" :owner_id (:id user)})
          part-data {:system_id (:id system)
                     :type "invalid-type"
                     :label "Test Part"
                     :position_x 100
                     :position_y 100}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Validation failed"
                            (part/create-part! part-data)))))

  (testing "update fails with invalid data"
    (let [user (register-test-user)
          system (system/create-system! {:title "Test System" :owner_id (:id user)})
          part (part/create-part! {:system_id (:id system)
                                   :type "manager"
                                   :label "Test Part"
                                   :position_x 100
                                   :position_y 100})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Validation failed"
                            (part/update-part! (:id part) {:type "invalid-type"}))))))
