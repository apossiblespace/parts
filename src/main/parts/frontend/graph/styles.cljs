(ns parts.frontend.graph.styles)

(def manager-points
  "0 -1
   0.866 -0.5
   0.866 0.5
   0 1
   -0.866 0.5
   -0.866 -0.5"
  )

(def firefighter-points
  (str (apply str
         (for [i (range 12)]
           (let [angle (* i (/ (* 2 Math/PI) 12))
                 angle2 (+ angle (/ Math/PI 12))
                 x1 (* (Math/cos angle) 1)
                 y1 (* (Math/sin angle) 1)
                 x2 (* (Math/cos angle2) 0.8)
                 y2 (* (Math/sin angle2) 0.8)]
             (str x1 " " y1 " " x2 " " y2 " "))))))

(def styles
  [
   {:selector "node"
    :style {"shape" "round-rectangle"  ;; for unknown
            "background-color" "#999999"
            "background-opacity" 0.2
            "border-width" "1px"
            "border-color" "#999999"
            "width" "100px"
            "height" "100px"
            "text-valign" "center"
            "text-halign" "center"
            "label" "data(label)"}}
   {:selector "edge"
    :style {"width" 2
            "line-color" "#666"
            "target-arrow-color" "#666"
            "target-arrow-shape" "triangle"
            "curve-style" "bezier"}}
   {:selector "node.manager"
    :style {"shape" "polygon"
            "shape-polygon-points" manager-points
            "background-color" "#D1B43C"
            "background-opacity" 0.3
            "border-width" "1px"
            "border-color" "#D1B43C"
            "height" "105px"
            "width" "110px"}}
   {:selector "node.exile"
    :style {"shape" "ellipse"
            "background-color" "#62A294"
            "background-opacity" 0.2
            "border-width" "1px"
            "border-color" "#62A294"}}
   {:selector "node.firefighter"
    :style {"shape" "polygon"
            "shape-polygon-points" firefighter-points
            "background-color" "#F2BE56"
            "background-opacity" 0.2
            "border-width" "1px"
            "border-color" "#F2BE56"
            "height" "120px"
            "width" "120px"}}])
