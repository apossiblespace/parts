(ns aps.parts.render.document
  "High-fidelity SVG of a Map for the printable client hand-out — the
   PDF export path (`/api/maps/:id/render.pdf`), transcoded via Apache
   Batik. Inlines the styled per-type SVGs from
   `resources/public/images/nodes/`, wraps labels with FontMetrics,
   and draws bezier edges with per-Relationship-type colours.

   Renders **structure only** by default: shapes, labels, Relationship
   lines. Clinical fields (`notes`, `body_location`) are excluded
   because the PDF is a client-facing hand-out (see ADR-0008).

   For the low-fidelity glanceable thumbnail used on the Maps list,
   see `aps.parts.render.preview`. The two renderers exist for
   different *jobs* (recognition vs. print artifact), not different
   output formats."
  (:require
   [aps.parts.common.geometry :as geometry]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [hiccup.util :refer [raw-string]]
   [hiccup2.core :refer [html]])
  (:import
   (java.awt Font RenderingHints)
   (java.awt.font FontRenderContext)
   (java.awt.geom AffineTransform)))

(def ^:private part-types
  "Every Part type the renderer knows how to draw. Mirrors the CHECK
   constraint on `parts.type` and the filenames in
   `resources/public/images/nodes/`."
  ["unknown" "exile" "manager" "firefighter"])

(defn- ->symbol
  "Convert one Part-type SVG file into a `<symbol>` string: strip the
   XML/DOCTYPE preamble, rewrite the outer `<svg …>` to
   `<symbol id=\"part-{type}\" viewBox=\"…\">`, and close as `</symbol>`.

   `<symbol>`'s viewBox auto-stretches into the `<use>` element's
   width/height, which reproduces the canvas's CSS squish for free —
   manager's 100×108 and firefighter's 120×120 both render correctly
   into a 100×100 `<use>` without us doing any transform math."
  [type]
  (let [path     (str "public/images/nodes/" type ".svg")
        raw      (slurp (io/resource path))
        no-pre   (str/replace raw
                              #"(?m)^<\?xml[^>]*\?>\s*|^<!DOCTYPE[^>]*>\s*"
                              "")
        view-box (or (second (re-find #"viewBox=\"([^\"]+)\"" no-pre))
                     "0 0 100 100")]
    (-> no-pre
        (str/replace #"<svg[^>]*>"
                     (str "<symbol id=\"part-" type "\" viewBox=\"" view-box "\">"))
        (str/replace "</svg>" "</symbol>")
        str/trim)))

(def ^:private shape-symbols
  "All four Part-type SVGs as `<symbol>` strings, joined for direct
   embedding inside `<defs>`. Loaded once at namespace init."
  (delay (str/join "\n" (map ->symbol part-types))))

(def ^:private default-viewbox
  "viewBox for an empty Map. Non-degenerate so consumers can size an
   `<img>` against it without divide-by-zero in the browser."
  [0 0 200 100])

(def ^:private viewbox-padding
  "Pixels of breathing space added on every side of the Parts' bounding
   box. Keeps Parts off the SVG border."
  20)

(defn- viewbox
  "viewBox vector `[x y w h]` for the rendered SVG — the bounding box of
   every Part's measured rectangle, expanded by `viewbox-padding` on each
   side. Falls back to `default-viewbox` when the Map is empty."
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

(defn- part-use
  "A `<use>` element placing one Part's shape symbol at its measured
   rectangle."
  [{:keys [type position_x position_y width height]}]
  [:use {:href   (str "#part-" type)
         :x      position_x
         :y      position_y
         :width  (or width  100)
         :height (or height 100)}])

;; ---- Labels -------------------------------------------------------------
;;
;; SVG `<text>` does not auto-wrap, so we compute line breaks server-side
;; with `java.awt.FontMetrics` (via `Font/getStringBounds`). Avoids
;; `<foreignObject>`, whose Batik support is incomplete (see ADR-0008).
;;
;; Font measurement uses Liberation Sans if the JVM can resolve it (true on
;; production Linux); otherwise AWT silently falls back to a logical font,
;; which measures slightly differently. The SVG declares the same fallback
;; chain so the browser / Batik render against the closest match they have.

(def ^:private label-font-size 14)
(def ^:private label-line-height 17)
(def ^:private label-font-family "\"Liberation Sans\", Arial, sans-serif")
(def ^:private label-max-lines 3)
(def ^:private label-inset
  "Horizontal padding inside a Part's box: usable text width is
   `width − 2 × label-inset`. Keeps labels from kissing the shape outline."
  10)
(def ^:private label-ellipsis "…")

(def ^:private ^Font label-font
  (Font. "Liberation Sans" Font/PLAIN label-font-size))

(def ^:private ^FontRenderContext frc
  ;; Pass the AA / fractional-metrics hints as RenderingHints values rather
  ;; than booleans: Clojure boxes booleans to `Boolean`, which makes the
  ;; (AffineTransform, Object, Object) overload match instead of
  ;; (AffineTransform, boolean, boolean) — and that overload validates the
  ;; values, rejecting Boolean with "AA hint:true". Picking the constants
  ;; explicitly side-steps the overload-resolution surprise.
  (FontRenderContext. (AffineTransform.)
                      RenderingHints/VALUE_TEXT_ANTIALIAS_ON
                      RenderingHints/VALUE_FRACTIONALMETRICS_ON))

(defn- string-width
  "Pixel width of `s` rendered in `label-font`."
  [^String s]
  (.getWidth (.getStringBounds label-font s frc)))

(defn- wrap-to-lines
  "Greedy word-wrap `text` into lines whose width does not exceed
   `max-px`. A single word wider than the box becomes its own line — we
   do not character-wrap; Part names are usually short enough that this
   degenerate case doesn't matter visually."
  [text max-px]
  (loop [words   (remove str/blank? (str/split text #"\s+"))
         current ""
         lines   []]
    (if (empty? words)
      (if (empty? current) lines (conj lines current))
      (let [word    (first words)
            attempt (if (empty? current) word (str current " " word))]
        (if (<= (string-width attempt) max-px)
          (recur (rest words) attempt lines)
          (if (empty? current)
            (recur (rest words) "" (conj lines word))
            (recur words "" (conj lines current))))))))

(defn- truncate-with-ellipsis
  "Shrink `text` one character at a time from the right until
   `text + ellipsis` fits within `max-px`. Returns the truncated string
   plus the ellipsis. Returns just the ellipsis if no prefix fits."
  [text max-px]
  (loop [s text]
    (cond
      (empty? s)
      label-ellipsis

      (<= (string-width (str s label-ellipsis)) max-px)
      (str s label-ellipsis)

      :else
      (recur (subs s 0 (dec (count s)))))))

(defn- wrap-label
  "Wrap a label to fit `box-width-px`, capped at `label-max-lines`. If
   wrapping overflows, the last line absorbs the remainder and is
   truncated with an ellipsis."
  [label box-width-px]
  (let [usable (- box-width-px (* 2 label-inset))
        lines  (wrap-to-lines label usable)]
    (if (<= (count lines) label-max-lines)
      lines
      (let [kept  (vec (take (dec label-max-lines) lines))
            spill (str/join " " (drop (dec label-max-lines) lines))]
        (conj kept (truncate-with-ellipsis spill usable))))))

;; ---- Edges --------------------------------------------------------------
;;
;; Server-side port of the canvas's edge routing. Singular Relationships
;; use a cubic Bezier with per-side tangents — a Clojure port of
;; ReactFlow's `getBezierPath`. Bidirectional pairs use a quadratic bow
;; mirroring `aps.parts.frontend.components.edges/quadratic-path`.
;;
;; Edges always start and end on the visible shape outline, computed via
;; `aps.parts.common.geometry` — same module the canvas uses, so a
;; refactor of shape math touches one place.

(def ^:private edge-stroke-colors
  "Per-Relationship-type stroke colour, mirroring the `--edge-color-*`
   CSS custom properties in `resources/styles/main.css`. Duplicated by
   design: an SVG generated server-side cannot read CSS custom
   properties, so the colour palette lives in two places. Drift is
   caught at manual review (see ADR-0008)."
  {"unknown"      "#999999"
   "protective"   "#4caf50"
   "polarization" "#f44336"
   "alliance"     "#2196f3"
   "burden"       "#ff9800"
   "blended"      "#9c27b0"})

(def ^:private edge-stroke-width 1.5)

(def ^:private bezier-curvature
  "Default curvature constant in ReactFlow's `getBezierPath`. Used by
   `control-offset` to scale the negative-distance fallback."
  0.25)

(def ^:private bow-offset-px
  "Perpendicular distance each edge in a bidirectional pair is bowed off
   the straight chord. Mirrors `bow-offset-px` in
   `aps.parts.frontend.components.edges`."
  50)

(defn- control-offset
  "Port of ReactFlow's `calculateControlOffset`. When the target lies in
   the direction this face exits (`distance ≥ 0`), the control sits half
   the distance away — a strong forward curve. When the target lies the
   wrong way (`distance < 0`), the control sits a smaller distance based
   on `√|distance|` — a gentler loop to reach around."
  [distance]
  (if (>= distance 0)
    (* 0.5 distance)
    (* bezier-curvature 25 (Math/sqrt (- distance)))))

(defn- control-point
  "Bezier control point for an endpoint at (x,y) leaving its node on
   `side`, with the other endpoint at (ox,oy). `side` is one of
   :top :right :bottom :left."
  [side x y ox oy]
  (case side
    :left   [(- x (control-offset (- x ox))) y]
    :right  [(+ x (control-offset (- ox x))) y]
    :top    [x (- y (control-offset (- y oy)))]
    :bottom [x (+ y (control-offset (- oy y)))]))

(defn- bezier-d
  "Cubic-Bezier `d` attribute string from (sx,sy) to (tx,ty) with control
   points chosen per the connecting faces."
  [sx sy s-side tx ty t-side]
  (let [[sc-x sc-y] (control-point s-side sx sy tx ty)
        [tc-x tc-y] (control-point t-side tx ty sx sy)]
    (str "M" sx "," sy
         " C" sc-x "," sc-y " " tc-x "," tc-y " " tx "," ty)))

(defn- quadratic-d
  "Quadratic-Bezier `d` attribute string from (sx,sy) to (tx,ty) bowed
   perpendicular to the chord by `offset` pixels. Mirrors the canvas's
   `quadratic-path` so bidirectional pairs separate visually."
  [sx sy tx ty offset]
  (let [mx  (/ (+ sx tx) 2)
        my  (/ (+ sy ty) 2)
        dx  (- tx sx)
        dy  (- ty sy)
        len (Math/sqrt (+ (* dx dx) (* dy dy)))]
    (if (zero? len)
      (str "M" sx "," sy " L" tx "," ty)
      (let [px (* (/ (- dy) len) offset)
            py (* (/    dx  len) offset)]
        (str "M" sx "," sy
             " Q" (+ mx px) "," (+ my py) " " tx "," ty)))))

(defn- part-center
  "Centre of a Part's measured rectangle."
  [{:keys [position_x position_y width height]}]
  [(+ position_x (/ (or width  100) 2))
   (+ position_y (/ (or height 100) 2))])

(defn- edge-endpoints
  "Visible-shape intersection points and edge sides for a Relationship
   from `source` to `target`. Returns `{:sx :sy :tx :ty :s-side :t-side}`."
  [source target]
  (let [[s-cx s-cy] (part-center source)
        [t-cx t-cy] (part-center target)
        s-pt        (geometry/intersection-for-shape
                     (geometry/shape-for (:type source))
                     (:position_x source) (:position_y source)
                     (or (:width source) 100) (or (:height source) 100)
                     t-cx t-cy)
        t-pt        (geometry/intersection-for-shape
                     (geometry/shape-for (:type target))
                     (:position_x target) (:position_y target)
                     (or (:width target) 100) (or (:height target) 100)
                     s-cx s-cy)]
    {:sx     (:x s-pt)
     :sy     (:y s-pt)
     :tx     (:x t-pt)
     :ty     (:y t-pt)
     :s-side (geometry/classify-side s-pt s-cx s-cy)
     :t-side (geometry/classify-side t-pt t-cx t-cy)}))

(defn- bidirectional-pairs
  "Set of unordered `#{source-id target-id}` pairs that have BOTH
   directions among the Relationships. Members render as bowed quadratic
   arcs so the two opposing edges don't draw on top of each other."
  [relationships]
  (let [pairs (set (map (juxt :source_id :target_id) relationships))]
    (set
     (keep (fn [[s t]]
             (when (and (not= s t) (pairs [t s]))
               #{s t}))
           pairs))))

(defn- relationship-path
  "A `<path>` element for one Relationship, bezier or bowed quadratic
   depending on whether its mirror exists. Returns nil when either
   endpoint Part is missing — protects against a stale Relationship row
   pointing at a since-deleted Part."
  [parts-by-id bidi {:keys [source_id target_id type]}]
  (let [source (parts-by-id source_id)
        target (parts-by-id target_id)]
    (when (and source target)
      (let [{:keys [sx sy tx ty s-side t-side]} (edge-endpoints source target)
            d                                   (if (bidi #{source_id target_id})
                                                  (quadratic-d sx sy tx ty bow-offset-px)
                                                  (bezier-d sx sy s-side tx ty t-side))]
        [:path {:d            d
                :fill         "none"
                :stroke       (get edge-stroke-colors type "#999999")
                :stroke-width edge-stroke-width
                :marker-end   "url(#edge-arrow)"}]))))

(def ^:private edge-arrow-marker
  "Single SVG `<marker>` definition reused by every edge — mirrors the
   one defined in `frontend.components.map`. `fill=\"context-stroke\"`
   makes the arrowhead inherit the referencing path's stroke colour, so
   one marker covers all six Relationship types."
  [:marker {:id           "edge-arrow"
            :viewBox      "0 0 10 10"
            :refX         9
            :refY         5
            :markerUnits  "strokeWidth"
            :markerWidth  6
            :markerHeight 6
            :orient       "auto-start-reverse"}
   [:path {:d "M 0 0 L 10 5 L 0 10 z" :fill "context-stroke"}]])

;; ---- Labels (continued) -------------------------------------------------

(defn- part-label
  "A `<text>` element holding wrapped label lines for one Part, centred on
   the Part's box. Returns nil when the label is nil or blank — emitting
   an empty `<text>` is wasteful and visually noisy."
  [{:keys [label position_x position_y width height]}]
  (when-let [text (some-> label str/trim not-empty)]
    (let [w        (or width  100)
          h        (or height 100)
          cx       (+ position_x (/ w 2))
          cy       (+ position_y (/ h 2))
          lines    (wrap-label text w)
          n-lines  (count lines)
          first-cy (- cy (* (/ (dec n-lines) 2.0) label-line-height))]
      [:text {:x                 cx
              :y                 first-cy
              :text-anchor       "middle"
              :dominant-baseline "middle"
              :font-family       label-font-family
              :font-size         label-font-size
              :font-weight       500
              :fill              "currentColor"}
       (map-indexed
        (fn [i line]
          [:tspan {:x  cx
                   :dy (if (zero? i) 0 label-line-height)}
           line])
        lines)])))

(defn render
  "Render a hydrated Map to a document-grade SVG string. Inlines the
   styled per-type SVG shapes, wraps labels with FontMetrics, draws
   per-side bezier edges with per-Relationship-type stroke colours and
   shared arrowhead marker.

   Input shape:

     {:parts         [{:id :type :label
                       :position_x :position_y :width :height} …]
      :relationships [{:id :source_id :target_id :type} …]}

   Returns a self-contained SVG string. Document chrome (title, date,
   'Made with Parts' footer) and the Apache Batik PDF transcode arrive
   in later increments — see ADR-0008."
  [{:keys [parts relationships] :as _the-map}]
  (let [[vx vy vw vh] (viewbox parts)
        by-id         (into {} (map (juxt :id identity)) parts)
        bidi          (bidirectional-pairs relationships)]
    (str
     (html
      [:svg {:xmlns   "http://www.w3.org/2000/svg"
             :viewBox (str vx " " vy " " vw " " vh)}
       [:defs
        (raw-string @shape-symbols)
        edge-arrow-marker]
       ;; Edges first, then Parts on top so the shape covers the edge
       ;; end-cap at the outline (the intersection geometry already
       ;; terminates the line there, but the overlap is forgiving).
       (keep (partial relationship-path by-id bidi) relationships)
       (map part-use parts)
       (keep part-label parts)]))))
