(ns aps.parts.frontend.components.banner
  "Page-level notice with a severity variant — the one home for the alert
   markup and its Lucide icon, so pages never hand-roll alert SVGs.

   Deliberately muted: soft variant-tinted background and border, black
   text on every variant (the tint and icon carry the severity), and a
   text-line-sized icon aligned to the first line of the message."
  (:require
   ["lucide-react" :refer [CircleAlert CircleCheck Info TriangleAlert]]
   [uix.core :refer [$ defui]]))

(def ^:private variants
  "Severity → soft background/border tint, icon colour, and Lucide icon.
   Text stays black; only the tint and icon signal the severity."
  {:info    {:tint "bg-sky-50 border-sky-200" :icon-tint "text-sky-600" :icon Info}
   :success {:tint "bg-green-50 border-green-200" :icon-tint "text-green-600" :icon CircleCheck}
   :warning {:tint "bg-amber-50 border-amber-200" :icon-tint "text-amber-600" :icon TriangleAlert}
   :alert   {:tint "bg-red-50 border-red-200" :icon-tint "text-red-600" :icon CircleAlert}})

(defui banner
  "A page notice. `:variant` is `:info` (the default), `:success`,
   `:warning`, or `:alert`; `:class` appends layout classes (`mb-4`, …);
   children render after the icon.

   The icon rides in a wrapper exactly one line-height tall (`h-[1lh]`),
   centred within it, and is sized in em — so it sits centred on the
   first text line and matches the text at any font size, with no magic
   pixel offsets. `p-3`/`gap-2` keep the banner compact against daisyUI's
   roomier alert defaults."
  [{:keys [variant class children]}]
  (let [{:keys [tint icon-tint icon]} (get variants variant (:info variants))]
    ($ :div {:role  "alert"
             :class (str "alert items-start gap-1.5 p-2 text-gray-900 " tint
                         (when class (str " " class)))}
       ($ :span {:class "flex h-[1lh] shrink-0 items-center"}
          ($ icon {:class (str "h-[1em] w-[1em] " icon-tint)}))
       children)))
