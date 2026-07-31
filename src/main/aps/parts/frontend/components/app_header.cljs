(ns aps.parts.frontend.components.app-header
  "Shared header for the signed-in pages (Maps list, Account): the
   horizontal logo (linking home) on the left with a nav menu beside it,
   the auth menu on the right."
  (:require
   [aps.parts.frontend.components.toolbar.auth-status :refer [auth-status]]
   [aps.parts.frontend.router :as router]
   [re-frame.core :as rf]
   [uix.core :refer [$ defui]]
   [uix.re-frame :as uix.rf]))

(defui app-header []
  (let [route-name (uix.rf/use-subscribe [:router/route-name])]
    ($ :div {:class "flex items-center justify-between mb-6"}
       ($ :div {:class "flex items-center gap-2"}
          ($ :a {:href "/app"}
             ($ :img {:class "w-40" :src "/images/parts-logo-horizontal.svg"}))
          ($ :span {:class "badge badge-sm badge-soft"} "Beta")
          ($ :nav {:class "ml-4"}
             ($ :a {:class    (str "text-sm cursor-pointer hover:underline"
                                   (if (= ::router/maps-list route-name)
                                     " font-semibold"
                                     " font-normal"))
                    :on-click #(rf/dispatch [:router/navigate ::router/maps-list])}
                "All maps")))
       ($ auth-status))))
