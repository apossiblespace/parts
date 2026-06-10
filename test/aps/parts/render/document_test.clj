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
  (testing "Parts with no label render no <tspan> — chrome <text> elements
            (header title + date + footer) always exist, but Part labels
            are the only `<tspan>`-bearing text in the document"
    (doseq [label [nil ""]]
      (let [svg (document/render
                 {:parts         [{:id         "p" :type       "manager" :label label
                                   :position_x 0   :position_y 0
                                   :width      100 :height     100}]
                  :relationships []})]
        (is (not (str/includes? svg "<tspan"))
            (str "label=" (pr-str label)))))))

(deftest render-chrome-test
  (testing "outer SVG is a page-sized A4 viewBox (Batik PDFTranscoder default)"
    (let [svg (document/render {:parts [] :relationships []})]
      (is (str/includes? svg "viewBox=\"0 0 595 842\""))))
  (testing "header carries the Map title"
    (let [svg (document/render
               {:title         "Smith Map"
                :parts         []
                :relationships []})]
      (is (str/includes? svg "Smith Map"))))
  (testing "missing/blank title falls back to 'Untitled Map' — no empty line"
    (doseq [t [nil ""]]
      (let [svg (document/render {:title t :parts [] :relationships []})]
        (is (str/includes? svg "Untitled Map")
            (str "title=" (pr-str t))))))
  (testing "header carries a formatted date — :as-of-date opt pins it for tests"
    (let [svg (document/render
               {:title "M" :parts [] :relationships []}
               {:as-of-date (java.time.LocalDate/of 2026 5 26)})]
      (is (str/includes? svg "26 May 2026"))))
  (testing "footer carries the 'Made with Parts' branding"
    (let [svg (document/render {:parts [] :relationships []})]
      (is (str/includes? svg "Made with Parts")))))

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
  (testing "one `<marker>` per Relationship type is defined; each edge
            references the marker matching its type. (One-marker-with-
            context-stroke is the canvas's trick; Apache FOP/Batik
            doesn't support that SVG-2 keyword, so the document renderer
            bakes colours into per-type markers instead.)"
    (let [svg (document/render
               {:parts         [{:id         "p1" :type       "manager"
                                 :position_x 0    :position_y 0         :width 100 :height 100}
                                {:id         "p2" :type       "exile"
                                 :position_x 300  :position_y 0       :width 100 :height 100}]
                :relationships [{:id "r1" :source_id "p1" :target_id "p2" :type "protective"}
                                {:id "r2" :source_id "p2" :target_id "p1" :type "protective"}]})]
      (is (= 6 (count (re-seq #"<marker " svg))))
      (is (= 2 (count (re-seq #"marker-end=\"url\(#edge-arrow-protective\)\"" svg)))))))

(deftest render-no-ratios-in-path-d-test
  (testing "edges between unknown-type Parts produce only decimal numbers in
            `d` attributes — Clojure Ratios (e.g. `10123/41`) are not valid
            SVG numbers and would crash Apache FOP's transcode"
    (let [svg (document/render
               {:parts         [{:id         "p1" :type       "unknown"
                                 :position_x 100  :position_y 150
                                 :width      100  :height     100}
                                {:id         "p2" :type       "unknown"
                                 :position_x 350  :position_y 280
                                 :width      100  :height     100}]
                :relationships [{:id   "r"          :source_id "p1" :target_id "p2"
                                 :type "protective"}]})]
      ;; Any d-attribute that contains a `/` between two digit runs would be
      ;; a Clojure Ratio stringified verbatim. Should not appear anywhere.
      (is (not (re-find #"d=\"[^\"]*\d+/\d+" svg))))))

(deftest render-resized-part-test
  (testing "a resized Part renders at its stored dimensions — the shape
            <use>, and therefore the viewBox, honour non-default sizes"
    (let [svg (document/render
               {:parts         [{:id         "p1" :type       "manager"
                                 :position_x 0    :position_y 0
                                 :width      200  :height     200}]
                :relationships []})]
      (is (str/includes? svg "width=\"200\""))
      ;; bbox: x ∈ [0, 200], y ∈ [0, 200]; padded by 20 on each side.
      (is (str/includes? svg "viewBox=\"-20 -20 240 240\"")))))

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
