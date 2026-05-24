(ns aps.parts.render.preview-test
  (:require
   [aps.parts.render.preview :as preview]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(deftest render-empty-test
  (testing "renders a valid SVG document even when the Map has no Parts"
    (let [svg (preview/render {:parts [] :relationships []})]
      (is (string? svg))
      (is (str/starts-with? svg "<svg"))
      (is (str/includes? svg "</svg>"))))
  (testing "an empty Map gets a non-degenerate default viewBox"
    (let [svg (preview/render {:parts [] :relationships []})]
      (is (str/includes? svg "viewBox=\"0 0 200 100\"")))))

(deftest render-parts-as-circles-test
  (testing "every Part becomes a <circle> filled with brand teal, regardless of type"
    (let [svg (preview/render
               {:parts         [{:id         "p1" :type       "manager"
                                 :position_x 0    :position_y 0         :width 100 :height 100}
                                {:id         "p2" :type       "exile"
                                 :position_x 200  :position_y 0       :width 100 :height 100}
                                {:id         "p3" :type       "firefighter"
                                 :position_x 100  :position_y 200           :width 100 :height 100}
                                {:id         "p4" :type       "unknown"
                                 :position_x 300  :position_y 200       :width 100 :height 100}]
                :relationships []})]
      (is (= 4 (count (re-seq #"<circle" svg))))
      ;; brand teal hex — shared with the canvas exile shape
      (is (str/includes? (str/lower-case svg) "#999999"))))
  (testing "circles are placed at each Part's centre"
    (let [svg (preview/render
               {:parts         [{:id         "p" :type       "manager"
                                 :position_x 0   :position_y 0
                                 :width      100 :height     100}]
                :relationships []})]
      ;; centre of (0,0)–(100,100) is (50,50)
      (is (re-find #"cx=\"50\"" svg))
      (is (re-find #"cy=\"50\"" svg))))
  (testing "no <use>, no <symbol>, no <text> — the preview is iconic only"
    (let [svg (preview/render
               {:parts         [{:id         "p" :type       "manager" :label "Boss"
                                 :position_x 0   :position_y 0
                                 :width      100 :height     100}]
                :relationships []})]
      (is (not (str/includes? svg "<use")))
      (is (not (str/includes? svg "<symbol")))
      (is (not (str/includes? svg "<text")))
      (is (not (str/includes? svg "Boss"))))))

(deftest render-relationships-as-lines-test
  (testing "every Relationship becomes a straight <line> in brand teal"
    (let [svg (preview/render
               {:parts         [{:id         "p1" :type       "manager"
                                 :position_x 0    :position_y 0         :width 100 :height 100}
                                {:id         "p2" :type       "exile"
                                 :position_x 300  :position_y 0       :width 100 :height 100}]
                :relationships [{:id   "r"          :source_id "p1" :target_id "p2"
                                 :type "protective"}]})]
      (is (= 1 (count (re-seq #"<line " svg))))
      (is (str/includes? (str/lower-case svg) "#999999"))))
  (testing "Relationship type does not affect line colour — preview is monochrome"
    (let [svg (preview/render
               {:parts         [{:id         "p1" :type       "manager"
                                 :position_x 0    :position_y 0         :width 100 :height 100}
                                {:id         "p2" :type       "exile"
                                 :position_x 300  :position_y 0       :width 100 :height 100}
                                {:id         "p3" :type       "firefighter"
                                 :position_x 600  :position_y 0             :width 100 :height 100}]
                :relationships [{:id "r1" :source_id "p1" :target_id "p2" :type "polarization"}
                                {:id "r2" :source_id "p2" :target_id "p3" :type "alliance"}]})]
      (is (not (str/includes? svg "#f44336")))   ; polarization red
      (is (not (str/includes? svg "#2196f3")))   ; alliance blue
      (is (= 2 (count (re-seq #"<line " svg))))))
  (testing "no bezier paths, no arrowhead markers — straight lines only"
    (let [svg (preview/render
               {:parts         [{:id         "p1" :type       "manager"
                                 :position_x 0    :position_y 0         :width 100 :height 100}
                                {:id         "p2" :type       "exile"
                                 :position_x 300  :position_y 0       :width 100 :height 100}]
                :relationships [{:id   "r"          :source_id "p1" :target_id "p2"
                                 :type "protective"}]})]
      (is (not (str/includes? svg "<path")))
      (is (not (str/includes? svg "<marker")))
      (is (not (str/includes? svg "marker-end")))))
  (testing "a Relationship pointing at a missing Part is skipped, not errored"
    (let [svg (preview/render
               {:parts         [{:id         "p1" :type       "manager"
                                 :position_x 0    :position_y 0         :width 100 :height 100}]
                :relationships [{:id   "r"          :source_id "p1" :target_id "ghost"
                                 :type "protective"}]})]
      (is (string? svg))
      (is (zero? (count (re-seq #"<line " svg)))))))

(deftest render-line-thickness-test
  (testing "preview lines are thicker than the document renderer's 1.5 so they read at thumbnail scale"
    (let [svg (preview/render
               {:parts         [{:id         "p1" :type       "manager"
                                 :position_x 0    :position_y 0         :width 100 :height 100}
                                {:id         "p2" :type       "exile"
                                 :position_x 300  :position_y 0       :width 100 :height 100}]
                :relationships [{:id   "r"          :source_id "p1" :target_id "p2"
                                 :type "protective"}]})]
      (when-let [m (re-find #"stroke-width=\"([0-9.]+)\"" svg)]
        (is (>= (Double/parseDouble (second m)) 2.0))))))
