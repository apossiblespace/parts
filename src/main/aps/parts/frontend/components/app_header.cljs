(ns aps.parts.frontend.components.app-header
  "Shared header for the signed-in pages (Maps list, Account): the
   horizontal logo on the left, the auth menu on the right. `actions`,
   when supplied, renders just before the auth menu — the Maps list
   passes its \"Create a new Map\" button there; the Account page passes
   nothing, so it gets the logo and the auth menu alone."
  (:require
   [aps.parts.frontend.components.toolbar.auth-status :refer [auth-status]]
   [uix.core :refer [$ defui]]))

(defui app-header [{:keys [actions]}]
  ($ :div {:class "flex items-center justify-between my-6"}
     ($ :a {:href "/app"}
        ($ :img {:class "w-40" :src "/images/parts-logo-horizontal.svg"}))
     ($ :div {:class "flex items-center gap-2"}
        actions
        ($ auth-status))))
