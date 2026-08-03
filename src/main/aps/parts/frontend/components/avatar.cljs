(ns aps.parts.frontend.components.avatar
  "Round avatar placeholder showing a display name's initial. The pure
   string logic lives in avatar-view so the cljs suite can test it."
  (:require
   [aps.parts.frontend.components.avatar-view :refer [initial-of]]
   [uix.core :refer [$ defui]]))

(def ^:private sizes
  "Size variants, banner-style: one place pairs the box with a
   proportionate initial."
  {:md {:box  "bg-neutral text-neutral-content rounded-full w-6"
        :text "text-xs"}
   :sm {:box  "bg-neutral text-neutral-content rounded-full w-5"
        :text "text-[0.625rem]"}})

(defui avatar-initial
  "Round avatar placeholder showing the display name's first character.
   `:size` is `:md` (the default, header-trigger size) or `:sm` (the
   phone menu's item size). shrink-0 so a tight flex row truncates the
   name beside it, never the avatar."
  [{:keys [display-name size]}]
  (let [{:keys [box text]} (get sizes size (:md sizes))]
    ($ :div {:class "avatar avatar-placeholder shrink-0"}
       ($ :div {:class box}
          ($ :span {:class text} (initial-of display-name))))))
