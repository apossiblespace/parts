(ns aps.parts.frontend.components.toolbar.sidebar
  (:require
   [aps.parts.frontend.components.toolbar.auth-status :refer [auth-status]]
   [aps.parts.frontend.components.toolbar.parts-tools :refer [parts-tools]]
   [aps.parts.frontend.components.toolbar.relationships-tools :refer [relationships-tools]]
   [aps.parts.frontend.components.waitlist-modal :refer [waitlist-modal]]
   [aps.parts.frontend.observe :as o]
   [uix.core :refer [$ defui use-state]]
   [uix.re-frame :as uix.rf]))

(defui sidebar
  "Display the main sidebar"
  []
  (let [demo (uix.rf/use-subscribe [:demo])
        minimal (uix.rf/use-subscribe [:minimal-demo])
        [show-waitlist-modal set-show-waitlist-modal] (use-state false)]
    ($ :div {:class "sidebar max-h-[calc(100vh-200px)] flex flex-col rounded-sm border-base-300 border bg-white shadow-sm"}
       (if demo
         (when-not minimal
           ($ :div {:class "p-2"}
              ($ :button
                 {:class "btn btn-sm btn-primary w-full"
                  :on-click #(do
                               (o/track "Signup Modal Open" {:source "playground"})
                               (set-show-waitlist-modal true))}
                 "Sign up")))
         ($ auth-status))
       ($ :div {:class "overflow-auto"}
          ($ parts-tools)
          ($ relationships-tools))
       ($ waitlist-modal
          {:show show-waitlist-modal
           :on-close #(do
                        (o/track "Signup Modal Close" {:source "playground"})
                        (set-show-waitlist-modal false))}))))
