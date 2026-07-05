(ns aps.parts.common.geometry-test
  (:require
   [aps.parts.common.geometry :as g]
   [cljs.test :refer-macros [deftest is testing]]))

(defn- approx=
  ([a b] (approx= a b 0.001))
  ([a b epsilon] (< (Math/abs (- a b)) epsilon)))

(deftest shape-for-test
  (testing "known part types resolve to the expected shape"
    (is (= :circle (g/shape-for "exile")))
    (is (vector? (g/shape-for "unknown")))
    (is (vector? (g/shape-for "manager")))
    (is (vector? (g/shape-for "firefighter"))))
  (testing "unknown type defaults to the unit square so unfamiliar SVGs degrade cleanly"
    (is (= (g/shape-for "unknown") (g/shape-for "nonexistent"))))
  (testing "unknown square has 4 vertices"
    (is (= 4 (count (g/shape-for "unknown")))))
  (testing "manager hex has 8 vertices (6-corner hex with split top and bottom edges)"
    (is (= 8 (count (g/shape-for "manager")))))
  (testing "firefighter sparkle has 16 vertices (8 outer + 8 inner)"
    (is (= 16 (count (g/shape-for "firefighter"))))))

(deftest circle-ray-intersection-test
  (testing "ray exits along +x axis"
    (let [{:keys [x y]} (g/circle-ray-intersection 100 100 50 200 100)]
      (is (approx= 150 x))
      (is (approx= 100 y))))
  (testing "ray exits along +y axis (down is positive in screen coords)"
    (let [{:keys [x y]} (g/circle-ray-intersection 100 100 50 100 200)]
      (is (approx= 100 x))
      (is (approx= 150 y))))
  (testing "ray exits at 45 degrees"
    (let [{:keys [x y]} (g/circle-ray-intersection 0 0 1 1 1)
          k             (/ 1 (Math/sqrt 2))]
      (is (approx= k x))
      (is (approx= k y))))
  (testing "degenerate (other = centre) returns centre rather than NaN"
    (let [{:keys [x y]} (g/circle-ray-intersection 50 50 10 50 50)]
      (is (= 50 x))
      (is (= 50 y)))))

(deftest polygon-ray-intersection-test
  (testing "horizontal ray exits a square at the right edge midpoint"
    (let [square        [[0 0] [100 0] [100 100] [0 100]]
          {:keys [x y]} (g/polygon-ray-intersection square 50 50 150 0)]
      (is (approx= 100 x))
      (is (approx= 50 y))))
  (testing "horizontal ray crosses vertical hex side at the midpoint"
    (let [hex           [[50 0] [100 25] [100 75] [50 100] [0 75] [0 25]]
          {:keys [x y]} (g/polygon-ray-intersection hex 50 50 200 0)]
      (is (approx= 100 x))
      (is (approx= 50 y))))
  (testing "returns nil when ray misses every segment"
    (is (nil? (g/polygon-ray-intersection
               [[0 0] [10 0] [10 10] [0 10]]
               -100 -100 -1 -1))))
  (testing "polygon with fewer than 3 vertices returns nil"
    (is (nil? (g/polygon-ray-intersection [[0 0] [10 10]] 5 5 1 0))))
  (testing "degenerate ray (dx=dy=0) returns the centre — parity with circle"
    (let [{:keys [x y]} (g/polygon-ray-intersection
                         [[0 0] [10 0] [10 10] [0 10]]
                         5 5 0 0)]
      (is (= 5 x))
      (is (= 5 y)))))

(deftest intersection-for-shape-test
  (testing ":circle dispatches to circle math (inscribed in box)"
    (let [{:keys [x y]} (g/intersection-for-shape :circle 0 0 100 100 200 50)]
      (is (approx= 100 x))
      (is (approx= 50 y))))
  (testing "unit-square polygon is scaled to the node's measured box"
    (let [{:keys [x y]} (g/intersection-for-shape
                         (g/shape-for "unknown") 0 0 100 100 200 50)]
      (is (approx= 100 x))
      (is (approx= 50 y))))
  (testing "real manager hex: horizontal ray hits the right vertical side"
    (let [{:keys [x y]} (g/intersection-for-shape
                         (g/shape-for "manager") 0 0 100 100 500 50)]
      ;; manager hex has a vertical right segment from (100, 28.43) to (100, 74.33);
      ;; midline (y=50) falls inside that segment, so the hit is at x=100.
      (is (approx= 100 x))
      (is (approx= 50 y)))))

(deftest classify-side-test
  (testing "cardinal directions"
    (is (= :right  (g/classify-side {:x 100 :y 50} 50 50)))
    (is (= :left   (g/classify-side {:x 0 :y 50} 50 50)))
    (is (= :bottom (g/classify-side {:x 50 :y 100} 50 50)))
    (is (= :top    (g/classify-side {:x 50 :y 0} 50 50))))
  (testing "diagonals fall into one of the cardinal regions (boundary behaviour)"
    (is (#{:right :top}    (g/classify-side {:x 100 :y 0} 50 50)))
    (is (#{:right :bottom} (g/classify-side {:x 100 :y 100} 50 50)))
    (is (#{:left :bottom}  (g/classify-side {:x 0 :y 100} 50 50)))
    (is (#{:left :top}     (g/classify-side {:x 0 :y 0} 50 50)))))

(deftest segment-intersects-rect?-test
  (let [rect {:x 100 :y 100 :width 100 :height 100}]
    (testing "an endpoint inside the rect is a hit"
      (is (true? (g/segment-intersects-rect? [150 150] [400 400] rect))))
    (testing "a segment crossing straight through (both ends outside) is a hit"
      (is (true? (g/segment-intersects-rect? [0 150] [300 150] rect))))
    (testing "a segment passing beside the rect misses"
      (is (false? (g/segment-intersects-rect? [0 0] [300 0] rect))))
    (testing "a segment entirely on one side misses"
      (is (false? (g/segment-intersects-rect? [0 300] [50 400] rect))))))

(deftest marquee-hit-relationship-ids-test
  ;; Two square Parts stacked vertically: the chord between them runs from
  ;; (50,100) — p1's bottom border — straight down to (50,300) — p2's top.
  (let [parts [{:id    "p1" :type   "unknown" :position_x 0 :position_y 0
                :width 100  :height 100}
               {:id    "p2" :type   "unknown" :position_x 0 :position_y 300
                :width 100  :height 100}]
        rels  [{:id "r1" :source_id "p1" :target_id "p2" :type "unknown"}]]
    (testing "a rect crossing the drawn chord hits the relationship"
      (is (= #{"r1"} (g/marquee-hit-relationship-ids
                      parts rels {:x 0 :y 150 :width 200 :height 50}))))
    (testing "a rect overlapping only a Part's body does NOT hit its edges —
              the chord starts at the border, not the centre"
      (is (= #{} (g/marquee-hit-relationship-ids
                  parts rels {:x 0 :y 0 :width 100 :height 50}))))
    (testing "a rect away from everything hits nothing"
      (is (= #{} (g/marquee-hit-relationship-ids
                  parts rels {:x 500 :y 0 :width 50 :height 50}))))
    (testing "a relationship pointing at a missing Part is skipped, not errored"
      (is (= #{} (g/marquee-hit-relationship-ids
                  parts
                  [{:id "r2" :source_id "p1" :target_id "ghost" :type "unknown"}]
                  {:x 0 :y 150 :width 200 :height 50}))))))

(deftest corners->rect-test
  (testing "normalises whichever way the drag ran"
    (is (= {:x 10 :y 20 :width 30 :height 40}
           (g/corners->rect {:x 40 :y 60} {:x 10 :y 20})
           (g/corners->rect {:x 10 :y 20} {:x 40 :y 60})))))
