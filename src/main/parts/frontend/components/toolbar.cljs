(ns parts.frontend.components.toolbar
  (:require
    [uix.core :refer [defui $]]))

(defui parts-toolbar [{:keys [children]}]
  ($ :div {:class "parts-toolbar"} children))

