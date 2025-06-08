(ns parts.frontend.components.system-list-modal
  (:require
   [parts.frontend.components.modal :refer [modal]]
   [parts.frontend.observe :as o]
   [re-frame.core :as rf]
   [uix.core :refer [$ defui]]
   [uix.re-frame :as uix.rf]))

(defui system-list-modal [{:keys [show on-close]}]
  (o/debug "system-list-modal" "Loading system list modal")
  (let [systems (uix.rf/use-subscribe [:systems/list])
        loading (uix.rf/use-subscribe [:systems/loading])

        handle-create (fn []
                        (rf/dispatch [:system/create])
                        (on-close))

        handle-select (fn [system]
                        (rf/dispatch [:system/load (:id system)])
                        (on-close))]
    ($ modal
       {:show show
        :on-close on-close
        :title "Your systems"}

       ($ :div {:class "systems-list"}
          (if loading
            ($ :div {:class "loading-spinner"} "Loading...")
            (if (empty? systems)
              ($ :p "No systems yet")
              ($ :ul {:class "menu w-full"}
                 (for [system systems]
                   ($ :li {:key (:id system)}
                      ($ :a {:on-click #(handle-select system)}
                         (:title system)))))))

          ($ :div {:class "modal-action"}
             ($ :button
                {:class "btn btn-primary"
                 :on-click handle-create}
                "Create a new System"))))))
