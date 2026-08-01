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

(defn- nav-item [label route-name]
  (let [current-route-name (uix.rf/use-subscribe [:router/route-name])]
    (if (= current-route-name route-name)
      ($ :span {:class "text-sm font-bold"} label)
      ($ :a {:class    "text-sm cursor-pointer hover:underline font-normal"
             :on-click #(rf/dispatch [:router/navigate route-name])}
         label))))

(defui app-header []
  ($ :div {:class "flex items-center justify-between mb-6"}
     ($ :div {:class "flex items-center gap-2"}
        ($ :a {:href "/app"}
           ($ :img {:class "w-40" :src "/images/parts-logo-horizontal.svg"}))
        ($ :span
           {:class "badge badge-sm badge-soft"}
           "Beta")
        ($ :nav {:class "ml-6 flex item-center gap-6"}
           (nav-item "Your Maps" ::router/maps-list)
           (nav-item "Account" ::router/account)))
     ($ auth-status)))
