(ns aps.parts.render.document.shapes
  "Part-shape rendering for the document SVG. Loads the per-type SVG
   files from `resources/public/images/nodes/` once, on first use,
   rewrites each into a `<symbol>` (`aps.parts.common.shapes`, shared
   with the canvas), and emits a `<use>` per Part.

   `<symbol>`'s viewBox auto-stretches into the `<use>` element's
   width/height — that reproduces the canvas's CSS squish for free, so
   manager (100×108) and firefighter (120×120) render correctly into a
   100×100 box without transform math."
  (:require
   [aps.parts.common.constants :as c]
   [aps.parts.common.geometry :as geometry]
   [aps.parts.common.shapes :as shapes]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [hiccup.util :refer [raw-string]]))

;; Hiccup 2 drops namespace segments from qualified keywords
;; (`:xlink/href` renders as just `href`), so namespaced SVG attributes
;; have to go through `(keyword "xlink:href")` to keep the prefix.
(def ^:private xlink-href (keyword "xlink:href"))

(def shape-symbols
  "All four Part-type SVGs as `<symbol>` strings, joined for direct
   embedding in `<defs>`. A delay — file I/O happens once on first
   deref. Consumer pattern: `@shape-symbols`."
  (delay (str/join "\n"
                   (map #(shapes/->symbol
                          % (slurp (io/resource (str "public/images/nodes/" % ".svg"))))
                        c/part-types))))

(def ^:private default-viewbox
  "viewBox for an empty Map. Non-degenerate so consumers can size an
   `<img>` against it without divide-by-zero in the browser."
  [0 0 200 100])

(def ^:private viewbox-padding 20)

(defn- drawn-rect
  "The area a Part draws on: its measured rectangle, grown by the
   Unburdened aura's reach when it has one."
  [{:keys [unburdened] :as part}]
  (let [{:keys [x y width height] :as rect} (geometry/part-rect part)]
    (if unburdened
      (let [dx (long (Math/round (* width shapes/aura-reach)))
            dy (long (Math/round (* height shapes/aura-reach)))]
        {:x (- x dx) :y (- y dy) :width (+ width dx dx) :height (+ height dy dy)})
      rect)))

(defn viewbox
  "viewBox `[x y w h]` covering the area every Part draws on (its
   measured rectangle, plus any aura), padded on each side. Falls back
   to `default-viewbox` for an empty Map."
  [parts]
  (if (empty? parts)
    default-viewbox
    (let [rects (map drawn-rect parts)
          xs    (map :x rects)
          ys    (map :y rects)
          rxs   (map #(+ (:x %) (:width %)) rects)
          rys   (map #(+ (:y %) (:height %)) rects)
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
  [{:keys [type] :as part}]
  (let [{:keys [x y width height]} (geometry/part-rect part)]
    [:use {xlink-href (str "#" (shapes/symbol-id type))
           :x         x
           :y         y
           :width     width
           :height    height}]))

(defn part-aura
  "The Unburdened aura behind one Part, or nil when it is not
   unburdened. The shared aura markup sizes itself in percentages of
   the Part's box, so it sits in a nested `<svg>` placed at that box;
   `overflow` visible lets the glow reach past it."
  [{:keys [id type unburdened] :as part}]
  (when unburdened
    (let [{:keys [x y width height]} (geometry/part-rect part)]
      [:svg {:x x :y y :width width :height height :overflow "visible"}
       (raw-string (shapes/aura-markup (str "aura-" id) type))])))
