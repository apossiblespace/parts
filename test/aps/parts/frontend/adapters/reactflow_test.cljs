(ns aps.parts.frontend.adapters.reactflow-test
  (:require
   [aps.parts.common.models.part :as part]
   [aps.parts.common.models.relationship :as relationship]
   [aps.parts.frontend.adapters.reactflow :as adapter]
   [cljs.test :refer-macros [deftest is testing]]))

(deftest part->node-test
  (testing "Converts part to node with correct structure"
    (let [part   {:id         "part-123"
                  :type       "manager"
                  :label      "Test Manager"
                  :position_x 100
                  :position_y 200}
          result (js->clj (adapter/part->node part) :keywordize-keys true)]

      (is (= "part-123" (:id result)))
      (is (= 100 (get-in result [:position :x])))
      (is (= 200 (get-in result [:position :y])))
      (is (= "Test Manager" (get-in result [:data :label])))
      (is (= "manager" (get-in result [:data :type]))))))

(deftest parts->nodes-test
  (testing "Converts collection of parts to array of nodes"
    (let [parts  [{:id "part-1" :type "manager" :label "Manager" :position_x 100 :position_y 100}
                  {:id "part-2" :type "exile" :label "Exile" :position_x 200 :position_y 200}]
          result (adapter/parts->nodes parts)]

      (is (array? result))
      (is (= 2 (.-length result)))

      (let [first-node (js->clj (aget result 0) :keywordize-keys true)]
        (is (= "part-1" (:id first-node)))
        (is (= "manager" (get-in first-node [:data :type])))))))

(deftest node->part-test
  (testing "Converts node to part with correct structure"
    (let [node      #js {:id       "node-123"
                         :position #js {:x 150 :y 250}
                         :data     #js {:label "Test Node" :type "firefighter"}}
          system-id "system-456"
          result    (adapter/node->part node system-id)]

      (is (= "node-123" (:id result)))
      (is (= system-id (:system_id result)))
      (is (= "firefighter" (:type result)))
      (is (= "Test Node" (:label result)))
      (is (= 150 (:position_x result)))
      (is (= 250 (:position_y result))))))

(deftest relationship->edge-test
  (testing "Converts relationship to edge with correct structure"
    (let [rel    {:id        "rel-123"
                  :source_id "source-123"
                  :target_id "target-456"
                  :type      "protective"}
          result (js->clj (adapter/relationship->edge rel) :keywordize-keys true)]

      (is (= "rel-123" (:id result)))
      (is (= "source-123" (:source result)))
      (is (= "target-456" (:target result)))
      (is (= "protective" (get-in result [:data :relationship])))
      (is (= "edge-protective" (:className result))))))

(deftest relationships->edges-test
  (testing "Converts collection of relationships to array of edges"
    (let [rels   [{:id "rel-1" :source_id "s1" :target_id "t1" :type "protective"}
                  {:id "rel-2" :source_id "s2" :target_id "t2" :type "polarization"}]
          result (adapter/relationships->edges rels)]

      (is (array? result))
      (is (= 2 (.-length result)))

      (let [first-edge (js->clj (aget result 0) :keywordize-keys true)]
        (is (= "rel-1" (:id first-edge)))
        (is (= "s1" (:source first-edge)))
        (is (= "t1" (:target first-edge)))
        (is (= "protective" (get-in first-edge [:data :relationship])))))))

(deftest edge->relationship-test
  (testing "Converts edge to relationship with correct structure"
    (let [edge      #js {:id     "edge-123"
                         :source "source-123"
                         :target "target-456"
                         :data   #js {:relationship "alliance"}}
          system-id "system-789"
          result    (adapter/edge->relationship edge system-id)]

      (is (= "edge-123" (:id result)))
      (is (= system-id (:system_id result)))
      (is (= "source-123" (:source_id result)))
      (is (= "target-456" (:target_id result)))
      (is (= "alliance" (:type result))))))

(deftest bidirectional-conversion-test
  (testing "Part -> Node -> Part conversion preserves data"
    (let [original-part (part/make-part {:id         "test-id"
                                         :system_id  "system-id"
                                         :type       "manager"
                                         :label      "Test Manager"
                                         :position_x 100
                                         :position_y 200})
          node          (adapter/part->node original-part)
          result        (adapter/node->part node "system-id")]

      (is (= original-part result))))

  (testing "Relationship -> Edge -> Relationship conversion preserves data"
    (let [original-rel (relationship/make-relationship {:id        "test-id"
                                                        :system_id "system-id"
                                                        :type      "protective"
                                                        :source_id "source-id"
                                                        :target_id "target-id"})
          edge         (adapter/relationship->edge original-rel)
          result       (adapter/edge->relationship edge "system-id")]

      (is (= original-rel result)))))
