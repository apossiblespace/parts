(ns parts.entity.edge-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [parts.entity.edge :as edge]
   [parts.entity.node :as node]
   [parts.entity.system :as system]
   [parts.helpers.utils :refer [with-test-db register-test-user]]))

(use-fixtures :once with-test-db)

(deftest test-edge-crud
  (let [user (register-test-user)
        system (system/create-system! {:title "Test System" :owner_id (:id user)})
        node1 (node/create-node! {:system_id (:id system)
                                  :type "manager"
                                  :label "Source Node"
                                  :position_x 100
                                  :position_y 100})
        node2 (node/create-node! {:system_id (:id system)
                                  :type "exile"
                                  :label "Target Node"
                                  :position_x 200
                                  :position_y 200})
        edge-data {:system_id (:id system)
                   :source_id (:id node1)
                   :target_id (:id node2)
                   :type "protective"}]

    (testing "create-edge!"
      (let [created (edge/create-edge! edge-data)]
        (is (string? (:id created)))
        (is (= (:type edge-data) (:type created)))
        (is (= (:source_id edge-data) (:source_id created)))
        (is (= (:target_id edge-data) (:target_id created)))))

    (testing "get-edge"
      (let [created (edge/create-edge! edge-data)
            fetched (edge/get-edge (:id created))]
        (is (= created fetched))))

    (testing "update-edge!"
      (let [created (edge/create-edge! edge-data)
            updated (edge/update-edge! (:id created)
                                       (assoc edge-data
                                              :type "alliance"
                                              :notes "Updated notes"))]
        (is (= "alliance" (:type updated)))
        (is (= "Updated notes" (:notes updated)))
        (is (= (:id created) (:id updated)))))

    (testing "delete-edge!"
      (let [created (edge/create-edge! edge-data)
            result (edge/delete-edge! (:id created))]
        (is (:deleted result))
        (is (= (:id created) (:id result)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Edge not found"
                              (edge/get-edge (:id created))))))))

(deftest test-edge-validations
  (testing "create fails with invalid data"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Validation failed"
                          (edge/create-edge! {}))))

  (testing "create fails with invalid type"
    (let [user (register-test-user)
          system (system/create-system! {:title "Test System" :owner_id (:id user)})
          node1 (node/create-node! {:system_id (:id system)
                                    :type "manager"
                                    :label "Source Node"
                                    :position_x 100
                                    :position_y 100})
          node2 (node/create-node! {:system_id (:id system)
                                    :type "exile"
                                    :label "Target Node"
                                    :position_x 200
                                    :position_y 200})
          edge-data {:system_id (:id system)
                     :source_id (:id node1)
                     :target_id (:id node2)
                     :type "invalid-type"}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Validation failed"
                            (edge/create-edge! edge-data)))))

  (testing "update fails with invalid data"
    (let [user (register-test-user)
          system (system/create-system! {:title "Test System" :owner_id (:id user)})
          node1 (node/create-node! {:system_id (:id system)
                                    :type "manager"
                                    :label "Source Node"
                                    :position_x 100
                                    :position_y 100})
          node2 (node/create-node! {:system_id (:id system)
                                    :type "exile"
                                    :label "Target Node"
                                    :position_x 200
                                    :position_y 200})
          edge (edge/create-edge! {:system_id (:id system)
                                   :source_id (:id node1)
                                   :target_id (:id node2)
                                   :type "protective"})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Validation failed"
                            (edge/update-edge! (:id edge) {:type "invalid-type"}))))))
