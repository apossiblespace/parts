(ns aps.parts.frontend.components.dropdown
  "Shared helper for daisyUI focus-driven dropdowns.")

(defn close-dropdown!
  "Close the daisyUI dropdown the caller is inside by blurring the
   currently focused element. daisyUI dropdowns stay open until focus
   leaves; this is the cheap idiomatic way to close one after a menu
   item fires its action."
  []
  (some-> js/document .-activeElement .blur))
