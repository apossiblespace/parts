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
                  :relationships [{:id   "r"          :source_id "p1" :target_id "p2"
                                   :type "protective"}]})
          bytes (pdf/svg->pdf svg)]
      (is (bytes? bytes))
      (is (> (alength bytes) 0))
      ;; PDF file signature: %PDF at offset 0
      (is (= "%PDF" (String. bytes 0 4 "UTF-8"))))))
