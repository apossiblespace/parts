(ns aps.parts.frontend.components.map-list-modal
  (:require
   [aps.parts.common.observe :as o]
   [aps.parts.frontend.components.modal :refer [modal]]
   [re-frame.core :as rf]
   [uix.core :refer [$ defui]]
   [uix.re-frame :as uix.rf]))

(defui map-list-modal [{:keys [show on-close]}]
  (o/debug "map-list-modal" "Loading map list modal")
  (let [maps          (uix.rf/use-subscribe [:maps/list])
        loading       (uix.rf/use-subscribe [:maps/loading])

        handle-create (fn []
                        (rf/dispatch [:map/create])
                        (on-close))

        handle-select (fn [the-map]
                        (rf/dispatch [:map/load (:id the-map)])
                        (on-close))]
    ($ modal
       {:show     show
        :on-close on-close
        :title    "Your maps"}

       ($ :div {:class "maps-list"}
          (if loading
            ($ :div {:class "loading-spinner"} "Loading...")
            (if (empty? maps)
              ($ :p "No maps yet")
              ($ :ul {:class "menu w-full"}
                 (for [the-map maps]
                   ($ :li {:key (:id the-map)}
                      ($ :a {:on-click #(handle-select the-map)}
                         (:title the-map)))))))

          ($ :div {:class "modal-action"}
             ($ :button
                {:class    "btn btn-primary"
                 :on-click handle-create}
                "Create a new Map"))))))
