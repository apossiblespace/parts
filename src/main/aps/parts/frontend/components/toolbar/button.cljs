(ns aps.parts.frontend.components.toolbar.button
  (:require
   [uix.core :refer [$ defui]]))

(defui button
  [{:keys [label icon on-click tooltip active? aria-label]}]
  (let [classes (str "btn btn-sm join-item "
                     (if active? "bg-base-300" "bg-white"))
        button  ($ :button {:class        classes
                            :on-click     on-click
                            :aria-label   aria-label
                            :aria-pressed (boolean active?)}
                   icon
                   label)]
    (if tooltip
      ($ :div {:class "tooltip tooltip-top" :data-tip tooltip}
         button)
      button)))
