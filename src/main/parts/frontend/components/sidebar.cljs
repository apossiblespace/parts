(ns parts.frontend.components.sidebar
  (:require
   [parts.frontend.components.auth-status :refer [auth-status]]
   [parts.frontend.components.node-form :refer [node-form]]
   [uix.core :refer [$ defui]]))

(defui sidebar
  "Display the main sidebar"
  [{:keys [selected-nodes selected-edges]}]
  (println "Sidebar selected nodes:" selected-nodes)
  (println "Sidebar selected edges:" selected-edges)
  ($ :div {:class "sidebar p-4"}
     ($ auth-status)
     ($ :div {:class "divider"})
     (when (seq selected-nodes)
       ($ :div {:class "selected-nodes"}
          (map
           (fn [node]
             ($ node-form {:key (:id node)
                           :node node
                           :on-save nil
                           :on-cancel nil}))
           selected-nodes)))
     (when (seq selected-edges)
       ($ :div {:class "selected-edges"}
          "Selected Edges"))))
