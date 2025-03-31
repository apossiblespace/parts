(ns parts.frontend.state.system-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [parts.frontend.state.system :as state]
   [parts.common.models.part :as part]
   [parts.common.models.relationship :as relationship]))

(def sample-system-id "test-system-id")

(def sample-part-1
  (part/make-part {:system_id sample-system-id
                   :type "manager"
                   :label "Test Manager"
                   :position_x 100
                   :position_y 100}))

(def sample-part-2
  (part/make-part {:system_id sample-system-id
                   :type "exile"
                   :label "Test Exile"
                   :position_x 200
                   :position_y 200}))

(def sample-relationship
  (relationship/make-relationship {:system_id sample-system-id
                                   :source_id (:id sample-part-1)
                                   :target_id (:id sample-part-2)
                                   :type "protective"}))

(def initial-state
  (state/create-system-state
   {:id sample-system-id
    :parts [sample-part-1 sample-part-2]
    :relationships [sample-relationship]}))

(deftest create-system-state-test
  (testing "Creates normalized state from parts and relationships"
    (let [state (state/create-system-state
                 {:id "test-id"
                  :parts [sample-part-1]
                  :relationships [sample-relationship]})]
      (is (= "test-id" (:system-id state)))
      (is (= 1 (count (:parts state))))
      (is (= 1 (count (:relationships state))))
      (is (= sample-part-1 (get-in state [:parts (:id sample-part-1)])))
      (is (= sample-relationship (get-in state [:relationships (:id sample-relationship)]))))))

(deftest get-part-test
  (testing "Gets a part by id"
    (is (= sample-part-1 (state/get-part initial-state (:id sample-part-1)))))

  (testing "Returns nil for non-existent part"
    (is (nil? (state/get-part initial-state "non-existent-id")))))

(deftest add-part-test
  (testing "Adds a new part to the state"
    (let [attrs {:type "firefighter"
                 :label "New Firefighter"
                 :position_x 300
                 :position_y 300}
          result (state/add-part initial-state attrs)
          new-part-id (some (fn [[id part]]
                              (when (= "New Firefighter" (:label part)) id))
                            (:parts result))]
      (is (= 3 (count (:parts result))))
      (is (some? new-part-id))
      (let [new-part (get-in result [:parts new-part-id])]
        (is (= "firefighter" (:type new-part)))
        (is (= "New Firefighter" (:label new-part)))
        (is (= 300 (:position_x new-part)))
        (is (= 300 (:position_y new-part)))
        (is (= sample-system-id (:system_id new-part)))))))

(deftest update-part-test
  (testing "Updates part attributes"
    (let [part-id (:id sample-part-1)
          updated-state (state/update-part initial-state part-id
                                           {:label "Updated Manager"
                                            :position_x 150})
          updated-part (get-in updated-state [:parts part-id])]
      (is (= "Updated Manager" (:label updated-part)))
      (is (= 150 (:position_x updated-part)))
      (is (= 100 (:position_y updated-part))) ; unchanged
      (is (= "manager" (:type updated-part))))) ; unchanged

  (testing "No-op when part doesn't exist"
    (let [result (state/update-part initial-state "non-existent-id" {:label "Won't Update"})]
      (is (= initial-state result))))

  (testing "Throws exception when update would create invalid part"
    (is (thrown? js/Error
                 (state/update-part initial-state (:id sample-part-1) {:type "invalid-type"})))))

(deftest update-part-position-test
  (testing "Updates part position from map coordinates"
    (let [part-id (:id sample-part-1)
          position {:x 250.5 :y 350.75}
          updated-state (state/update-part-position initial-state part-id position)
          updated-part (get-in updated-state [:parts part-id])]
      (is (= 250 (:position_x updated-part))) ; should be integer
      (is (= 350 (:position_y updated-part))) ; should be integer
      (is (= "Test Manager" (:label updated-part))) ; unchanged
      (is (= "manager" (:type updated-part)))))) ; unchanged

(deftest remove-part-test
  (testing "Removes a part from the state"
    (let [part-id (:id sample-part-1)
          result (state/remove-part initial-state part-id)]
      (is (= 1 (count (:parts result))))
      (is (nil? (get-in result [:parts part-id])))))

  (testing "Removes relationships connected to the removed part"
    (let [part-id (:id sample-part-1)
          result (state/remove-part initial-state part-id)]
      (is (= 0 (count (:relationships result))))
      (is (nil? (get-in result [:relationships (:id sample-relationship)])))))

  (testing "No-op when part doesn't exist"
    (let [result (state/remove-part initial-state "non-existent-id")]
      (is (= initial-state result)))))

(deftest get-relationship-test
  (testing "Gets a relationship by id"
    (is (= sample-relationship
           (state/get-relationship initial-state (:id sample-relationship)))))

  (testing "Returns nil for non-existent relationship"
    (is (nil? (state/get-relationship initial-state "non-existent-id")))))

(deftest add-relationship-test
  (testing "Adds a new relationship to the state"
    (let [attrs {:source_id (:id sample-part-2)
                 :target_id (:id sample-part-1)
                 :type "alliance"}
          result (state/add-relationship initial-state attrs)
          new-rel-id (some (fn [[id rel]]
                             (when (and (= (:id sample-part-2) (:source_id rel))
                                        (= "alliance" (:type rel)))
                               id))
                           (:relationships result))]
      (is (= 2 (count (:relationships result))))
      (is (some? new-rel-id))
      (let [new-rel (get-in result [:relationships new-rel-id])]
        (is (= "alliance" (:type new-rel)))
        (is (= (:id sample-part-2) (:source_id new-rel)))
        (is (= (:id sample-part-1) (:target_id new-rel)))
        (is (= sample-system-id (:system_id new-rel)))))))

(deftest update-relationship-test
  (testing "Updates relationship attributes"
    (let [rel-id (:id sample-relationship)
          updated-state (state/update-relationship initial-state rel-id {:type "burden"})
          updated-rel (get-in updated-state [:relationships rel-id])]
      (is (= "burden" (:type updated-rel)))
      (is (= (:source_id sample-relationship) (:source_id updated-rel))) ; unchanged
      (is (= (:target_id sample-relationship) (:target_id updated-rel))))) ; unchanged

  (testing "No-op when relationship doesn't exist"
    (let [result (state/update-relationship initial-state "non-existent-id" {:type "burden"})]
      (is (= initial-state result))))

  (testing "Throws exception when update would create invalid relationship"
    (is (thrown? js/Error
                 (state/update-relationship initial-state
                                            (:id sample-relationship)
                                            {:type "invalid-type"})))))

(deftest remove-relationship-test
  (testing "Removes a relationship from the state"
    (let [rel-id (:id sample-relationship)
          result (state/remove-relationship initial-state rel-id)]
      (is (= 0 (count (:relationships result))))
      (is (nil? (get-in result [:relationships rel-id])))))

  (testing "No-op when relationship doesn't exist"
    (let [result (state/remove-relationship initial-state "non-existent-id")]
      (is (= initial-state result)))))

(deftest reactflow-conversion-test
  (testing "Converts parts to ReactFlow nodes"
    (let [nodes (state/get-reactflow-nodes initial-state)
          node-ids (set (map #(.-id %) nodes))]
      (is (= 2 (count nodes)))
      (is (contains? node-ids (:id sample-part-1)))
      (is (contains? node-ids (:id sample-part-2)))))

  (testing "Converts relationships to ReactFlow edges"
    (let [edges (state/get-reactflow-edges initial-state)
          edge-ids (set (map #(.-id %) edges))]
      (is (= 1 (count edges)))
      (is (contains? edge-ids (:id sample-relationship))))))

(deftest event-preparation-test
  (testing "Prepares part creation event"
    (let [event (state/prepare-part-create-event sample-part-1)]
      (is (= (:id sample-part-1) (:id event)))
      (is (= "create" (:type event)))
      (is (= "manager" (get-in event [:data :type])))
      (is (= "Test Manager" (get-in event [:data :label])))))

  (testing "Prepares part update event"
    (let [attrs {:label "New Label" :type "firefighter"}
          event (state/prepare-part-update-event (:id sample-part-1) attrs)]
      (is (= (:id sample-part-1) (:id event)))
      (is (= "update" (:type event)))
      (is (= attrs (:data event)))))

  (testing "Prepares part remove event"
    (let [event (state/prepare-part-remove-event (:id sample-part-1))]
      (is (= (:id sample-part-1) (:id event)))
      (is (= "remove" (:type event)))
      (is (= {} (:data event)))))

  (testing "Prepares part position event"
    (let [position {:x 300 :y 400}
          event (state/prepare-part-position-event (:id sample-part-1) position)]
      (is (= (:id sample-part-1) (:id event)))
      (is (= "position" (:type event)))
      (is (= position (:position event)))
      (is (= false (:dragging event)))))

  (testing "Prepares relationship creation event"
    (let [event (state/prepare-relationship-create-event sample-relationship)]
      (is (= (:id sample-relationship) (:id event)))
      (is (= "create" (:type event)))
      (is (= "protective" (get-in event [:data :type])))
      (is (= (:source_id sample-relationship) (get-in event [:data :source_id])))
      (is (= (:target_id sample-relationship) (get-in event [:data :target_id])))))

  (testing "Prepares relationship update event"
    (let [attrs {:type "alliance"}
          event (state/prepare-relationship-update-event (:id sample-relationship) attrs)]
      (is (= (:id sample-relationship) (:id event)))
      (is (= "update" (:type event)))
      (is (= attrs (:data event)))))

  (testing "Prepares relationship remove event"
    (let [event (state/prepare-relationship-remove-event (:id sample-relationship))]
      (is (= (:id sample-relationship) (:id event)))
      (is (= "remove" (:type event)))
      (is (= {} (:data event))))))
