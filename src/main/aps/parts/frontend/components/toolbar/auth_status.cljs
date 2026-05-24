(ns aps.parts.frontend.components.toolbar.auth-status
  (:require
   [re-frame.core :as rf]
   [uix.core :refer [$ defui]]
   [uix.re-frame :as uix.rf]))

(defui auth-status
  "Auth-status menu for the Maps list header. The trigger shows a
   coloured status dot plus the user's name; the daisyUI dropdown
   reveals the Log out action (or Log in, signed out). Render this
   in a page header — it positions its menu with `dropdown-end` so
   the menu doesn't overflow when the trigger sits at the right edge."
  []
  (let [user    (uix.rf/use-subscribe [:auth/user])
        loading (uix.rf/use-subscribe [:auth/loading])]
    ($ :div {:class "dropdown dropdown-end"}
       ($ :div {:tabIndex 0
                :role     "button"
                :class    "btn btn-sm btn-ghost"
                :aria-label (if user
                              (str "Account menu for " (:username user))
                              "Account menu")}
          (cond
            loading
            ($ :span {:class "loading loading-spinner loading-sm"})

            user
            ($ :<>
               ($ :span {:class      "status status-success"
                         :aria-label "Status: logged-in"})
               ($ :span {:class "text-sm font-normal"} (:username user)))

            :else
            ($ :<>
               ($ :span {:class      "status status-error"
                         :aria-label "Status: logged-out"})
               ($ :span {:class "text-sm font-normal"} "Signed out"))))
       (when-not loading
         ($ :ul {:tabIndex 0
                 :class    (str "dropdown-content menu menu-sm "
                                "bg-base-100 border border-base-300 "
                                "rounded-box shadow-sm "
                                "z-10 mt-1 w-40 p-2")}
            (if user
              ($ :li
                 ($ :a {:on-click #(rf/dispatch [:auth/logout])} "Log out"))
              ($ :li
                 ($ :a {:href "/app"} "Log in"))))))))
