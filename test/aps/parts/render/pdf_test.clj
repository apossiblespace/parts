(ns aps.parts.render.pdf-test
  (:require
   [aps.parts.render.document :as document]
   [aps.parts.render.pdf :as pdf]
   [clojure.test :refer [deftest is testing]]))

(deftest svg->pdf-test
  (testing "transcodes a document-renderer SVG to PDF bytes that begin with the %PDF signature"
    (let [svg   (document/render
                 {:title         "Smoke"
                  :parts         [{:id         "p1" :type       "manager" :label "A"
                                   :position_x 0    :position_y 0
                                   :width      100  :height     100}
                                  {:id         "p2" :type       "exile" :label "B"
                                   :position_x 300  :position_y 0
                                   :width      100  :height     100}]
                  :relationships [{:id   "r"        :source_id "p1" :target_id "p2"
                                   :type "protects"}]})
          bytes (pdf/svg->pdf svg)]
      (is (bytes? bytes))
      (is (> (alength bytes) 0))
      ;; PDF file signature: %PDF at offset 0
      (is (= "%PDF" (String. bytes 0 4 "UTF-8"))))))

(deftest svg->pdf-cjk-test
  (testing "CJK title and labels transcode through the bundled font —
            exercises the FOP font registration end to end"
    (let [svg   (document/render
                 {:title         "內在系統圖 — 简体也可以"
                  :parts         [{:id         "p1"     :type       "exile"
                                   :label      "被遺棄的孩子"
                                   :position_x 0        :position_y 0
                                   :width      100      :height     100}]
                  :relationships []})
          bytes (pdf/svg->pdf svg)]
      (is (= "%PDF" (String. bytes 0 4 "UTF-8"))))))
