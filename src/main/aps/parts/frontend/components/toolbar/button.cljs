(ns aps.parts.frontend.components.toolbar.button
  (:require
   [uix.core :refer [$ defui]]))

(defui button
  [{:keys [label on-click tooltip active?]}]
  (let [classes (str "btn btn-sm join-item "
                     (if active? "bg-base-300" "bg-white"))
        button  ($ :button {:class    classes
                            :on-click on-click}
                   label)]
    (if tooltip
      ($ :div {:class "tooltip tooltip-bottom" :data-tip tooltip}
         button)
      button)))
