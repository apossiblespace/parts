(ns aps.parts.frontend.components.edges
  (:require
   ["@xyflow/react" :refer [BaseEdge EdgeLabelRenderer Position
                            getBezierPath useInternalNode]]
   [aps.parts.common.constants :as constants]
   [aps.parts.common.geometry :as geometry]
   [uix.core :refer [$ as-react defui]]
   [uix.re-frame :as uix.rf]))

(defn- node-intersection
  "Point on `intersection-node`'s visible shape where the centre-to-centre
   line from `other-node` crosses. Dispatches on the Part type so each
   shape's actual outline is used — see `geometry/shape-for` for the
   per-type definitions.

   Both nodes are React Flow internal nodes:
     (.. node -internals -positionAbsolute) -> #js {:x :y}   (top-left)
     (.. node -measured -width)              -> number
     (.. node -measured -height)             -> number
     (.. node -data -type)                   -> Part type string"
  [^js intersection-node ^js other-node]
  (let [i-pos (.. intersection-node -internals -positionAbsolute)
        i-w   (.. intersection-node -measured -width)
        i-h   (.. intersection-node -measured -height)
        o-pos (.. other-node -internals -positionAbsolute)
        o-w   (.. other-node -measured -width)
        o-h   (.. other-node -measured -height)
        o-cx  (+ (.-x o-pos) (/ o-w 2))
        o-cy  (+ (.-y o-pos) (/ o-h 2))
        shape (geometry/shape-for (.. intersection-node -data -type))]
    (geometry/intersection-for-shape shape (.-x i-pos) (.-y i-pos)
                                     i-w i-h o-cx o-cy)))

(defn- edge-side
  "Map the intersection point on `node`'s outline to a ReactFlow Position
   enum (Top/Right/Bottom/Left) so getBezierPath can pick a tangent."
  [^js node intersection]
  (let [pos (.. node -internals -positionAbsolute)
        cx  (+ (.-x pos) (/ (.. node -measured -width) 2))
        cy  (+ (.-y pos) (/ (.. node -measured -height) 2))]
    (case (geometry/classify-side intersection cx cy)
      :top    (.-Top Position)
      :right  (.-Right Position)
      :bottom (.-Bottom Position)
      :left   (.-Left Position))))

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

(defui parts-edge [{:keys [id data source-id target-id marker-end]}]
  (let [source-node (useInternalNode source-id)
        target-node (useInternalNode target-id)
        rel-type    (:relationship data)
        bidir?      (:bidir data)]
    (when (and source-node target-node
               (.-measured source-node) (.-measured target-node))
      (let [params     (floating-edge-params source-node target-node)
            class-name (str "edge edge-" rel-type)
            path       (if bidir?
                         (geometry/quadratic-path params geometry/bow-offset-px)
                         (bezier-path params))]
        ($ :<>
           ($ BaseEdge {:path      path
                        :className class-name
                        :id        id
                        :markerEnd marker-end})
           ;; EdgeLabelRenderer is ReactFlow's HTML overlay for edge
           ;; decorations — SVG <text> can't carry the badge styling.
           (when-let [ordinal (:firstAppeared data)]
             (let [{:keys [x y]} (geometry/curve-midpoint
                                  params
                                  (if bidir? geometry/bow-offset-px 0))]
               ($ EdgeLabelRenderer
                  ($ :span {:class (str "recency-badge on-edge"
                                        (when (:recent data) " recent"))
                            :title (str "First appeared in Session " ordinal)
                            :style #js {:transform
                                        (str "translate(-50%, -50%) translate("
                                             x "px," y "px)")}}
                     (str "S" ordinal))))))))))

(def PartsEdge
  (as-react
   (fn [{:keys [id data source target markerEnd]}]
     ($ parts-edge {:id         id
                    :data       (js->clj data :keywordize-keys true)
                    :source-id  source
                    :target-id  target
                    :marker-end markerEnd}))))

(def edge-types
  #js {:default PartsEdge})

;; -- Connection preview line --------------------------------------------
;; While the user drags out a connection, ReactFlow renders a "connection
;; line" preview. Default is a straight Bezier from the source handle to
;; the cursor; we want it to match the floating-edge shape — same
;; source-border intersection, same Bezier curvature, and the colour of
;; the relationship type selected in the toolbar — so the preview previews
;; what will land.

(defn- opposite-position [pos]
  (condp = pos
    (.-Top Position)    (.-Bottom Position)
    (.-Bottom Position) (.-Top Position)
    (.-Left Position)   (.-Right Position)
    (.-Right Position)  (.-Left Position)
    (.-Bottom Position)))

(defui parts-connection-line [{:keys [from-node to-node to-x to-y]}]
  (let [rel-type (uix.rf/use-subscribe [:ui/relationship-type])
        ;; Hovering a prospective target: draw the exact path the edge
        ;; will take — both endpoints on shape boundaries — instead of
        ;; chasing the cursor to the target's centre (the whole-node drop
        ;; handle reports its centre as the snap point). Self-loops keep
        ;; the cursor endpoint: identical centres make the intersection
        ;; math degenerate.
        target?  (and to-node (.-measured to-node)
                      (not= (.-id to-node) (.-id from-node)))]
    (when (and from-node (.-measured from-node))
      (let [path (if target?
                   (bezier-path (floating-edge-params from-node to-node))
                   (let [cursor-node #js {:internals #js {:positionAbsolute #js {:x to-x :y to-y}}
                                          :measured  #js {:width 0 :height 0}}
                         from-point  (node-intersection from-node cursor-node)
                         source-pos  (edge-side from-node from-point)]
                     (bezier-path {:sx         (:x from-point)
                                   :sy         (:y from-point)
                                   :tx         to-x
                                   :ty         to-y
                                   :source-pos source-pos
                                   :target-pos (opposite-position source-pos)})))]
        ($ :path {:d         path
                  :className "react-flow__connection-path"
                  :style     #js {:stroke      (constants/relationship-colors rel-type)
                                  :strokeWidth 1.5
                                  :fill        "none"}})))))

(def PartsConnectionLine
  (as-react
   (fn [{:keys [fromNode toNode toX toY]}]
     ($ parts-connection-line {:from-node fromNode
                               :to-node   toNode
                               :to-x      toX
                               :to-y      toY}))))
