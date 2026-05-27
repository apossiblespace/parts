(ns aps.parts.render.document.edges
  "Edge rendering for the document SVG. Singular Relationships draw as
   cubic Beziers with per-side tangents (a Clojure port of ReactFlow's
   `getBezierPath`); bidirectional pairs draw as a quadratic bow so the
   two opposing edges don't overlap. Edges always start/end on the
   visible shape outline via `aps.parts.common.geometry`.

   Each Relationship's stroke colour comes from
   `aps.parts.common.constants/relationship-colors`. A `<marker>` per
   type is emitted into `<defs>` with its colour baked in — the SVG-2
   `fill=\"context-stroke\"` trick the canvas uses isn't supported by
   Apache FOP / Batik."
  (:require
   [aps.parts.common.constants :as c]
   [aps.parts.common.geometry :as geometry]))

(def ^:private edge-stroke-width 1.5)

(def ^:private bezier-curvature
  "Default curvature constant in ReactFlow's `getBezierPath`. Scales
   the negative-distance fallback in `control-offset`."
  0.25)

(defn- control-offset
  "Port of ReactFlow's `calculateControlOffset`. When the target lies
   in the direction this face exits, the control sits half the distance
   away (strong forward curve); otherwise a smaller distance based on
   `√|distance|` (a gentler loop reaching around).

   Stays local to the document renderer: the canvas uses ReactFlow's
   JS `getBezierPath` directly, so lifting the port to
   `common.geometry` wouldn't deduplicate anything until/unless the
   canvas also stops calling ReactFlow's built-in."
  [distance]
  (if (>= distance 0)
    (* 0.5 distance)
    (* bezier-curvature 25 (Math/sqrt (- distance)))))

(defn- control-point
  [side x y ox oy]
  (case side
    :left   [(- x (control-offset (- x ox))) y]
    :right  [(+ x (control-offset (- ox x))) y]
    :top    [x (- y (control-offset (- y oy)))]
    :bottom [x (+ y (control-offset (- oy y)))]))

(defn- bezier-d
  "Cubic-Bezier `d` attribute from (sx,sy) to (tx,ty)."
  [sx sy s-side tx ty t-side]
  (let [[sc-x sc-y] (control-point s-side sx sy tx ty)
        [tc-x tc-y] (control-point t-side tx ty sx sy)]
    (str "M" sx "," sy
         " C" sc-x "," sc-y " " tc-x "," tc-y " " tx "," ty)))

(defn- edge-endpoints
  "Visible-shape intersection points and edge sides for an edge from
   `source` to `target`. Returns `{:sx :sy :tx :ty :s-side :t-side}`."
  [source target]
  (let [[s-cx s-cy] (geometry/part-center source)
        [t-cx t-cy] (geometry/part-center target)
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

(defn- arrow-marker-id [type]
  (str "edge-arrow-" type))

(defn relationship-path
  "A `<path>` element for one Relationship, bezier or bowed quadratic
   depending on whether its mirror exists. Returns nil when either
   endpoint Part is missing — protects against a stale Relationship row
   pointing at a since-deleted Part."
  [parts-by-id bidi-pairs {:keys [source_id target_id type] :as rel}]
  (let [source (parts-by-id source_id)
        target (parts-by-id target_id)]
    (when (and source target)
      (let [type-kw                             (keyword type)
            type'                               (if (contains? c/relationship-colors type-kw)
                                                  type "unknown")
            colour                              (c/relationship-colors (keyword type'))
            endpoints                           (edge-endpoints source target)
            {:keys [sx sy s-side tx ty t-side]} endpoints
            d                                   (if (geometry/bidirectional? bidi-pairs rel)
                                                  (geometry/quadratic-path endpoints geometry/bow-offset-px)
                                                  (bezier-d sx sy s-side tx ty t-side))]
        [:path {:d            d
                :fill         "none"
                :stroke       colour
                :stroke-width edge-stroke-width
                :marker-end   (str "url(#" (arrow-marker-id type') ")")}]))))

(defn- arrow-marker
  "A `<marker>` element with its arrowhead colour baked in. Two
   deliberate deviations from the canvas's marker keep this
   FOP-friendly:

   - The canvas's `fill=\"context-stroke\"` (inherit the path's stroke,
     SVG 2) becomes a pre-resolved per-type colour. One marker per
     Relationship type instead of one shared marker.
   - The canvas's `orient=\"auto-start-reverse\"` (SVG 2) becomes plain
     `orient=\"auto\"` (SVG 1.1). We only use markers as `marker-end`,
     so the SVG-2 niceness isn't needed."
  [type colour]
  [:marker {:id           (arrow-marker-id type)
            :viewBox      "0 0 10 10"
            :refX         9
            :refY         5
            :markerUnits  "strokeWidth"
            :markerWidth  6
            :markerHeight 6
            :orient       "auto"}
   [:path {:d "M 0 0 L 10 5 L 0 10 z" :fill colour}]])

(def edge-arrow-markers
  "One `<marker>` per Relationship type. Each `<path>` references the
   marker whose id ends with the Relationship's type. `map` not `mapv`
   because Hiccup interprets a vector at a child position as one
   element (first item = tag); a seq is iterated as multiple children."
  (map (fn [[type-kw colour]] (arrow-marker (name type-kw) colour))
       c/relationship-colors))
