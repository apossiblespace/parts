(ns aps.parts.frontend.components.toolbar.header
  (:require
   [uix.core :refer [$ defui]]))

(defui header
  "Renders a header for a tool collection to be shown in the sidebar.
   `:count` renders as a badge; `:right` as plain de-emphasised text —
   both on the right."
  [{:keys [title count right]}]
  ($ :header {:class "p-2 bg-base-300 text-xs font-bold flex justify-between"}
     ($ :h3 title)
     (when count
       ($ :div {:class "badge badge-xs"} count))
     (when right
       ($ :span {:class "font-normal text-base-content/60"} right))))
