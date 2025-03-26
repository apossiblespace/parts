(ns parts.frontend.components.toolbar.sidebar
  (:require
   [parts.frontend.components.toolbar.auth-status :refer [auth-status]]
   [parts.frontend.components.toolbar.edges :refer [edges-tools]]
   [parts.frontend.components.toolbar.nodes :refer [node-tools]]
   [uix.core :refer [$ defui]]))

(defui sidebar
  "Display the main sidebar"
  [{:keys [selected-nodes selected-edges]}]
  ($ :div {:class "sidebar max-h-[calc(100vh-200px)] flex flex-col rounded-sm border-base-300 border bg-white shadow-sm"}
     ($ auth-status)
     ($ :div {:class "overflow-auto"}
        ($ node-tools {:selected-nodes selected-nodes})
        ($ edges-tools {:selected-edges selected-edges}))))
