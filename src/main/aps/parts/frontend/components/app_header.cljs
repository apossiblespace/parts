(ns aps.parts.frontend.components.app-header
  "Shared header for the signed-in pages (Maps list, Account): logo,
   nav links, and the account menu — folded into a single hamburger
   dropdown on phones.

   The phone/desktop split uses `device/phone-primary?`, not a CSS
   breakpoint: the pages gate capability on that predicate (phones
   view, never edit — TASK-105) and the chrome must agree with it. A
   width breakpoint would diverge on landscape phones (wide viewport,
   still view-only) and narrow desktop windows (the reverse)."
  (:require
   ["lucide-react" :refer [Menu]]
   [aps.parts.frontend.components.avatar :refer [avatar-initial]]
   [aps.parts.frontend.components.dropdown :refer [close-dropdown!]]
   [aps.parts.frontend.components.toolbar.auth-status :refer [account-menu-items auth-status]]
   [aps.parts.frontend.device :as device]
   [aps.parts.frontend.router :as router]
   [re-frame.core :as rf]
   [uix.core :refer [$ defui]]
   [uix.re-frame :as uix.rf]))

(def ^:private nav-links
  "The signed-in pages, in nav order — the one list both the desktop
   nav and the phone menu render."
  [["Your Maps" ::router/maps-list]
   ["Account" ::router/account]])

(defui ^:private nav-item
  "One nav destination: static bold text when it is the `current` route,
   else a link dispatching the route. `on-select` (optional) runs first —
   the phone menu passes `close-dropdown!`. `class` appends styling; the
   phone menu passes none, its items are styled by the daisyUI menu."
  [{:keys [label route-name current on-select class]}]
  (if (= current route-name)
    ($ :span {:class (str "font-bold" (when class (str " " class)))} label)
    ($ :a {:class    (str "cursor-pointer hover:underline font-normal"
                          (when class (str " " class)))
           :on-click (fn []
                       (when on-select (on-select))
                       (rf/dispatch [:router/navigate route-name]))}
       label)))

(defui ^:private mobile-menu
  "The phone header's hamburger dropdown."
  [{:keys [current]}]
  (let [user         (uix.rf/use-subscribe [:auth/user])
        loading      (uix.rf/use-subscribe [:auth/loading])
        display-name (:display_name user)]
    (if loading
      ($ :span {:class "loading loading-spinner loading-sm"})
      ($ :div {:class "dropdown dropdown-end"}
         ($ :button {:tabIndex   0
                     :class      "btn btn-sm btn-ghost btn-square"
                     :aria-label "Menu"}
            ($ Menu {:size 20}))
         ($ :ul {:tabIndex 0
                 :class    "dropdown-content menu menu-sm z-10 mt-1 w-56"}
            (when user
              ($ :<>
                 ;; menu-sm sizes items at text-xs but excludes `.menu-title`
                 ;; rows, so the match is explicit here; min-w-0 lets a long
                 ;; name truncate inside the fixed-width menu.
                 ($ :li {:class "menu-title"}
                    ($ :div {:class "flex items-center gap-2 min-w-0"}
                       ($ avatar-initial {:display-name display-name :size :sm})
                       ($ :span {:class "text-xs font-normal text-base-content truncate"}
                          display-name)))
                 ($ :li ($ :hr))
                 (for [[label route-name] nav-links]
                   ($ :li {:key label}
                      ($ nav-item {:label      label
                                   :route-name route-name
                                   :current    current
                                   :on-select  close-dropdown!})))
                 ($ :li ($ :hr))))
            ($ account-menu-items {:user user}))))))

(defui app-header []
  (let [current (uix.rf/use-subscribe [:router/route-name])]
    ;; flex-wrap: safety net for narrow desktop windows — the phone
    ;; header is a single hamburger row and stays narrow by design.
    ($ :div {:class "flex flex-wrap items-center justify-between gap-y-2 mb-6"}
       ($ :div {:class "flex items-center gap-2"}
          ;; shrink-0: without it the flex row crushes the link (and with it
          ;; the logo image) to zero width when space runs out.
          ($ :a {:href "/app" :class "shrink-0"}
             ($ :img {:class "w-32 sm:w-40" :src "/images/parts-logo-horizontal.svg"}))
          ($ :span
             {:class "badge badge-sm badge-soft"}
             "Beta")
          (when-not device/phone-primary?
            ($ :nav {:class "ml-6 flex items-center gap-6"}
               (for [[label route-name] nav-links]
                 ($ nav-item {:key        label
                              :label      label
                              :route-name route-name
                              :current    current
                              :class      "text-sm whitespace-nowrap"})))))
       (if device/phone-primary?
         ($ mobile-menu {:current current})
         ($ auth-status)))))
