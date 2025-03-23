(ns parts.frontend.components.toolbar.sidebar
  (:require
   ["@xyflow/react" :refer [MiniMap]]
   [parts.frontend.components.toolbar.auth-status :refer [auth-status]]
   [parts.frontend.components.toolbar.edges :refer [edges-tools]]
   [parts.frontend.components.toolbar.nodes :refer [node-tools]]
   [uix.core :refer [$ defui]]))

(defui sidebar
  "Display the main sidebar"
  [{:keys [selected-nodes selected-edges]}]
  ($ :div {:class "sidebar h-full flex flex-col"}
     ($ :div {:class "scrollable-area flex-grow"}
        ($ auth-status)
        ($ node-tools {:selected-nodes selected-nodes})
        ($ edges-tools {:selected-edges selected-edges}))
     ($ :div {:class "fixed-bottom"}
        ($ MiniMap {:className "parts-minimap"
                    :ariaLabel "Minimap"
                    :offsetScale 5}))))
