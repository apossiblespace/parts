(ns aps.parts.render.document.shapes
  "Part-shape rendering for the document SVG. Loads the per-type SVG
   files from `resources/public/images/nodes/` once at namespace init,
   rewrites each into a `<symbol>`, and emits a `<use>` per Part.

   `<symbol>`'s viewBox auto-stretches into the `<use>` element's
   width/height — that reproduces the canvas's CSS squish for free, so
   manager (100×108) and firefighter (120×120) render correctly into a
   100×100 box without transform math."
  (:require
   [aps.parts.common.constants :as c]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;; Hiccup 2 drops namespace segments from qualified keywords
;; (`:xlink/href` renders as just `href`), so namespaced SVG attributes
;; have to go through `(keyword "xlink:href")` to keep the prefix.
(def ^:private xlink-href (keyword "xlink:href"))

(defn- ->symbol
  "Convert one Part-type SVG file into a `<symbol>` string: strip the
   XML/DOCTYPE preamble, rewrite the outer `<svg …>` to
   `<symbol id=\"part-{type}\" viewBox=\"…\">`, close as `</symbol>`."
  [type]
  (let [raw      (slurp (io/resource (str "public/images/nodes/" type ".svg")))
        no-pre   (str/replace raw
                              #"(?m)^<\?xml[^>]*\?>\s*|^<!DOCTYPE[^>]*>\s*"
                              "")
        view-box (or (second (re-find #"viewBox=\"([^\"]+)\"" no-pre))
                     "0 0 100 100")]
    (-> no-pre
        (str/replace
         #"<svg[^>]*>"
         (str "<symbol id=\"part-" type "\" viewBox=\"" view-box "\">"))
        (str/replace "</svg>" "</symbol>")
        str/trim)))

(def shape-symbols
  "All four Part-type SVGs as `<symbol>` strings, joined for direct
   embedding in `<defs>`. A delay — file I/O happens once on first
   deref. Consumer pattern: `@shape-symbols`."
  (delay (str/join "\n" (map ->symbol c/part-types))))

(def ^:private default-viewbox
  "viewBox for an empty Map. Non-degenerate so consumers can size an
   `<img>` against it without divide-by-zero in the browser."
  [0 0 200 100])

(def ^:private viewbox-padding 20)

(defn viewbox
  "viewBox `[x y w h]` covering every Part's measured rectangle, padded
   on each side. Falls back to `default-viewbox` for an empty Map."
  [parts]
  (if (empty? parts)
    default-viewbox
    (let [xs    (map :position_x parts)
          ys    (map :position_y parts)
          rxs   (map #(+ (:position_x %) (or (:width  %) 100)) parts)
          rys   (map #(+ (:position_y %) (or (:height %) 100)) parts)
          min-x (- (apply min xs)  viewbox-padding)
          min-y (- (apply min ys)  viewbox-padding)
          max-x (+ (apply max rxs) viewbox-padding)
          max-y (+ (apply max rys) viewbox-padding)]
      [min-x min-y (- max-x min-x) (- max-y min-y)])))

(defn part-use
  "A `<use>` element placing one Part's shape symbol at its measured
   rectangle. Uses `xlink:href` (SVG 1.1) — Apache FOP / Batik don't
   resolve the SVG-2 `href` form, and browsers still accept the
   prefixed one."
  [{:keys [type position_x position_y width height]}]
  [:use {xlink-href (str "#part-" type)
         :x         position_x
         :y         position_y
         :width     (or width  100)
         :height    (or height 100)}])
