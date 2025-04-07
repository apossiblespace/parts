(ns parts.frontend.components.toolbar.auth-status
  (:require
   [parts.frontend.components.login-modal :refer [login-modal]]
   [uix.core :refer [$ defui use-state]]
   [uix.re-frame :as uix.rf]
   [re-frame.core :as rf]))

(defui auth-status
  "Renders a logged/in out status, and a button to login and log out"
  []
  (let [[show-login-modal set-show-login-modal] (use-state false)
        user (uix.rf/use-subscribe [:auth/user])
        loading (uix.rf/use-subscribe [:auth/loading])]
    ($ :section {:class "tools p-2 auth-status border-b-1 border-base-300"}
       ($ login-modal
          {:show show-login-modal
           :on-close #(set-show-login-modal false)})
       (when loading
         ($ :span {:class "loading loading-spinner loading-sm"}))
       (if user
         ($ :div {:class "flex justify-between"}
            ($ :div
               ($ :span {:class "status status-success mr-1" :aria-label "Status: logged-in"})
               ($ :span {:class "text-sm"} (:username user)))
            ($ :button {:class "btn btn-xs ml-1" :on-click #(rf/dispatch [:auth/logout])}
               "Log out"))
         ($ :div {:class "flex justify-between"}
            ($ :div
               ($ :span {:class "status status-error mr-1" :aria-label "Status: logged-out"})
               ($ :span "Signed out"))
            ($ :button {:class "btn btn-xs ml-1" :on-click #(set-show-login-modal true)}
               "Log in"))))))
