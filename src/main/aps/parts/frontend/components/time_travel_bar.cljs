(ns aps.parts.frontend.components.time-travel-bar
  "Time-travel mode's navigation chrome (TASK-073.03): one-click ◀ ▶
   stepping between Sessions, styled as a standard button join like the
   toolbar and the map-name widget. Rendered top-center while the mode
   is active; its presence — together with the reduced toolbar — is the
   mode's read-only signal. Exit lives on the History toggle in the
   top-left row (T, or Escape)."
  (:require
   ["lucide-react" :refer [ChevronLeft ChevronRight]]
   [aps.parts.frontend.dates :as dates]
   [re-frame.core :as rf]
   [uix.core :refer [$ defui]]
   [uix.re-frame :as uix.rf]))

(defui time-travel-bar
  []
  (let [viewing                         (uix.rf/use-subscribe [:time-travel/viewing])
        error                           (uix.rf/use-subscribe [:time-travel/error])
        {:keys [session latest? index]} viewing
        date                            (dates/format-date
                                         dates/short-date-format
                                         (:anchor_valid_at session))]
    (when viewing
      ($ :div {:class "flex items-center gap-2"}
         ($ :div {:class "join shadow-xs"}
            ($ :button {:class      (str "btn btn-sm btn-square join-item "
                                         "bg-base-100 tooltip tooltip-bottom")
                        :data-tip   "Previous session — ←"
                        :disabled   (= 1 index)
                        :aria-label "Previous session"
                        :on-click   #(rf/dispatch [:time-travel/step :back])}
               ($ ChevronLeft {:size 16}))
            ;; Fixed width so the nav buttons don't shift as the label
            ;; changes length between sessions. No loading indicator:
            ;; the previous Session stays on screen until the snapshot
            ;; lands (see `state/time-travel`), so there is nothing to
            ;; spin about.
            ($ :div {:class (str "btn btn-sm join-item bg-base-100 "
                                 "pointer-events-none w-36")}
               (str "Session " (:ordinal session)
                    (when date (str " · " date))))
            ($ :button {:class      (str "btn btn-sm btn-square join-item "
                                         "bg-base-100 tooltip tooltip-bottom")
                        :data-tip   "Next session — →"
                        :disabled   (boolean latest?)
                        :aria-label "Next session"
                        :on-click   #(rf/dispatch [:time-travel/step :forward])}
               ($ ChevronRight {:size 16})))
         (when error
           ($ :span {:class "text-error text-xs" :role "alert"} error))))))
