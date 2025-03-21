(ns parts.frontend.components.toolbar.edges
  (:require
   [uix.core :refer [$ defui]]))

(defui edges-tools
  "Renders a tool palette that displays the selected edges for editing"
  [{:keys [selected-edges]}]
  (println "Sidebar selected edges:" selected-edges)
  (when (seq selected-edges)
    ($ :div {:class "selected-edges p-2"}
       "Selected Edges")))
