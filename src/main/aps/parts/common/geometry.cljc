(ns aps.parts.common.geometry
  "Pure 2D geometry for attaching edges to a node's visible shape rather than
   its bounding box. A Part's visual SVG (hex / star / circle) is inscribed
   in its measured rectangle, so a naive centre-to-centre line clipped at the
   rectangle border ends outside the visible shape. The functions here clip
   to per-part-type polygons (or a circle) instead.

   Consumed by both the canvas (`@xyflow/react` edge routing in
   `aps.parts.frontend.components.edges`) and the server-side **Render**
   (`aps.parts.render`). Stays runtime-neutral — `Math/*` interop dispatches
   to `java.lang.Math` on JVM and `js/Math` on JS without reader
   conditionals.")

(def ^:private unit-square
  "Polygon used for square SVGs (`unknown`) and as the fallback for any
   unrecognised part type — both render as the bounding box, so the
   bounding-box polygon clips correctly.

   Vertices are doubles, not integers, so the ray-segment math stays in
   double-land. Integer vertices ×Â integer Part positions × integer ray
   direction would bottom out as a Clojure Ratio at the final `/` —
   correct as a rational number, but `(str 51/2)` is `\"51/2\"` and
   Batik rejects that as an invalid SVG number."
  [[0.0 0.0] [1.0 0.0] [1.0 1.0] [0.0 1.0]])

(def ^:private shape-polygons
  "Per part type: a polygon (vertices in normalised 0..1 coords) approximating
   the visual SVG, or the sentinel :circle. Polygons are scaled to the
   node's measured box at use time.

   Vertices for `manager` come from `resources/public/images/nodes/manager.svg`
   after applying its inner transform and squishing the 100×108 viewBox to
   the rendered 100×100 area.

   `firefighter` is an 8-point sparkle. Its 16 vertices (alternating outer
   tips and inner valleys) sit at fixed angular positions in
   `resources/public/images/nodes/firefighter.svg`. After applying the SVG's
   inner transform and the 120×120 viewBox → 100×100 squish, the outer tips
   land at radius ≈ 0.486 from centre and the valleys at ≈ 0.387. We model
   that here as a regular 8-point star (tip up)."
  {"unknown"     unit-square
   "exile"       :circle
   "manager"     [[0.4785 0.0051] [0.5215 0.0051]
                  [1.0    0.2843] [1.0    0.7433]
                  [0.5215 0.9948] [0.4785 0.9948]
                  [0.0    0.7433] [0.0    0.2843]]
   "firefighter" (let [r-out 0.486
                       r-in  0.387
                       cx    0.5
                       cy    0.5
                       step  (/ (* 2 Math/PI) 8)
                       base  (- (/ Math/PI 2))
                       point (fn [angle r]
                               [(+ cx (* r (Math/cos angle)))
                                (+ cy (* r (Math/sin angle)))])]
                   (vec (mapcat (fn [i]
                                  [(point (+ base (* i step)) r-out)
                                   (point (+ base (* i step) (/ step 2)) r-in)])
                                (range 8))))})

(defn shape-for
  "Shape for the given part type. Unknown types fall back to the unit square —
   the bounding-box polygon degrades cleanly for arbitrary SVGs."
  [type-name]
  (get shape-polygons type-name unit-square))

(defn part-center
  "Centre point `[cx cy]` of a Part's measured rectangle, in world
   coordinates. Defaults missing `width`/`height` to 100 (the DB default
   for legacy rows). Used by both renderers and by edge intersection.

   Always returns doubles: an odd width over integer division would
   produce a Ratio, and a Ratio `str`ed into SVG markup is an invalid
   number — see `unit-square`'s docstring."
  [{:keys [position_x position_y width height]}]
  [(+ position_x (/ (or width  100) 2.0))
   (+ position_y (/ (or height 100) 2.0))])

;; ---- Relationship-edge math ---------------------------------------------
;;
;; Math that's the same regardless of who's drawing. Both the canvas
;; (`frontend/components/edges.cljs`) and the document renderer
;; (`render/document/edges.clj`) consume from here. The bezier path for
;; singular edges is the one piece that *isn't* here yet — the canvas
;; uses ReactFlow's JS `getBezierPath`, and the document renderer ports
;; it independently. Unifying those is a larger move (would require the
;; canvas to stop using ReactFlow's built-in) and isn't done.

(def bow-offset-px
  "Perpendicular distance each edge in a bidirectional pair is bowed
   off the chord. Same on both renderers — same visual."
  50)

(defn bidirectional-pairs
  "Set of unordered `#{source-id target-id}` pairs that have BOTH
   directions among `relationships`. Members render as bowed quadratic
   arcs so the two opposing edges don't overlap."
  [relationships]
  (let [pairs (set (map (juxt :source_id :target_id) relationships))]
    (set
     (keep (fn [[s t]]
             (when (and (not= s t) (pairs [t s]))
               #{s t}))
           pairs))))

(defn bidirectional?
  "Predicate: is the given relationship's pair in the
   `bidirectional-pairs` set? Cheap query against a precomputed set."
  [bidi-pairs {:keys [source_id target_id]}]
  (contains? bidi-pairs #{source_id target_id}))

(defn quadratic-path
  "SVG-path `d` attribute for a quadratic Bezier from (sx,sy) to (tx,ty)
   bowed perpendicular to the chord by `offset` pixels. Used for
   bidirectional edge pairs — opposite chord vectors flip the
   perpendicular automatically, so passing the same `offset` to both
   sides separates them cleanly."
  [{:keys [sx sy tx ty]} offset]
  (let [mx  (/ (+ sx tx) 2)
        my  (/ (+ sy ty) 2)
        dx  (- tx sx)
        dy  (- ty sy)
        len (Math/sqrt (+ (* dx dx) (* dy dy)))]
    (if (zero? len)
      (str "M" sx "," sy " L" tx "," ty)
      (let [nx (/ (- dy) len)
            ny (/    dx  len)
            cx (+ mx (* offset nx))
            cy (+ my (* offset ny))]
        (str "M" sx "," sy " Q" cx "," cy " " tx "," ty)))))

(defn- ray-segment-intersection
  "Where does the ray from (cx,cy) in direction (dx,dy) cross segment
   (ax,ay) → (bx,by)? Returns the ray parameter t ≥ 0 of the hit, or nil
   if the ray misses or hits behind the origin."
  [cx cy dx dy ax ay bx by]
  (let [sx    (- bx ax)
        sy    (- by ay)
        denom (- (* dx sy) (* dy sx))]
    (when-not (zero? denom)
      (let [tx (- ax cx)
            ty (- ay cy)
            t  (/ (- (* tx sy) (* ty sx)) denom)
            s  (/ (- (* tx dy) (* ty dx)) denom)]
        (when (and (>= t 0) (>= s 0) (<= s 1))
          t)))))

(defn polygon-ray-intersection
  "Nearest point where a ray from (cx,cy) in direction (dx,dy) crosses the
   outline of the polygon defined by `vertices` (a sequence of [x y] pairs
   in order). Returns {:x :y} or nil if no segment is crossed. Degenerate
   ray (dx=dy=0) returns the centre, mirroring `circle-ray-intersection`."
  [vertices cx cy dx dy]
  (let [n (count vertices)]
    (cond
      (and (zero? dx) (zero? dy)) {:x cx :y cy}
      (< n 3)                     nil
      :else
      (let [hits (keep
                  (fn [i]
                    (let [[ax ay] (nth vertices i)
                          [bx by] (nth vertices (mod (inc i) n))]
                      (ray-segment-intersection cx cy dx dy ax ay bx by)))
                  (range n))]
        (when (seq hits)
          (let [t (apply min hits)]
            {:x (+ cx (* t dx))
             :y (+ cy (* t dy))}))))))

(defn circle-ray-intersection
  "Point on the circle of radius `r` centred at (cx,cy) where the ray
   toward (ox,oy) exits. Degenerate (ox = cx, oy = cy) returns the centre."
  [cx cy r ox oy]
  (let [dx (- ox cx)
        dy (- oy cy)
        d  (Math/sqrt (+ (* dx dx) (* dy dy)))]
    (if (zero? d)
      {:x cx :y cy}
      {:x (+ cx (* r (/ dx d)))
       :y (+ cy (* r (/ dy d)))})))

(defn intersection-for-shape
  "Where the centre-to-centre line crosses the visible boundary of a node.
   (nx,ny) is the node's top-left, (w,h) its measured size, (ox,oy) the
   other node's centre. `shape` is whatever `shape-for` returned.

   The returned `{:x :y}` coordinates are always doubles. The interior
   math is happy to mix Longs, Ratios, and doubles freely (Clojure
   promotes them through the number tower), but the output crosses into
   string-formatting territory where a Ratio (e.g. `10123/41`) would
   become an invalid SVG number on `str` — see `unit-square`'s
   docstring."
  [shape nx ny w h ox oy]
  (let [cx (+ nx (/ w 2))
        cy (+ ny (/ h 2))
        pt (if (= shape :circle)
             (circle-ray-intersection cx cy (/ (min w h) 2) ox oy)
             (let [world-verts (mapv (fn [[ux uy]]
                                       [(+ nx (* ux w))
                                        (+ ny (* uy h))])
                                     shape)]
               (polygon-ray-intersection world-verts cx cy (- ox cx) (- oy cy))))]
    (when pt
      {:x (double (:x pt))
       :y (double (:y pt))})))

(defn classify-side
  "Which side of the node centre (cx,cy) does the intersection {:x :y} lie
   on? Returns :top :right :bottom or :left. Y is positive downward, as in
   screen coordinates."
  [{:keys [x y]} cx cy]
  (let [dx  (- x cx)
        dy  (- y cy)
        ang (Math/atan2 dy dx)
        q   (/ Math/PI 4)]
    (cond
      (and (>= ang (- q)) (< ang q))                :right
      (and (>= ang q) (< ang (* 3 q)))              :bottom
      (or  (>= ang (* 3 q)) (< ang (- (* 3 q))))    :left
      :else                                         :top)))
