(ns parts.frontend.components.toolbar.header
  (:require
   [uix.core :refer [$ defui]]))

(defui header
  "Renders a header for a tool collection to be shown in the sidebar"
  [{:keys [title count]}]
  ($ :header {:class "p-2 bg-base-300 text-xs font-bold flex justify-between"}
     ($ :h3 title)
     (when count
       ($ :div {:class "badge badge-xs"} count))))
