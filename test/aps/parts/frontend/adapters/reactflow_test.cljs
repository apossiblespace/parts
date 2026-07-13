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
    (let [node   #js {:id       "node-123"
                      :position #js {:x 150 :y 250}
                      :data     #js {:label "Test Node" :type "firefighter"}}
          map-id "map-456"
          result (adapter/node->part node map-id)]

      (is (= "node-123" (:id result)))
      (is (= map-id (:map_id result)))
      (is (= "firefighter" (:type result)))
      (is (= "Test Node" (:label result)))
      (is (= 150 (:position_x result)))
      (is (= 250 (:position_y result))))))

(deftest relationship->edge-test
  (testing "Converts relationship to edge with correct structure"
    (let [rel    {:id        "rel-123"
                  :source_id "source-123"
                  :target_id "target-456"
                  :type      "protects"}
          result (js->clj (adapter/relationship->edge rel) :keywordize-keys true)]

      (is (= "rel-123" (:id result)))
      (is (= "source-123" (:source result)))
      (is (= "target-456" (:target result)))
      (is (= "protects" (get-in result [:data :relationship])))
      (is (= "edge-protects" (:className result))))))

(deftest parts->nodes-canvas-opts-test
  (let [parts [{:id         "p1" :type       "unknown" :label "A"
                :position_x 0    :position_y 0}
               {:id         "p2" :type       "unknown" :label "B"
                :position_x 10   :position_y 10}]]
    (testing "the resize-armed decision threads into every node's data"
      (let [nodes (adapter/parts->nodes parts ["p1"] {:resizable? true})]
        (is (true? (.. (aget nodes 0) -data -resizable)))
        (is (true? (.. (aget nodes 1) -data -resizable)))))

    (testing "without opts, nodes are not resizable"
      (let [nodes (adapter/parts->nodes parts)]
        (is (false? (.. (aget nodes 0) -data -resizable)))))))

(deftest recency-badge-data-test
  (testing "first_appeared_ordinal threads into node data; a Part born in
            the viewed Session is marked recent (the accented badge)"
    (let [parts [{:id                     "p1" :type       "unknown" :label "old"
                  :position_x             0    :position_y 0
                  :first_appeared_ordinal 1}
                 {:id                     "p2" :type       "unknown" :label "new"
                  :position_x             0    :position_y 0
                  :first_appeared_ordinal 3}]
          nodes (adapter/parts->nodes parts nil {:session-badges? true
                                                 :viewed-ordinal  3})]
      (is (= 1 (.. (aget nodes 0) -data -firstAppeared)))
      (is (false? (.. (aget nodes 0) -data -recent)))
      (is (true? (.. (aget nodes 1) -data -recent)))))

  (testing "a single-Session Map shows no badges — every element would
            read 'S1'; the ordinal is withheld even though the row has it"
    (let [nodes (adapter/parts->nodes [{:id                     "p1"
                                        :type                   "unknown"
                                        :label                  "only"
                                        :position_x             0
                                        :position_y             0
                                        :first_appeared_ordinal 1}]
                                      nil {:viewed-ordinal 1})]
      (is (nil? (.. (aget nodes 0) -data -firstAppeared)))
      (is (false? (.. (aget nodes 0) -data -recent)))))

  (testing "a row without the ordinal (demo Maps: no Sessions) carries no
            badge and is never recent"
    (let [nodes (adapter/parts->nodes [{:id         "p1"   :type       "unknown"
                                        :label      "demo"
                                        :position_x 0      :position_y 0}]
                                      nil {:session-badges? true
                                           :viewed-ordinal  nil})]
      (is (nil? (.. (aget nodes 0) -data -firstAppeared)))
      (is (false? (.. (aget nodes 0) -data -recent)))))

  (testing "the viewed Session's activated Part wears the marker with the
            trigger text; other Parts don't — and the marker is independent
            of the badge gate (a single-Session Map can have an activation)"
    (let [parts [{:id         "p1" :type       "unknown" :label "activated"
                  :position_x 0    :position_y 0}
                 {:id         "p2" :type       "unknown" :label "bystander"
                  :position_x 0    :position_y 0}]
          nodes (adapter/parts->nodes parts nil
                                      {:activated-part-id  "p1"
                                       :activation-trigger "conflict at work"})]
      (is (true? (.. (aget nodes 0) -data -activated)))
      (is (= "conflict at work" (.. (aget nodes 0) -data -activationTrigger)))
      (is (false? (.. (aget nodes 1) -data -activated)))
      (is (nil? (.. (aget nodes 1) -data -activationTrigger)))))

  (testing "no activation in the viewed Session — no markers"
    (let [nodes (adapter/parts->nodes [{:id         "p1" :type       "unknown"
                                        :label      "p"
                                        :position_x 0    :position_y 0}]
                                      nil {:activated-part-id nil})]
      (is (false? (.. (aget nodes 0) -data -activated)))))

  (testing "edges carry the same badge data, gated the same way"
    (let [rel   {:id                     "r1"       :source_id "a" :target_id "b"
                 :type                   "protects"
                 :first_appeared_ordinal 2}
          badge (adapter/relationships->edges
                 [rel] nil {:session-badges? true :viewed-ordinal 2})
          bare  (adapter/relationships->edges
                 [rel] nil {:viewed-ordinal 2})]
      (is (= 2 (.. (aget badge 0) -data -firstAppeared)))
      (is (true? (.. (aget badge 0) -data -recent)))
      (is (nil? (.. (aget bare 0) -data -firstAppeared)))
      (is (false? (.. (aget bare 0) -data -recent))))))

(deftest relationships->edges-test
  (testing "Converts collection of relationships to array of edges"
    (let [rels   [{:id "rel-1" :source_id "s1" :target_id "t1" :type "protects"}
                  {:id "rel-2" :source_id "s2" :target_id "t2" :type "polarizes-with"}]
          result (adapter/relationships->edges rels)]

      (is (array? result))
      (is (= 2 (.-length result)))

      (let [first-edge (js->clj (aget result 0) :keywordize-keys true)]
        (is (= "rel-1" (:id first-edge)))
        (is (= "s1" (:source first-edge)))
        (is (= "t1" (:target first-edge)))
        (is (= "protects" (get-in first-edge [:data :relationship])))))))

(deftest edge->relationship-test
  (testing "Converts edge to relationship with correct structure"
    (let [edge   #js {:id     "edge-123"
                      :source "source-123"
                      :target "target-456"
                      :data   #js {:relationship "works-with"}}
          map-id "map-789"
          result (adapter/edge->relationship edge map-id)]

      (is (= "edge-123" (:id result)))
      (is (= map-id (:map_id result)))
      (is (= "source-123" (:source_id result)))
      (is (= "target-456" (:target_id result)))
      (is (= "works-with" (:type result))))))

(deftest bidirectional-conversion-test
  (testing "Part -> Node -> Part conversion preserves data"
    (let [original-part (part/make-part {:id         "test-id"
                                         :map_id     "map-id"
                                         :type       "manager"
                                         :label      "Test Manager"
                                         :position_x 100
                                         :position_y 200})
          node          (adapter/part->node original-part)
          result        (adapter/node->part node "map-id")]

      (is (= original-part result))))

  (testing "Relationship -> Edge -> Relationship conversion preserves data"
    (let [original-rel (relationship/make-relationship {:id        "test-id"
                                                        :map_id    "map-id"
                                                        :type      "protects"
                                                        :source_id "source-id"
                                                        :target_id "target-id"})
          edge         (adapter/relationship->edge original-rel)
          result       (adapter/edge->relationship edge "map-id")]

      (is (= original-rel result)))))

(deftest translate-nodes-change-test
  (testing "a position change while dragging yields only a frame intent"
    (let [changes #js [#js {:type     "position"
                            :id       "p1"
                            :position #js {:x 50 :y 75}
                            :dragging true}]]
      (is (= [{:intent :part-position-frame :id "p1" :position {:x 50 :y 75}}]
             (adapter/translate-nodes-change changes)))))

  (testing "a settled position change yields a frame and a moved intent, in order"
    (let [changes #js [#js {:type     "position"
                            :id       "p1"
                            :position #js {:x 50 :y 75}
                            :dragging false}]]
      (is (= [{:intent :part-position-frame :id "p1" :position {:x 50 :y 75}}
              {:intent :part-moved :id "p1" :position {:x 50 :y 75}}]
             (adapter/translate-nodes-change changes)))))

  (testing "a position change without a :position is skipped"
    (let [changes #js [#js {:type "position" :id "p1"}]]
      (is (= [] (adapter/translate-nodes-change changes)))))

  (testing "select translates to :part-selected"
    (let [changes #js [#js {:type "select" :id "p1" :selected true}]]
      (is (= [{:intent :part-selected :id "p1" :selected? true}]
             (adapter/translate-nodes-change changes)))))

  (testing "a marquee lands as a batch of selects — one intent per Part"
    (let [changes #js [#js {:type "select" :id "p1" :selected true}
                       #js {:type "select" :id "p2" :selected true}
                       #js {:type "select" :id "p3" :selected true}]]
      (is (= [{:intent :part-selected :id "p1" :selected? true}
              {:intent :part-selected :id "p2" :selected? true}
              {:intent :part-selected :id "p3" :selected? true}]
             (adapter/translate-nodes-change changes)))))

  (testing "dragging a selection moves every member — a settled multi-node
            batch commits a :part-moved per Part"
    (let [changes #js [#js {:type     "position"
                            :id       "p1"
                            :position #js {:x 10 :y 20}
                            :dragging false}
                       #js {:type     "position"
                            :id       "p2"
                            :position #js {:x 110 :y 120}
                            :dragging false}]]
      (is (= [{:intent :part-position-frame :id "p1" :position {:x 10 :y 20}}
              {:intent :part-moved :id "p1" :position {:x 10 :y 20}}
              {:intent :part-position-frame :id "p2" :position {:x 110 :y 120}}
              {:intent :part-moved :id "p2" :position {:x 110 :y 120}}]
             (adapter/translate-nodes-change changes)))))

  (testing "remove translates to :part-removed"
    (let [changes #js [#js {:type "remove" :id "p1"}]]
      (is (= [{:intent :part-removed :id "p1"}]
             (adapter/translate-nodes-change changes)))))

  (testing "a mixed batch yields a flat intent vector preserving order"
    (let [changes #js [#js {:type "select" :id "p1" :selected true}
                       #js {:type     "position"
                            :id       "p2"
                            :position #js {:x 10 :y 20}
                            :dragging false}
                       #js {:type "remove" :id "p3"}]]
      (is (= [{:intent :part-selected :id "p1" :selected? true}
              {:intent :part-position-frame :id "p2" :position {:x 10 :y 20}}
              {:intent :part-moved :id "p2" :position {:x 10 :y 20}}
              {:intent :part-removed :id "p3"}]
             (adapter/translate-nodes-change changes)))))

  (testing "unhandled change types are dropped (e.g. ReactFlow's `dimensions`)"
    (let [changes #js [#js {:type "dimensions" :id "p1"}]]
      (is (= [] (adapter/translate-nodes-change changes))))))

(deftest translate-edges-change-test
  (testing "select translates to :relationship-selected"
    (let [changes #js [#js {:type "select" :id "e1" :selected true}]]
      (is (= [{:intent :relationship-selected :id "e1" :selected? true}]
             (adapter/translate-edges-change changes)))))

  (testing "remove translates to :relationship-removed"
    (let [changes #js [#js {:type "remove" :id "e1"}]]
      (is (= [{:intent :relationship-removed :id "e1"}]
             (adapter/translate-edges-change changes))))))

(deftest translate-connect-test
  (testing "a connection translates to a :relationship-connected intent"
    (let [conn #js {:source "p1" :target "p2"}]
      (is (= {:intent :relationship-connected :source_id "p1" :target_id "p2"}
             (adapter/translate-connect conn))))))

(deftest translate-nodes-change-resize-test
  (testing "a resize frame yields :part-resize-frame with live dimensions"
    (let [changes #js [#js {:id         "p1"
                            :type       "dimensions"
                            :resizing   true
                            :dimensions #js {:width 150.4 :height 150.4}}]]
      (is (= [{:intent     :part-resize-frame
               :id         "p1"
               :dimensions {:width 150.4 :height 150.4}}]
             (adapter/translate-nodes-change changes)))))

  (testing "resize end yields :part-resized with the final dimensions"
    (let [changes #js [#js {:id         "p1"
                            :type       "dimensions"
                            :resizing   false
                            :dimensions #js {:width 180 :height 180}}]]
      (is (= [{:intent     :part-resized
               :id         "p1"
               :dimensions {:width 180 :height 180}}]
             (adapter/translate-nodes-change changes)))))

  (testing "a measurement-only dimensions change (no :resizing flag) is ignored"
    (is (= [] (adapter/translate-nodes-change
               #js [#js {:id         "p1"
                         :type       "dimensions"
                         :dimensions #js {:width 100 :height 100}}]))))

  (testing "position changes in a resize batch don't emit :part-moved —
            NodeResizer's position changes carry no :dragging flag, and a
            per-frame :part-moved would write a bitemporal row per frame"
    (let [changes #js [#js {:id       "p1"
                            :type     "position"
                            :position #js {:x 10 :y 12}}
                       #js {:id         "p1"
                            :type       "dimensions"
                            :resizing   true
                            :dimensions #js {:width 150 :height 150}}]
          intents (adapter/translate-nodes-change changes)]
      (is (= #{:part-position-frame :part-resize-frame}
             (set (map :intent intents))))))

  (testing "a plain drag end (explicit :dragging false) still emits :part-moved"
    (let [changes #js [#js {:id       "p1"
                            :type     "position"
                            :dragging false
                            :position #js {:x 10 :y 12}}]
          intents (adapter/translate-nodes-change changes)]
      (is (= [:part-position-frame :part-moved] (mapv :intent intents))))))
