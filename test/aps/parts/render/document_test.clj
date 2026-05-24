(ns aps.parts.render.document-test
  (:require
   [aps.parts.render.document :as document]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(deftest render-empty-test
  (testing "renders a valid SVG document even when the Map has no Parts"
    (let [svg (document/render {:parts [] :relationships []})]
      (is (string? svg))
      (is (str/starts-with? svg "<svg"))
      (is (str/includes? svg "</svg>"))))
  (testing "an empty Map gets a non-degenerate default viewBox"
    (let [svg (document/render {:parts [] :relationships []})]
      (is (str/includes? svg "viewBox=\"0 0 200 100\"")))))

(deftest render-defs-test
  (testing "<defs> contains one <symbol> per Part type, loaded from the SVG files"
    (let [svg (document/render {:parts [] :relationships []})]
      (is (str/includes? svg "<defs>"))
      (is (str/includes? svg "id=\"part-unknown\""))
      (is (str/includes? svg "id=\"part-exile\""))
      (is (str/includes? svg "id=\"part-manager\""))
      (is (str/includes? svg "id=\"part-firefighter\"")))))

(deftest render-parts-test
  (testing "each Part becomes one <use> referencing its shape symbol, at its position"
    (let [svg (document/render
               {:parts         [{:id         "p1" :type       "manager" :label "Boss"
                                 :position_x 10   :position_y 20        :width 100    :height 100}
                                {:id         "p2" :type       "exile" :label "Hurt"
                                 :position_x 200  :position_y 50      :width 100    :height 100}]
                :relationships []})]
      (is (str/includes? svg "href=\"#part-manager\""))
      (is (str/includes? svg "href=\"#part-exile\""))
      (is (str/includes? svg "x=\"10\""))
      (is (str/includes? svg "x=\"200\"")))))

(deftest render-labels-test
  (testing "a Part's label appears as a <text> element with the label text"
    (let [svg (document/render
               {:parts         [{:id         "p" :type       "manager" :label "Boss"
                                 :position_x 0   :position_y 0         :width 100    :height 100}]
                :relationships []})]
      (is (str/includes? svg "<text"))
      (is (str/includes? svg "Boss"))))
  (testing "a label longer than the Part's width wraps into multiple <tspan> lines"
    (let [svg (document/render
               {:parts         [{:id         "p"                           :type       "manager"
                                 :label      "Inner Critic from Childhood"
                                 :position_x 0                             :position_y 0
                                 :width      100                           :height     100}]
                :relationships []})]
      (is (>= (count (re-seq #"<tspan" svg)) 2))))
  (testing "a label that won't fit in 3 lines is truncated with an ellipsis"
    (let [svg (document/render
               {:parts         [{:id         "p"                                       :type       "manager"
                                 :label      "A B C D E F G H I J K L M N O P Q R S T"
                                 :position_x 0                                         :position_y 0
                                 :width      100                                       :height     100}]
                :relationships []})]
      ;; Exact line count depends on font metrics (Liberation Sans on Linux
      ;; vs the JVM's logical fallback on macOS measure slightly different
      ;; widths). What matters is the cap and the ellipsis.
      (is (<= (count (re-seq #"<tspan" svg)) 3))
      (is (str/includes? svg "…"))))
  (testing "Parts with no label render no <text> element"
    (doseq [label [nil ""]]
      (let [svg (document/render
                 {:parts         [{:id         "p" :type       "manager" :label label
                                   :position_x 0   :position_y 0
                                   :width      100 :height     100}]
                  :relationships []})]
        (is (not (str/includes? svg "<text"))
            (str "label=" (pr-str label)))))))

(deftest render-edges-test
  ;; Note: shape SVGs contain `<path>` elements too, so regexes target
  ;; `<path[^>]*stroke=` — only our edge paths declare a stroke attribute,
  ;; the shape paths use `style="fill:…"`.
  (testing "a Relationship between two Parts becomes one <path> with a cubic-Bezier `d`"
    (let [svg (document/render
               {:parts         [{:id         "p1" :type       "manager"
                                 :position_x 0    :position_y 0         :width 100 :height 100}
                                {:id         "p2" :type       "exile"
                                 :position_x 300  :position_y 0       :width 100 :height 100}]
                :relationships [{:id "r1" :source_id "p1" :target_id "p2" :type "protective"}]})]
      (is (= 1 (count (re-seq #"<path[^>]*stroke=" svg))))
      (is (re-find #"d=\"M-?[0-9]" svg))
      (is (re-find #" C-?[0-9]" svg))))
  (testing "an edge is stroked with the Relationship type's colour"
    (let [svg (document/render
               {:parts         [{:id         "p1" :type       "manager"
                                 :position_x 0    :position_y 0         :width 100 :height 100}
                                {:id         "p2" :type       "exile"
                                 :position_x 300  :position_y 0       :width 100 :height 100}]
                :relationships [{:id "r1" :source_id "p1" :target_id "p2" :type "protective"}]})]
      (is (str/includes? svg "#4caf50"))))
  (testing "bidirectional Relationships render as two bowed quadratic paths"
    (let [svg (document/render
               {:parts         [{:id         "p1" :type       "manager"
                                 :position_x 0    :position_y 0         :width 100 :height 100}
                                {:id         "p2" :type       "exile"
                                 :position_x 300  :position_y 0       :width 100 :height 100}]
                :relationships [{:id "r1" :source_id "p1" :target_id "p2" :type "alliance"}
                                {:id "r2" :source_id "p2" :target_id "p1" :type "alliance"}]})]
      (is (= 2 (count (re-seq #"<path[^>]*stroke=" svg))))
      (is (re-find #" Q-?[0-9]" svg))))
  (testing "a Relationship pointing at a missing Part is skipped, not errored"
    (let [svg (document/render
               {:parts         [{:id         "p1" :type       "manager"
                                 :position_x 0    :position_y 0         :width 100 :height 100}]
                :relationships [{:id "r1" :source_id "p1" :target_id "ghost" :type "protective"}]})]
      (is (string? svg))
      (is (zero? (count (re-seq #"<path[^>]*stroke=" svg))))))
  (testing "an arrowhead marker is defined once and every edge references it"
    (let [svg (document/render
               {:parts         [{:id         "p1" :type       "manager"
                                 :position_x 0    :position_y 0         :width 100 :height 100}
                                {:id         "p2" :type       "exile"
                                 :position_x 300  :position_y 0       :width 100 :height 100}]
                :relationships [{:id "r1" :source_id "p1" :target_id "p2" :type "protective"}
                                {:id "r2" :source_id "p2" :target_id "p1" :type "protective"}]})]
      (is (= 1 (count (re-seq #"<marker " svg))))
      (is (= 2 (count (re-seq #"marker-end=\"url\(#edge-arrow\)\"" svg)))))))

(deftest render-viewbox-test
  (testing "viewBox encompasses every Part's measured rectangle, padded on every side"
    (let [svg (document/render
               {:parts         [{:id         "p1" :type       "manager"
                                 :position_x 0    :position_y 0         :width 100 :height 100}
                                {:id         "p2" :type       "exile"
                                 :position_x 200  :position_y 0       :width 100 :height 100}]
                :relationships []})]
      ;; bbox: x ∈ [0, 300], y ∈ [0, 100]; padded by 20 on each side →
      ;; viewBox "-20 -20 340 140".
      (is (str/includes? svg "viewBox=\"-20 -20 340 140\"")))))
