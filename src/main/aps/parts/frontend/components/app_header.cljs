(ns aps.parts.frontend.components.app-header
  "Shared header for the signed-in pages (Maps list, Account): the
   horizontal logo (linking home) on the left, the auth menu on the right."
  (:require
   [aps.parts.frontend.components.toolbar.auth-status :refer [auth-status]]
   [uix.core :refer [$ defui]]))

(defui app-header []
  ($ :div {:class "flex items-center justify-between mb-6"}
     ($ :a {:href "/app"}
        ($ :img {:class "w-40" :src "/images/parts-logo-horizontal.svg"}))
     ($ auth-status)))
