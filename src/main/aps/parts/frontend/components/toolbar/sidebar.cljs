(ns aps.parts.frontend.components.toolbar.sidebar
  (:require
   [aps.parts.common.observe :as o]
   [aps.parts.frontend.components.login-modal :refer [login-modal]]
   [aps.parts.frontend.components.signup-modal :refer [signup-modal]]
   [aps.parts.frontend.components.toolbar.auth-status :refer [auth-status]]
   [aps.parts.frontend.components.toolbar.parts-tools :refer [parts-tools]]
   [aps.parts.frontend.components.toolbar.relationships-tools :refer [relationships-tools]]
   [uix.core :refer [$ defui use-state]]
   [uix.re-frame :as uix.rf]))

(defui sidebar
  "Display the main sidebar"
  []
  (let [demo                                      (uix.rf/use-subscribe [:demo])
        minimal                                   (uix.rf/use-subscribe [:minimal-demo])
        [show-signup-modal set-show-signup-modal] (use-state false)
        [show-login-modal set-show-login-modal]   (use-state false)]
    ($ :div {:class "sidebar max-h-[calc(100vh-200px)] flex flex-col rounded-sm border-base-300 border bg-white shadow-sm"}
       (if demo
         (when-not minimal
           ($ :div {:class "p-2 space-y-2"}
              ($ :button
                 {:class    "btn btn-sm btn-primary w-full"
                  :on-click #(do
                               (o/track "Signup Modal Open" {:source "playground"})
                               (set-show-signup-modal true))}
                 "Create an account")
              ($ :button
                 {:class    "btn btn-sm btn-ghost w-full"
                  :on-click #(do
                               (o/track "Login Modal Open" {:source "playground"})
                               (set-show-login-modal true))}
                 "Log in")))
         ($ auth-status))
       ($ :div {:class "overflow-auto"}
          ($ parts-tools)
          ($ relationships-tools))
       ($ signup-modal
          {:show       show-signup-modal
           :on-close   #(do
                          (o/track "Signup Modal Close" {:source "playground"})
                          (set-show-signup-modal false))
           :on-success (fn [result]
                         ;; Redirect to the new system after successful signup
                         (when-let [system-id (get-in result [:body :system_id])]
                           (set! (.-href js/window.location)
                                 (str "/systems/" system-id))))})
       ($ login-modal
          {:show     show-login-modal
           :on-close #(do
                        (o/track "Login Modal Close" {:source "playground"})
                        (set-show-login-modal false))}))))
