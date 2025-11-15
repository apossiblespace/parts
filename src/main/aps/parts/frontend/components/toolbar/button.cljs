(ns aps.parts.frontend.components.toolbar.button
  (:require
   [uix.core :refer [$ defui]]))

(defui button
  [{:keys [label on-click tooltip]}]
  (let [button ($ :button {:class    "btn bg-white btn-sm join-item"
                           :on-click on-click}
                  label)]
    (if tooltip
      ($ :div {:class "tooltip tooltip-bottom" :data-tip tooltip}
         button)
      button)))
