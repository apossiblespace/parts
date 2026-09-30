(ns aps.parts.common.shapes-test
  (:require
   [aps.parts.common.constants :as c]
   [aps.parts.common.shapes :as shapes]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]))

(defn- artwork [type]
  (slurp (io/resource (str "public/images/nodes/" type ".svg"))))

(deftest test-symbol-keeps-the-artwork
  (doseq [type c/part-types
          :let [sym (shapes/->symbol type (artwork type))]]
    (testing type
      (is (re-find #"^<symbol id=\"part-[a-z]+\" viewBox=\"[^\"]+\">" sym))
      (is (re-find #"</symbol>$" sym))
      (is (not (re-find #"<\?xml|<!DOCTYPE|<svg|</svg>" sym)))
      (is (not (re-find #"var\(" sym))
          "the Render's symbols must stay plain — Batik has no CSS variables"))))

(deftest test-tintable-symbol-recolours-only-the-interior
  (doseq [type c/part-types
          :let [raw (artwork type)
                sym (shapes/->tintable-symbol type raw)]]
    (testing type
      (is (= 1 (count (re-seq #"var\(--part-fill, " sym)))
          "the artwork must draw its interior with one fill + fill-opacity pair")
      (is (= 1 (count (re-seq #"var\(--part-fill-opacity, " sym))))
      (is (not (re-find #"fill-opacity=" sym))
          "no translucent layer escapes the variables"))))
