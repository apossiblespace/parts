(ns parts.frontend.graph
  (:require ["cytoscape" :as cytoscape]
            ["cytoscape-edgehandles" :as edgehandles]
            [parts.frontend.graph.styles :as s]
            [parts.common.part-types :refer [part-types]]))

(.use cytoscape edgehandles)

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

(defn add-node! [cy type]
  (let [id (str (random-uuid))  ;; Generate unique ID
        type-info (get part-types (keyword type))
        new-node {:group "nodes"
                  :data {:id id
                        :label (:label type-info)}
                  :position {:x 100 :y 100}  ;; Default position
                  :classes type}]
    (.add cy (clj->js [new-node]))))


(defn setup-toolbar! [toolbar cy]
  (let [buttons (.querySelectorAll toolbar "button")]
    (doseq [button (array-seq buttons)]
      (.addEventListener button "click"
                        (fn [e]
                          (let [type (.. e -target -dataset -type)]
                            (add-node! cy type)))))))

(defn create-logo []
  (let [logo (js/document.createElement "div")]
    (set! (.-className logo) "logo")
    (set! (.-innerHTML logo)
          "<img src='/images/parts-logo-horizontal.svg' width='150'>")
    logo))

(defn init [initial-data]
  (let [root (js/document.getElementById "root")
        container (js/document.createElement "div")]
    ;; Setup container
    (set! (.-className container) "system-view")
    (set! (.-style.width container) "100vw")
    (set! (.-style.height container) "100vh")
    (.appendChild root container)

    (let [toolbar (create-toolbar)]
      (.appendChild container toolbar)
      (.appendChild container (create-logo))
      (.appendChild container (create-controls))

      ;; Initialize Cytoscape
      (let [cy-container (js/document.createElement "div")]
        (set! (.-style.width cy-container) "100%")
        (set! (.-style.height cy-container) "100%")
        (set! (.-style.position cy-container) "relative")
        (.appendChild container cy-container)

        (let [cy (cytoscape
                  #js {:container cy-container
                       :elements (clj->js initial-data)
                       :style (clj->js s/styles)
                       :layout #js {:name "preset"}
                       :minZoom 0.1
                       :maxZoom 10
                       :wheelSensitivity 0.1})
              eh (.edgehandles cy #js {:snap true
                                       :snapThreshold 20
                                       :handleNodes "node"
                                       :handlePosition "middle top"})]

          (.enable eh)
          (setup-toolbar! toolbar cy)

          (js/console.log "Nodes:" (.nodes cy))
          (js/console.log "Styles:" (.style cy))
          (js/console.log "Edge handles initialized:" eh)
          cy)))))
