(ns parts.frontend.components.toolbar.sidebar
  (:require
   [parts.frontend.components.toolbar.auth-status :refer [auth-status]]
   [parts.frontend.components.toolbar.relationships-tools :refer [relationships-tools]]
   [parts.frontend.components.toolbar.parts-tools :refer [parts-tools]]
   [uix.core :refer [$ defui]]
   [uix.re-frame :as uix.rf]))

(defui sidebar
  "Display the main sidebar"
  []
  (let [demo-mode (uix.rf/use-subscribe [:demo-mode])]
    ($ :div {:class "sidebar max-h-[calc(100vh-200px)] flex flex-col rounded-sm border-base-300 border bg-white shadow-sm"}
       (when-not demo-mode ($ auth-status))
       ($ :div {:class "overflow-auto"}
          ($ parts-tools)
          ($ relationships-tools)))))
