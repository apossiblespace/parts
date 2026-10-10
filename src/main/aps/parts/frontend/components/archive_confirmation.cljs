(ns aps.parts.frontend.components.archive-confirmation
  "The confirmation before archiving a Map."
  (:require
   [aps.parts.frontend.components.confirmation-modal :refer [confirmation-modal]]
   [uix.core :refer [$ defui]]))

(defui archive-confirmation
  "The confirmation before archiving `the-map` (nil when closed). Used by
   the Maps list and the map menu. Unarchiving needs no confirmation."
  [{:keys [the-map on-confirm on-close]}]
  ($ confirmation-modal
     {:show          (some? the-map)
      :title         (str "Archive “" (:title the-map) "”?")
      :body          ($ :<>
                        "The Map moves to Archived and becomes read-only."
                        ($ :br)
                        "You can unarchive it at any time.")
      :confirm-label "Archive"
      :on-confirm    on-confirm
      :on-close      on-close}))
