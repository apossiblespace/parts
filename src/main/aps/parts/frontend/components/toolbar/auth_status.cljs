(ns aps.parts.frontend.components.toolbar.auth-status
  (:require
   [re-frame.core :as rf]
   [uix.core :refer [$ defui]]
   [uix.re-frame :as uix.rf]))

(defui auth-status
  "Renders a logged in/out status, with a log out button or a link into
   the /app login screen."
  []
  (let [user    (uix.rf/use-subscribe [:auth/user])
        loading (uix.rf/use-subscribe [:auth/loading])]
    ($ :section {:class "tools p-2 auth-status border-b-1 border-base-300"}
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
            ($ :a {:class "btn btn-xs ml-1" :href "/app"}
               "Log in"))))))
