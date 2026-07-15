(ns aps.parts.render.document.edges
  "Edge rendering for the document SVG. The curves themselves — cubic
   bezier, bidirectional bow, Intensity jaggedness — come from
   `geometry/edge-path`, the same source the canvas draws from. Edges
   always start/end on the visible shape outline via
   `aps.parts.common.geometry`.

   Each Relationship's stroke colour comes from
   `aps.parts.common.constants/relationship-colors`. A `<marker>` per
   type is emitted into `<defs>` with its colour baked in — the SVG-2
   `fill=\"context-stroke\"` trick the canvas uses isn't supported by
   Apache FOP / Batik."
  (:require
   [aps.parts.common.constants :as c]
   [aps.parts.common.geometry :as geometry]))

(def ^:private edge-stroke-width 1.5)

(defn- edge-endpoints
  "Visible-shape intersection points and edge sides for an edge from
   `source` to `target`, from the shared border-to-border chord. Returns
   `{:sx :sy :tx :ty :s-side :t-side}`, or nil for a degenerate pair."
  [source target]
  (when-let [[[sx sy] [tx ty]] (geometry/edge-chord source target)]
    (let [[s-cx s-cy] (geometry/part-center source)
          [t-cx t-cy] (geometry/part-center target)]
      {:sx     sx
       :sy     sy
       :tx     tx
       :ty     ty
       :s-side (geometry/classify-side {:x sx :y sy} s-cx s-cy)
       :t-side (geometry/classify-side {:x tx :y ty} t-cx t-cy)})))

(defn- arrow-marker-id [type]
  (str "edge-arrow-" type))

(defn relationship-path
  "A `<path>` element for one Relationship, bezier or bowed quadratic
   depending on whether its mirror exists. Returns nil when either
   endpoint Part is missing — protects against a stale Relationship row
   pointing at a since-deleted Part."
  [parts-by-id bidi-pairs {:keys [source_id target_id type intensity] :as rel}]
  (let [source (parts-by-id source_id)
        target (parts-by-id target_id)]
    (when-let [endpoints (and source target (edge-endpoints source target))]
      (let [type-kw (keyword type)
            type'   (if (contains? c/relationship-colors type-kw)
                      type "unknown")
            colour  (c/relationship-colors (keyword type'))
            d       (geometry/edge-path
                     endpoints
                     {:bow?      (geometry/bidirectional? bidi-pairs rel)
                      :intensity intensity})]
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
