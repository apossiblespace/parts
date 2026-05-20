(ns aps.parts.frontend.components.toolbar.sidebar
  (:require
   [aps.parts.common.observe :as o]
   [aps.parts.frontend.components.toolbar.auth-status :refer [auth-status]]
   [aps.parts.frontend.components.toolbar.parts-tools :refer [parts-tools]]
   [aps.parts.frontend.components.toolbar.relationships-tools :refer [relationships-tools]]
   [aps.parts.frontend.components.waitlist-modal :refer [waitlist-modal]]
   [uix.core :refer [$ defui use-state]]
   [uix.re-frame :as uix.rf]))

(defui sidebar
  "Display the main sidebar"
  []
  (let [demo                                          (uix.rf/use-subscribe [:demo])
        minimal                                       (uix.rf/use-subscribe [:minimal-demo])
        launched                                      (uix.rf/use-subscribe [:launched])
        selected-parts                                (uix.rf/use-subscribe [:map/selected-parts])
        selected-rels                                 (uix.rf/use-subscribe [:map/selected-relationships])
        has-auth                                      (or (not demo) (and demo (not minimal)))
        has-selection                                 (or (seq selected-parts) (seq selected-rels))
        [show-waitlist-modal set-show-waitlist-modal] (use-state false)]
    (when (or has-auth has-selection)
      ($ :div {:class "sidebar max-h-[calc(100vh-200px)] flex flex-col rounded-sm border-base-300 border bg-white shadow-sm"}
         (if demo
           (when-not minimal
             ($ :div {:class "p-2 space-y-2"}
                (if launched
                  ($ :a
                     {:class    "btn btn-sm btn-primary w-full"
                      :href     "/app"
                      :on-click #(o/track "Create Account Click" {:source "playground"})}
                     "Create an account")
                  ($ :button
                     {:class    "btn btn-sm btn-primary w-full"
                      :on-click #(do
                                   (o/track "Waitlist Modal Open" {:source "playground"})
                                   (set-show-waitlist-modal true))}
                     "Sign up"))
                (when launched
                  ($ :a
                     {:class    "btn btn-sm btn-ghost w-full"
                      :href     "/app"
                      :on-click #(o/track "Login Click" {:source "playground"})}
                     "Log in"))))
           ($ auth-status))
         ($ :div {:class "overflow-auto"}
            ($ parts-tools)
            ($ relationships-tools))
         ($ waitlist-modal
            {:show     show-waitlist-modal
             :on-close #(do
                          (o/track "Waitlist Modal Close" {:source "playground"})
                          (set-show-waitlist-modal false))})))))
