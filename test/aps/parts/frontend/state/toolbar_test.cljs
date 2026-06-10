(ns aps.parts.frontend.state.toolbar-test
  (:require
   [aps.parts.frontend.state.toolbar :as toolbar]
   [cljs.test :refer-macros [deftest is testing]]))

(deftest relationship-create-attrs-test
  (testing "the selected type is threaded into the new Relationship"
    (let [db {:map {:id "map-1"}
              :ui  {:relationship-type :protective}}]
      (is (= {:map_id    "map-1"
              :type      "protective"
              :source_id "p1"
              :target_id "p2"}
             (toolbar/relationship-create-attrs
              db {:source_id "p1" :target_id "p2"})))))

  (testing "defaults to unknown when no type was ever selected"
    (is (= "unknown"
           (:type (toolbar/relationship-create-attrs
                   {:map {:id "map-1"}}
                   {:source_id "p1" :target_id "p2"}))))))

(deftest tool-mode-after-create-test
  (testing "one-shot: placing a Part disarms the tool"
    (is (nil? (toolbar/tool-mode-after-create :add-exile false))))

  (testing "shift-click keeps the tool armed for batch adds"
    (is (= :add-exile (toolbar/tool-mode-after-create :add-exile true)))))
