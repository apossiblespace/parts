(ns aps.parts.render.fonts-test
  (:require
   [aps.parts.render.document.labels :as labels]
   [aps.parts.render.fonts :as fonts]
   [clojure.test :refer [deftest is testing]]))

(deftest document-font-test
  (testing "the document font covers both Chinese scripts — FOP does no
            per-glyph fallback, so coverage must live in the one font"
    (let [font (fonts/document-font :regular 14)]
      (is (.canDisplay font \內) "traditional-only character")
      (is (.canDisplay font \简) "simplified-only character")
      (is (.canDisplay font \a))))

  (testing "bold is the real Bold file, not a synthetic derivation"
    (is (not= (fonts/document-font :regular 14)
              (fonts/document-font :bold 14))))

  (testing "CJK glyphs measure full-width — wrap math sees the real
            metrics, not a fallback font's"
    (is (< 27.9 (labels/text-width
                 (fonts/document-font :regular 14) "內在")
           28.1))))

(deftest svg-weights-test
  (testing "every emitted weight maps to a real style — an unmapped
            weight would fall to FOP's silent closest-match"
    (is (every? #{:regular :bold} (vals fonts/svg-weights)))))
