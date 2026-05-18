(ns aps.parts.frontend.components.edges
  (:require
   ["@xyflow/react" :refer [BaseEdge Position
                            getBezierPath useInternalNode useStore]]
   [uix.core :refer [$ as-react defui]]))

(def ^:private bow-offset-px
  "Perpendicular distance each edge in a bidirectional pair is bowed off
   the straight chord. Both edges use the same sign — opposite chord
   vectors flip the perpendicular for free."
  50)

(defn- node-intersection
  "Return the {:x :y} point on the border of `intersection-node` where the
   straight line from the centre of `other-node` to the centre of
   `intersection-node` crosses the border.

   Both nodes are React Flow internal nodes:
     (.. node -internals -positionAbsolute) -> #js {:x :y}   (top-left)
     (.. node -measured -width)              -> number
     (.. node -measured -height)             -> number

   Used to make edges 'float' to a node's nearest visible border point
   rather than binding to a fixed top/bottom handle.

   Algorithm (from the React Flow floating-edges example): normalise the
   centre-to-centre vector against the rectangle's half-extents, then map
   the unit vector onto the border. Compact but opaque — see the linked
   example for derivation."
  [^js intersection-node ^js other-node]
  (let [i-pos (.. intersection-node -internals -positionAbsolute)
        i-w   (.. intersection-node -measured -width)
        i-h   (.. intersection-node -measured -height)
        o-pos (.. other-node -internals -positionAbsolute)
        o-w   (.. other-node -measured -width)
        o-h   (.. other-node -measured -height)
        w     (/ i-w 2)
        h     (/ i-h 2)
        x2    (+ (.-x i-pos) w)
        y2    (+ (.-y i-pos) h)
        x1    (+ (.-x o-pos) (/ o-w 2))
        y1    (+ (.-y o-pos) (/ o-h 2))
        xx1   (- (/ (- x1 x2) (* 2 w)) (/ (- y1 y2) (* 2 h)))
        yy1   (+ (/ (- x1 x2) (* 2 w)) (/ (- y1 y2) (* 2 h)))
        a     (/ 1 (+ (Math/abs xx1) (Math/abs yy1)))
        xx3   (* a xx1)
        yy3   (* a yy1)]
    {:x (+ (* w (+ xx3 yy3)) x2)
     :y (+ (* h (+ (- xx3) yy3)) y2)}))

(defn- edge-side
  "Classify which side of `node` an intersection point `{:x :y}` lies on.
   Returns one of Position/Top, Position/Right, Position/Bottom, Position/Left."
  [^js node {:keys [x y]}]
  (let [pos    (.. node -internals -positionAbsolute)
        nx     (.-x pos)
        ny     (.-y pos)
        width  (.. node -measured -width)
        height (.. node -measured -height)
        px     (Math/round x)
        py     (Math/round y)]
    (cond
      (<= px (inc nx))            (.-Left Position)
      (>= px (dec (+ nx width)))  (.-Right Position)
      (<= py (inc ny))            (.-Top Position)
      (>= py (dec (+ ny height))) (.-Bottom Position)
      :else                       (.-Top Position))))

(defn- floating-edge-params
  "Compute floating endpoint coordinates and side-positions for an edge
   between two measured React Flow nodes."
  [^js source-node ^js target-node]
  (let [s (node-intersection source-node target-node)
        t (node-intersection target-node source-node)]
    {:sx         (:x s)
     :sy         (:y s)
     :tx         (:x t)
     :ty         (:y t)
     :source-pos (edge-side source-node s)
     :target-pos (edge-side target-node t)}))

(defn- bezier-path
  [{:keys [sx sy tx ty source-pos target-pos]}]
  (first (getBezierPath #js {:sourceX        sx
                             :sourceY        sy
                             :targetX        tx
                             :targetY        ty
                             :sourcePosition source-pos
                             :targetPosition target-pos})))

(defn- quadratic-path
  "Build a quadratic SVG path from (sx,sy) to (tx,ty) bowed perpendicular
   to the chord by `offset` pixels. Used for bidirectional pairs so the
   two opposing edges don't draw on top of each other."
  [{:keys [sx sy tx ty]} offset]
  (let [mx  (/ (+ sx tx) 2)
        my  (/ (+ sy ty) 2)
        dx  (- tx sx)
        dy  (- ty sy)
        len (Math/sqrt (+ (* dx dx) (* dy dy)))
        ;; Perpendicular unit vector (chord rotated 90° CCW). The two edges
        ;; of a bidirectional pair have opposite chord vectors, so they
        ;; naturally get opposite perpendiculars — same offset sign for both.
        nx  (if (zero? len) 0 (/ (- dy) len))
        ny  (if (zero? len) 0 (/ dx len))
        cx  (+ mx (* offset nx))
        cy  (+ my (* offset ny))]
    (str "M" sx "," sy " Q" cx "," cy " " tx "," ty)))

(defn- has-reverse-edge?
  "Return true if the edges store contains any edge whose source/target
   are the reverse of the given pair. Used to decide whether to bow."
  [^js state source-id target-id]
  (boolean (some #(and (= (.-source %) target-id)
                       (= (.-target %) source-id))
                 (.-edges state))))

(defui parts-edge [{:keys [id data source-id target-id marker-end]}]
  (let [source-node (useInternalNode source-id)
        target-node (useInternalNode target-id)
        rel-type    (:relationship data)
        bidir?      (useStore (fn [^js state]
                                (has-reverse-edge? state source-id target-id)))]
    (when (and source-node target-node
               (.-measured source-node) (.-measured target-node))
      (let [params     (floating-edge-params source-node target-node)
            class-name (str "edge edge-" rel-type)
            path       (if bidir?
                         (quadratic-path params bow-offset-px)
                         (bezier-path params))]
        ($ BaseEdge {:path      path
                     :className class-name
                     :id        id
                     :markerEnd marker-end})))))

(def PartsEdge
  (as-react
   (fn [{:keys [id data source target markerEnd]}]
     ($ parts-edge {:id         id
                    :data       (js->clj data :keywordize-keys true)
                    :source-id  source
                    :target-id  target
                    :marker-end markerEnd}))))

(def edge-types
  {"default" PartsEdge})
