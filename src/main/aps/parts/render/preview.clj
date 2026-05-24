(ns aps.parts.render.preview
  "Glanceable iconic SVG of a Map for the Maps-list thumbnail
   (`/api/maps/:id/preview.svg`). Optimised for *recognition* at ~200px:
   every Part is a grey circle, every Relationship is a straight line in
   the same grey. Monochrome by design — no labels, no per-type styling,
   no document chrome.

   The split from the high-fidelity document renderer is deliberate: a
   thumbnail's job is to help a therapist scan their list and go 'ah,
   that's the Smith Map.' Pixel-detailed yellow hexagons and 14px
   wrapped labels are invisible noise at thumbnail scale. The document
   render (`aps.parts.render.document`) carries that fidelity for the
   print artifact, which is a different job. See ADR-0008."
  (:require
   [hiccup2.core :refer [html]]))

(def ^:private ink
  "Single colour used for both Parts and Relationship lines. Matches the
   canvas's `--edge-color-unknown` in `resources/styles/main.css`, so
   the preview's palette is continuous with the 'unknown' relationship
   styling in the live tool. Mid-light grey: present against the card's
   gray-50 background, neutral enough to recede behind the Map's title."
  "#999999")

(def ^:private part-radius
  "Dot radius in Map coordinates. Roughly fills the default 100×100 Part
   box — visually dominant at thumbnail scale, where recognition of *how
   many* nodes there are and *how* they connect matters more than
   distinguishing dot-from-line. The line still extends visibly past the
   dot edge in any reasonable layout."
  40)

(def ^:private line-stroke-width
  "Stroke width for Relationship lines, in Map coordinates. Thicker than
   the document renderer's 1.5 so lines remain visible after the SVG is
   scaled down into a thumbnail card."
  4)

(def ^:private viewbox-padding 20)

(def ^:private default-viewbox
  "viewBox for an empty Map. Non-degenerate so an `<img>` can size
   against it without divide-by-zero."
  [0 0 200 100])

(defn- part-center
  "Centre of a Part's measured rectangle, in Map coordinates."
  [{:keys [position_x position_y width height]}]
  [(+ position_x (/ (or width  100) 2))
   (+ position_y (/ (or height 100) 2))])

(defn- viewbox
  "viewBox vector `[x y w h]` encompassing every Part's dot, padded on
   each side. Falls back to `default-viewbox` for an empty Map."
  [parts]
  (if (empty? parts)
    default-viewbox
    (let [centres (map part-center parts)
          xs      (map first centres)
          ys      (map second centres)
          pad     (+ part-radius viewbox-padding)
          min-x   (- (apply min xs) pad)
          min-y   (- (apply min ys) pad)
          max-x   (+ (apply max xs) pad)
          max-y   (+ (apply max ys) pad)]
      [min-x min-y (- max-x min-x) (- max-y min-y)])))

(defn- part-dot
  "A `<circle>` representing one Part."
  [part]
  (let [[cx cy] (part-center part)]
    [:circle {:cx cx :cy cy :r part-radius :fill ink}]))

(defn- relationship-line
  "A straight `<line>` between two Parts' centres. Returns nil when
   either endpoint Part is missing — protects against a stale
   Relationship row pointing at a since-deleted Part."
  [parts-by-id {:keys [source_id target_id]}]
  (when-let [source (parts-by-id source_id)]
    (when-let [target (parts-by-id target_id)]
      (let [[sx sy] (part-center source)
            [tx ty] (part-center target)]
        [:line {:x1             sx
                :y1             sy
                :x2             tx
                :y2             ty
                :stroke         ink
                :stroke-width   line-stroke-width
                :stroke-linecap "round"}]))))

(defn render
  "Render a hydrated Map to a glanceable preview SVG string.

   Input shape:

     {:parts         [{:id :type :position_x :position_y :width :height} …]
      :relationships [{:source_id :target_id} …]}

   `:type`, `:label`, and Relationship `:type` are deliberately ignored —
   the preview is monochrome circles and lines by design."
  [{:keys [parts relationships] :as _the-map}]
  (let [[vx vy vw vh] (viewbox parts)
        by-id         (into {} (map (juxt :id identity)) parts)]
    (str
     (html
      [:svg {:xmlns   "http://www.w3.org/2000/svg"
             :viewBox (str vx " " vy " " vw " " vh)}
       ;; Lines first, dots on top — the dot covers the line end where it
       ;; enters the circle so the visible segment is dot-edge to dot-edge.
       (keep (partial relationship-line by-id) relationships)
       (map part-dot parts)]))))
