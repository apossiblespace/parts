(ns aps.parts.common.geometry-test
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer-macros [deftest is testing]])
   [aps.parts.common.geometry :as g]
   [clojure.string :as str]))

(defn- approx=
  ([a b] (approx= a b 0.001))
  ([a b epsilon] (< (Math/abs (double (- a b))) epsilon)))

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

(deftest curve-midpoint-test
  (testing "zero offset: the chord midpoint (plain bezier edges)"
    (is (= {:x 50 :y 0} (g/curve-midpoint {:sx 0 :sy 0 :tx 100 :ty 0} 0))))

  (testing "a bowed edge's visual midpoint sits half the offset off the
            chord; the pair's opposite chord directions land the two
            midpoints on OPPOSITE sides — badges never stack"
    (let [a (g/curve-midpoint {:sx 0 :sy 0 :tx 100 :ty 0} 40)
          b (g/curve-midpoint {:sx 100 :sy 0 :tx 0 :ty 0} 40)]
      (is (approx= 50 (:x a)))
      (is (approx= 20 (:y a)))
      (is (approx= 50 (:x b)))
      (is (approx= -20 (:y b)))))

  (testing "degenerate zero-length chord stays at the point"
    (is (= {:x 5 :y 5} (g/curve-midpoint {:sx 5 :sy 5 :tx 5 :ty 5} 40)))))

(deftest corners->rect-test
  (testing "normalises whichever way the drag ran"
    (is (= {:x 10 :y 20 :width 30 :height 40}
           (g/corners->rect {:x 40 :y 60} {:x 10 :y 20})
           (g/corners->rect {:x 10 :y 20} {:x 40 :y 60})))))

(defn- parse-num [s]
  #?(:clj (Double/parseDouble s) :cljs (js/parseFloat s)))

(defn- path-points
  "Every x,y pair in a `d` string, in order, as doubles."
  [d]
  (mapv (fn [pair]
          (mapv parse-num (str/split pair #",")))
        (re-seq #"-?[\d.]+,-?[\d.]+" d)))

;; A flat horizontal chord: the smooth cubic (controls on the axis) and
;; the bow-less curve both sit exactly on y=0, so any |y| in a sampled
;; point is pure jag amplitude.
(def ^:private flat-endpoints
  {:sx 0.0 :sy 0.0 :tx 300.0 :ty 0.0 :s-side :right :t-side :left})

(deftest edge-path-smooth-test
  (testing "intensity 0 (or absent) is exactly the classic smooth path"
    (is (= (g/cubic-path flat-endpoints)
           (g/edge-path flat-endpoints {})
           (g/edge-path flat-endpoints {:intensity 0})))
    (is (= (g/quadratic-path flat-endpoints g/bow-offset-px)
           (g/edge-path flat-endpoints {:bow? true}))))

  (testing "the shared cubic starts at the source and ends at the target"
    (let [pts (path-points (g/cubic-path flat-endpoints))]
      (is (= 4 (count pts)) "M + two controls + endpoint")
      (is (approx= 0.0 (ffirst pts)))
      (is (approx= 300.0 (first (peek pts)))))))

(deftest edge-path-jagged-test
  (let [pts-of  (fn [opts] (path-points (g/edge-path flat-endpoints opts)))
        d100    (g/edge-path flat-endpoints {:intensity 100})
        d30     (g/edge-path flat-endpoints {:intensity 30})
        pts100  (path-points d100)
        pts30   (path-points d30)
        max-dev (fn [pts] (apply max (map (fn [[_ y]] (Math/abs y)) pts)))]
    (testing "intensity 100 is a pure sawtooth — hard corners, no curves"
      (is (not (str/includes? d100 "C")))
      (is (not (str/includes? d100 "Q")))
      (is (str/includes? d100 "L")))

    (testing "lower intensities round the peaks into a wave (quadratic
              corners), sharpening as intensity rises"
      (is (str/includes? d30 "Q"))
      (is (not (str/includes? d30 "C")))
      (is (> (g/jag-sharpness 30) (g/jag-sharpness 80))
          "corner rounding shrinks as intensity rises")
      (is (zero? (g/jag-sharpness 100)) "sawtooth at the top"))

    (testing "endpoints are exact regardless of intensity — the taper
              zeroes the jag where the curve meets the Parts"
      (is (approx= 0.0 (ffirst pts100)))
      (is (approx= 0.0 (second (first pts100))))
      (is (approx= 300.0 (first (peek pts100))))
      (is (approx= 0.0 (second (peek pts100)))))

    (testing "amplitude scales with intensity; 100 caps at the max"
      (is (pos? (max-dev pts30)))
      (is (> (max-dev pts100) (max-dev pts30)))
      (is (<= (max-dev pts100) g/jag-max-amplitude-px)))

    (testing "fixed wavelength: point count tracks curve length, not
              intensity (compared within one corner form — rounded
              corners emit extra on-curve points)"
      (is (= (count pts30) (count (pts-of {:intensity 60}))))
      (is (> (count (path-points
                     (g/edge-path (assoc flat-endpoints :tx 600.0)
                                  {:intensity 100})))
             (count pts100))))

    (testing "the first interior point deviates less than the peak —
              the endpoint taper at work"
      (is (< (Math/abs (second (nth pts100 1)))
             (max-dev pts100))))))

(deftest edge-path-jagged-bow-test
  (testing "a bowed pair's jag rides the bow — the midpoint sits near
            the bow apex (half the control offset), jag excursion aside"
    (let [pts  (path-points (g/edge-path flat-endpoints
                                         {:bow? true :intensity 100}))
          mid  (nth pts (quot (count pts) 2))
          apex (/ g/bow-offset-px 2)]
      (is (> (second mid) (- apex g/jag-max-amplitude-px 1))))))
