(ns aps.parts.frontend.components.toolbar.button
  (:require
   [uix.core :refer [$ defui]]))

(defui button
  ;; The tooltip classes ride on the <button> itself — a wrapper div
  ;; would become the join's child instead of the button, and its
  ;; inline-block line box is taller than the button (the strut below
  ;; the baseline), misaligning the whole toolbar row.
  [{:keys [label icon on-click tooltip active? aria-label disabled?]}]
  ($ :button {:class        (str "btn btn-sm join-item "
                                 (if active? "bg-base-300" "bg-white")
                                 (when tooltip " tooltip tooltip-top"))
              :data-tip     tooltip
              :on-click     on-click
              :aria-label   aria-label
              :aria-pressed (boolean active?)
              :disabled     (boolean disabled?)}
     icon
     label))
