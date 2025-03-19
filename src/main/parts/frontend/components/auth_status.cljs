(ns parts.frontend.components.auth-status
  (:require
   [uix.core :refer [defui $ use-state]]
   [parts.frontend.context :as ctx]
   [parts.frontend.components.login-modal :refer [login-modal]]))

(defui auth-status
  "Displays a logged/in out status, and a button to login and log out"
  []
  (let [[show-login-modal set-show-login-modal] (use-state false)
        {:keys [user loading logout]} (ctx/use-auth)]
    ($ :section {:class "p-2 auth-status border-b border-b-1 border-base-300"}
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
            ($ :button {:class "btn btn-xs ml-1" :on-click (fn [] (logout))}
               "Log out"))
         ($ :div {:class "flex justify-between"}
            ($ :div
               ($ :span {:class "status status-error mr-1" :aria-label "Status: logged-out"})
               ($ :span "Signed out"))
            ($ :button {:class "btn btn-xs ml-1" :on-click #(set-show-login-modal true)}
               "Log in"))))))
