(ns main.parts.frontend.observe)

(defn track
  "track an event NAME in Plausible, with PROPS"
  [name props]
  (when (js/window.plausible)
    (js/window.plausible name
                         (clj->js {:props props}))))
