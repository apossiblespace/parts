(ns parts.entity.node-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [parts.entity.node :as node]
   [parts.entity.system :as system]
   [parts.helpers.utils :refer [with-test-db register-test-user]]))

(use-fixtures :once with-test-db)

(deftest test-node-crud
  (let [user (register-test-user)
        system (system/create-system! {:title "Test System" :owner_id (:id user)})
        node-data {:system_id (:id system)
                   :type "manager"
                   :label "Test Node"
                   :position_x 100
                   :position_y 100}]

    (testing "create-node!"
      (let [created (node/create-node! node-data)]
        (is (string? (:id created)))
        (is (= (:type node-data) (:type created)))
        (is (= (:label node-data) (:label created)))
        (is (= (:position_x node-data) (:position_x created)))
        (is (= (:position_y node-data) (:position_y created)))))

    (testing "get-node"
      (let [created (node/create-node! node-data)
            fetched (node/get-node (:id created))]
        (is (= created fetched))))

    (testing "update-node!"
      (let [created (node/create-node! node-data)
            updated (node/update-node! (:id created)
                                       (assoc node-data
                                              :label "Updated Label"
                                              :position_x 200))]
        (is (= "Updated Label" (:label updated)))
        (is (= 200 (:position_x updated)))
        (is (= (:id created) (:id updated)))))

    (testing "delete-node!"
      (let [created (node/create-node! node-data)
            result (node/delete-node! (:id created))]
        (is (:deleted result))
        (is (= (:id created) (:id result)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Node not found"
                              (node/get-node (:id created))))))))

(deftest test-node-validations
  (testing "create fails with invalid data"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Validation failed"
                          (node/create-node! {}))))

  (testing "create fails with invalid type"
    (let [user (register-test-user)
          system (system/create-system! {:title "Test System" :owner_id (:id user)})
          node-data {:system_id (:id system)
                     :type "invalid-type"
                     :label "Test Node"
                     :position_x 100
                     :position_y 100}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Validation failed"
                            (node/create-node! node-data)))))

  (testing "update fails with invalid data"
    (let [user (register-test-user)
          system (system/create-system! {:title "Test System" :owner_id (:id user)})
          node (node/create-node! {:system_id (:id system)
                                   :type "manager"
                                   :label "Test Node"
                                   :position_x 100
                                   :position_y 100})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Validation failed"
                            (node/update-node! (:id node) {:type "invalid-type"}))))))
