(ns aps.parts.frontend.components.time-travel-bar
  "Time-travel mode's navigation chrome (TASK-073.03): one-click ◀ ▶
   stepping between Sessions and the explicit exit back to Editing mode.
   Rendered top-center while the mode is active; its presence — together
   with the reduced toolbar — is the mode's read-only signal."
  (:require
   ["lucide-react" :refer [ChevronLeft ChevronRight]]
   [aps.parts.common.observe :as o]
   [aps.parts.frontend.dates :as dates]
   [re-frame.core :as rf]
   [uix.core :refer [$ defui]]
   [uix.re-frame :as uix.rf]))

(def ^:private bar-date-format
  (js/Intl.DateTimeFormat. js/undefined #js {:day "numeric" :month "short"}))

(defui time-travel-bar
  []
  (let [viewing                               (uix.rf/use-subscribe [:time-travel/viewing])
        loading?                              (uix.rf/use-subscribe [:time-travel/loading?])
        error                                 (uix.rf/use-subscribe [:time-travel/error])
        {:keys [session count latest? index]} viewing
        date                                  (some->> (:anchor_valid_at session)
                                                       (dates/->js-date)
                                                       (.format bar-date-format))
        label                                 (str "Session " (:ordinal session) " of " count
                                                   (when (seq (:trigger session))
                                                     (str " — " (:trigger session)))
                                                   (when date (str " · " date)))]
    (when viewing
      ($ :div {:class (str "flex items-center gap-1 px-2 py-1 "
                           "rounded-box border border-base-300 "
                           "bg-white shadow-xs")}
         ($ :button {:class      "btn btn-sm btn-square btn-ghost"
                     :disabled   (= 1 index)
                     :aria-label "Previous session"
                     :on-click   #(rf/dispatch [:time-travel/step :back])}
            ($ ChevronLeft {:size 16}))
         ($ :span {:class "text-sm px-1 whitespace-nowrap"} label)
         ($ :button {:class      "btn btn-sm btn-square btn-ghost"
                     :disabled   (boolean latest?)
                     :aria-label "Next session"
                     :on-click   #(rf/dispatch [:time-travel/step :forward])}
            ($ ChevronRight {:size 16}))
         (when loading?
           ($ :span {:class "loading loading-spinner loading-xs mx-1"}))
         (when error
           ($ :span {:class "text-error text-xs mx-1" :role "alert"} error))
         ($ :button {:class    "btn btn-sm btn-primary ml-1"
                     :on-click (fn []
                                 (o/track "Time travel exited" {})
                                 (rf/dispatch [:time-travel/exit]))}
            "Back to editing")))))
