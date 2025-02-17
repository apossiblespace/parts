(ns parts.frontend.graph
  (:require ["cytoscape" :as cytoscape]
            [parts.common.part-types :refer [part-types]]))

(def styles
  [{:selector "node"
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
    :style {"shape" "hexagon"
            "background-color" "#D1B43C"
            "background-opacity" 0.3
            "border-width" "1px"
            "border-color" "#D1B43C"
            "height" "108px"
            "width" "100px"}}
   {:selector "node.exile"
    :style {"shape" "ellipse"
            "background-color" "#62A294"
            "background-opacity" 0.2
            "border-width" "1px"
            "border-color" "#62A294"}}
   {:selector "node.firefighter"
    :style {"shape" "star"
            "background-color" "#F2BE56"
            "background-opacity" 0.2
            "border-width" "1px"
            "border-color" "#F2BE56"
            "height" "120px"
            "width" "120px"}}])

(def initial-elements
  [{:group "nodes"
    :data {:id "1" :label "Manager"}
    :position {:x 300 :y 130}
    :classes "manager"}
   {:group "nodes"
    :data {:id "2" :label "Exile"}
    :position {:x 200 :y 300}
    :classes "exile"}
   {:group "nodes"
    :data {:id "3" :label "Firefighter"}
    :position {:x 100 :y 130}
    :classes "firefighter"}
   {:group "edges"
    :data {:id "e1-2" :source "1" :target "2"}}
   {:group "edges"
    :data {:id "e3-2" :source "3" :target "2"}}])

(defn create-controls []
  (let [controls (js/document.createElement "div")]
    (set! (.-className controls) "controls")
    (set! (.-innerHTML controls)
          "<button>+</button><button>-</button><button>Fit</button>")
    controls))

(defn create-toolbar []
  (let [toolbar (js/document.createElement "div")]
    (set! (.-className toolbar) "toolbar")
    (set! (.-innerHTML toolbar)
          (str "<span>Add part: </span>"
               (apply str
                      (for [[type {:keys [label]}] part-types]
                        (str "<button data-type='" (name type) "'>"
                             label
                             "</button>")))))
    toolbar))

(defn create-logo []
  (let [logo (js/document.createElement "div")]
    (set! (.-className logo) "logo")
    (set! (.-innerHTML logo)
          "<img src='/images/parts-logo-horizontal.svg' width='150'>")
    logo))

(defn init []
  (let [root (js/document.getElementById "root")
        container (js/document.createElement "div")]
    ;; Setup container
    (set! (.-className container) "system-view")
    (set! (.-style.width container) "100vw")
    (set! (.-style.height container) "100vh")
    (.appendChild root container)

    ;; Add UI elements
    (.appendChild container (create-logo))
    (.appendChild container (create-toolbar))
    (.appendChild container (create-controls))

    ;; Initialize Cytoscape
    (let [cy-container (js/document.createElement "div")]
      (set! (.-style.width cy-container) "100%")
      (set! (.-style.height cy-container) "100%")
      (set! (.-style.position cy-container) "relative")
      (.appendChild container cy-container)

      (let [cy (cytoscape
                #js {:container cy-container
                     :elements (clj->js initial-elements)
                     :style (clj->js styles)
                     :layout #js {:name "preset"}
                     :minZoom 0.1
                     :maxZoom 10
                     :wheelSensitivity 0.1})]
        (js/console.log "Nodes:" (.nodes cy))
        (js/console.log "Styles:" (.style cy))
        cy))))
