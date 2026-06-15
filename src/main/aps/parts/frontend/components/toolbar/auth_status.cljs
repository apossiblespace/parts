(ns aps.parts.frontend.components.toolbar.auth-status
  (:require
   ["lucide-react" :refer [ChevronDown LogOut User]]
   [aps.parts.frontend.router :as router]
   [clojure.string :as str]
   [re-frame.core :as rf]
   [uix.core :refer [$ defui]]
   [uix.re-frame :as uix.rf]))

(defui auth-status
  "Auth-status menu for the Maps list header. When signed in, the trigger
   shows an avatar placeholder with the user's initial plus their name; the
   daisyUI dropdown reveals the Log out action (or Log in, signed out). Render
   this in a page header — it positions its menu with `dropdown-end` so the
   menu doesn't overflow when the trigger sits at the right edge."
  []
  (let [user         (uix.rf/use-subscribe [:auth/user])
        loading      (uix.rf/use-subscribe [:auth/loading])
        display-name (:display_name user)
        initial      (some-> display-name not-empty (subs 0 1) str/upper-case)]
    ($ :div {:class "dropdown dropdown-end ml-4"}
       ;; Plain flex row, not a button. The hover affordance lives only on the
       ;; caret, but `group` makes it light up when hovering anywhere on the
       ;; row (see `group-hover` on the caret). The whole row is the dropdown
       ;; trigger (focusable tabIndex/role), so a click anywhere opens the menu.
       ($ :div {:tabIndex   0
                :role       "button"
                :class      "group flex items-center gap-2 cursor-pointer"
                :aria-label (if user
                              (str "Account menu for " display-name)
                              "Account menu")}
          (cond
            loading
            ($ :span {:class "loading loading-spinner loading-sm"})

            user
            ($ :<>
               ($ :div {:class "avatar avatar-placeholder"}
                  ($ :div {:class "bg-neutral text-neutral-content w-6 rounded-full"}
                     ($ :span {:class "text-xs"} initial)))
               ($ :span {:class "text-sm font-normal"} display-name))

            :else
            ($ :<>
               ($ :span {:class      "status status-error"
                         :aria-label "Status: logged-out"})
               ($ :span {:class "text-sm font-normal"} "Signed out")))
          (when-not loading
            ($ :span {:class "rounded p-0.5 group-hover:bg-base-200"}
               ($ ChevronDown {:size 16}))))
       (when-not loading
         ($ :ul {:tabIndex 0
                 :class    "dropdown-content menu menu-sm z-10 mt-1 w-40"}
            (if user
              ($ :<>
                 ($ :li
                    ($ :a {:on-click #(rf/dispatch [:router/navigate ::router/account])}
                       ($ User {:size 16})
                       "Account"))
                 ($ :li ($ :hr {:class "my-1 -mx-2 border-base-300"}))
                 ($ :li
                    ($ :a {:on-click #(rf/dispatch [:auth/logout])}
                       ($ LogOut {:size 16})
                       "Log out")))
              ($ :li
                 ($ :a {:href "/app"} "Log in"))))))))
