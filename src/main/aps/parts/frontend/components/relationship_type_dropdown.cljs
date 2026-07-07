(ns aps.parts.frontend.components.relationship-type-dropdown
  (:require
   ["lucide-react" :refer [Check]]
   [aps.parts.common.constants :as constants]
   [uix.core :refer [$ defui]]))

(defn close-dropdown!
  "Close the daisyUI dropdown the caller is inside by blurring the
   currently focused element. daisyUI dropdowns stay open until focus
   leaves; this is the cheap idiomatic way to close one after a menu
   item fires its action."
  []
  (some-> js/document .-activeElement .blur))

(defui relationship-type-dropdown
  "daisyUI dropdown listing the Relationship types — each a colour dot +
   name behind a fixed-width leading check column (macOS HIG: the state
   column leads) marking the selected type. The caller supplies the
   trigger's content as children; this component provides the focusable
   trigger wrapper and the menu. Used by the map toolbar (drop-up) and
   the sidebar relationship form (drop-down).

   Props:
   - selected: the selected type, a keyword
   - on-select: (fn [type]) — receives the keyword; the menu closes itself
   - dropdown-class: positioning classes for the dropdown root
     (e.g. \"dropdown-top\"); daisyUI tooltip classes may ride along —
     dropdown and tooltip coexist on one element
   - data-tip / on-mouse-leave: forwarded to the dropdown root, for that
     tooltip
   - trigger-class: classes for the trigger
   - trigger-aria-label: aria-label for the trigger"
  [{:keys [selected on-select dropdown-class data-tip on-mouse-leave
           trigger-class trigger-aria-label children]}]
  ($ :div {:class          (str "dropdown " dropdown-class)
           :data-tip       data-tip
           :on-mouse-leave on-mouse-leave}
     ($ :div {:tabIndex   0
              :role       "button"
              :class      trigger-class
              :aria-label trigger-aria-label}
        children)
     ($ :ul {:tabIndex 0
             :class    "dropdown-content menu menu-sm z-10 my-1 w-44"}
        (map (fn [type]
               (let [{:keys [label]} (constants/relationship-labels type)]
                 ($ :li {:key (name type)}
                    ($ :a {:class        "px-2"
                           :aria-current (when (= selected type) "true")
                           :on-click     (fn []
                                           (on-select type)
                                           (close-dropdown!))}
                       ;; Check + dot share one cell — daisyUI menu items
                       ;; are a grid whose *middle* column stretches, and
                       ;; that column must be the label. The px-2 / w-3.5 /
                       ;; gap-1.5 trio lands the menu dots on the same x as
                       ;; the toolbar trigger's dot.
                       ($ :span {:class "flex items-center gap-1.5"}
                          (if (= selected type)
                            ($ Check {:size 14 :className "w-3.5 shrink-0"})
                            ($ :span {:class "w-3.5 shrink-0"}))
                          ($ :span {:class "type-dot"
                                    :style {:background-color
                                            (constants/relationship-colors type)}}))
                       label))))
             constants/relationship-type-order))))
