(ns parts.frontend.components.modal
  (:require
   [uix.core :refer [defui $]]))

(defui modal [{:keys [show title on-close children]}]
  (when show
    ($ :div {:class "modal-overlay"}
       ($ :div {:class "modal-container"}
          ($ :div {:class "modal-header"}
             ($ :h3 title)
             ($ :button {:class "modal-close" :on-click on-close} "×"))
          ($ :div {:class "modal-content"}
             children)))))