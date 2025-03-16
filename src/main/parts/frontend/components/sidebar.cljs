(ns parts.frontend.components.sidebar
  (:require
   [uix.core :refer [defui $]]
   [parts.frontend.components.auth-status :refer [auth-status]]))

(defui sidebar
  "Display the main sidebar"
  []
  ($ :div {:class "sidebar p-4"}
     ($ auth-status)))
