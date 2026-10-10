(ns aps.parts.frontend.components.maps-list-view-test
  (:require
   [aps.parts.frontend.components.maps-list-view :as view]
   [cljs.test :refer [deftest is testing]]))

(def ^:private maps
  [{:id "a"}
   {:id "b" :archived_at "2026-10-01T10:00:00Z"}
   {:id "c"}
   {:id "d" :archived_at "2026-10-09T10:00:00Z"}])

(deftest test-view
  (is (= :archived (view/view {:view "archived"})))
  (is (= :active (view/view {})))
  (is (= :active (view/view {:view "something"}))))

(deftest test-maps-in-view
  (testing "Active shows the Maps that are not archived, in list order"
    (is (= ["a" "c"] (map :id (view/maps-in-view maps :active)))))
  (testing "Archived shows archived Maps, newest archive first"
    (is (= ["d" "b"] (map :id (view/maps-in-view maps :archived))))))
