(ns aps.parts.frontend.state.map-updates-test
  (:require
   [aps.parts.frontend.state.map-updates :as map-updates]
   [cljs.test :refer-macros [deftest is testing]]))

(deftest rename-map-test
  (let [db {:map {:id            "map-1"
                  :title         "Anxious thoughts"
                  :parts         [{:id "p1"}]
                  :relationships [{:id "r1"}]}}
        fx (map-updates/rename-map db "Work-related anxiety")]
    (testing "the title updates optimistically in :map"
      (is (= "Work-related anxiety" (get-in fx [:db :map :title]))))
    (testing "the previous title is stashed for rollback"
      (is (= "Anxious thoughts" (get-in fx [:db :maps :rename-rollback]))))
    (testing "Parts and Relationships are left untouched"
      (is (= [{:id "p1"}] (get-in fx [:db :map :parts])))
      (is (= [{:id "r1"}] (get-in fx [:db :map :relationships]))))
    (testing "the update is persisted via :storage/update-map"
      (is (= {:id "map-1" :map-data {:title "Work-related anxiety"}}
             (:storage/update-map fx))))))

(deftest apply-map-update-test
  (testing "a metadata-only server response merges into :map without wiping the canvas"
    ;; entity/map/update! returns metadata only (ADR-0002). A full replace
    ;; here would blank every Part and Relationship off the canvas.
    (let [db          {:map  {:id            "map-1"
                              :title         "Work-related anxiety"
                              :parts         [{:id "p1"}]
                              :relationships [{:id "r1"}]}
                       :maps {:rename-rollback "Anxious thoughts"}}
          updated-map {:id "map-1" :title "Work-related anxiety"}
          result      (map-updates/apply-map-update db updated-map)]
      (is (= [{:id "p1"}] (get-in result [:map :parts]))
          "Parts survive the metadata update")
      (is (= [{:id "r1"}] (get-in result [:map :relationships]))
          "Relationships survive the metadata update")
      (is (= "Work-related anxiety" (get-in result [:map :title])))
      (is (nil? (get-in result [:maps :rename-rollback]))
          "the rollback is cleared once the update lands")))

  (testing "the matching Maps list entry is synced"
    (let [db     {:map  {:id "map-1" :title "New"}
                  :maps {:list [{:id "map-1" :title "Old"}
                                {:id "map-2" :title "Other"}]}}
          result (map-updates/apply-map-update db {:id "map-1" :title "New"})]
      (is (= [{:id "map-1" :title "New"}
              {:id "map-2" :title "Other"}]
             (get-in result [:maps :list])))))

  (testing "a nil Maps list stays nil (not coerced to an empty vector)"
    (let [result (map-updates/apply-map-update {:map {:id "map-1"}}
                                               {:id "map-1" :title "New"})]
      (is (nil? (get-in result [:maps :list]))))))

(deftest revert-map-update-test
  (testing "a failed update rolls the title back to the stashed value"
    (let [db     {:map  {:id "map-1" :title "Work-related anxiety"}
                  :maps {:rename-rollback "Anxious thoughts"}}
          result (map-updates/revert-map-update db "Failed to update map")]
      (is (= "Anxious thoughts" (get-in result [:map :title]))
          "the optimistic title is reverted")
      (is (= "Failed to update map" (get-in result [:maps :error])))
      (is (nil? (get-in result [:maps :rename-rollback]))
          "the rollback is consumed"))))
