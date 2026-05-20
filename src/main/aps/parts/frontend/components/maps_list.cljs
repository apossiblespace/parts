(ns aps.parts.frontend.components.maps-list
  "Full-page Maps list route (/app/maps). Fetches the list on mount and
   lets the user open or create a Map. Selecting or creating a Map
   navigates the client-side router to /app/maps/:id."
  (:require
   [aps.parts.frontend.router :as router]
   [re-frame.core :as rf]
   [uix.core :refer [$ defui use-effect]]
   [uix.re-frame :as uix.rf]))

(defui maps-list []
  (let [maps          (uix.rf/use-subscribe [:maps/list])
        loading       (uix.rf/use-subscribe [:maps/loading])

        handle-create (fn []
                        (rf/dispatch [:map/create]))

        handle-select (fn [the-map]
                        (rf/dispatch [:router/navigate
                                      ::router/map
                                      {:id (:id the-map)}]))]

    ;; Fetch the list when the route mounts.
    (use-effect
     (fn []
       (rf/dispatch [:map/fetch-list])
       js/undefined)
     [])

    ($ :div {:class "min-h-screen bg-gray-50 p-4"}
       ($ :div {:class "max-w-2xl mx-auto"}
          ($ :div {:class "flex items-center justify-between my-6"}
             ($ :a {:href "/" :class "flex items-center"}
                ($ :img {:class "w-40" :src "/images/parts-logo-horizontal.svg"}))
             ($ :button
                {:class    "btn btn-sm btn-primary"
                 :on-click handle-create}
                "Create a new Map"))
          ($ :div {:class "card bg-white shadow-sm border border-base-300"}
             ($ :div {:class "card-body"}
                ($ :h1 {:class "text-lg font-bold mb-2"} "Your Maps")
                (if loading
                  ($ :div {:class "loading loading-spinner"})
                  (if (empty? maps)
                    ($ :p {:class "text-gray-500"} "No Maps yet")
                    ($ :ul {:class "menu w-full"}
                       (for [the-map maps]
                         ($ :li {:key (:id the-map)}
                            ($ :a {:on-click #(handle-select the-map)}
                               (:title the-map)))))))))))))
