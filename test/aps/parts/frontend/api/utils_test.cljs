(ns aps.parts.frontend.api.utils-test
  (:require
   [aps.parts.frontend.api.utils :as utils]
   [cljs.test :refer-macros [deftest is]]))

(deftest clear-playground-data-test
  (let [storage  (js/Object.create #js {:removeItem (fn [k] (this-as this (js-delete this k)))})
        original (.-localStorage js/globalThis)]
    (js/Object.assign storage (clj->js (zipmap (conj (mapv #(str "parts-map-" %) (range 40)) "other-key")
                                               (repeat "x"))))
    (set! (.-localStorage js/globalThis) storage)
    (try
      (utils/clear-playground-data)
      (is (= ["other-key"] (vec (js/Object.keys storage))))
      (finally
        (set! (.-localStorage js/globalThis) original)))))
