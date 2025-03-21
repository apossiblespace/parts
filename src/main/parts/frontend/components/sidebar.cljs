(ns parts.frontend.components.sidebar
  (:require
   [parts.frontend.components.toolbar.auth-status :refer [auth-status]]
   [parts.frontend.components.toolbar.edges :refer [edges-tools]]
   [parts.frontend.components.toolbar.nodes :refer [node-tools]]
   [uix.core :refer [$ defui]]))

(defui sidebar
  "Display the main sidebar"
  [{:keys [selected-nodes selected-edges]}]
  ($ :div {:class "sidebar"}
     ($ auth-status)
     ($ node-tools {:selected-nodes selected-nodes})
     ($ edges-tools {:selected-edges selected-edges})))
