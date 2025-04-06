(ns parts.frontend.components.toolbar.sidebar
  (:require
   [parts.frontend.components.toolbar.auth-status :refer [auth-status]]
   ;; [parts.frontend.components.toolbar.edges :refer [edges-tools]]
   [parts.frontend.components.toolbar.parts-tools :refer [parts-tools]]
   [uix.core :refer [$ defui]]))

(defui sidebar
  "Display the main sidebar"
  []
  ($ :div {:class "sidebar max-h-[calc(100vh-200px)] flex flex-col rounded-sm border-base-300 border bg-white shadow-sm"}
     ($ auth-status)
     ($ :div {:class "overflow-auto"}
        ($ parts-tools))))
        ;;; ($ edges-tools))))
