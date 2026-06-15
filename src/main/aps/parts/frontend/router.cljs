(ns aps.parts.frontend.router
  "Client-side routing for the /app SPA.

   Uses reitit.frontend with HTML5 history (real URLs, no hash). The
   current match is pushed into re-frame under :router/match so views can
   subscribe to it; navigation is done by dispatching :router/navigate or
   calling `rfe/href` for plain anchors.

   All routes live under the /app path prefix — the server renders the
   same shell for every /app* URL, and this router decides what to show."
  (:require
   [re-frame.core :as rf]
   [reitit.frontend :as rf-routing]
   [reitit.frontend.easy :as rfe]))

(def routes
  "Route table for the SPA. Names are keywords used by `rfe/href` and
   programmatic navigation. The maps list is the app home at /app; an
   individual map's canvas is at /app/maps/:id; the account page is at
   /app/account; /app/login and /app/signup are the auth screens. /app,
   /app/account and /app/maps/:id are protected — the SPA root gates them
   in place when the user isn't authenticated."
  [["/app"
    {:name ::maps-list}]
   ["/app/login"
    {:name ::login}]
   ["/app/signup"
    {:name ::signup}]
   ["/app/account"
    {:name ::account}]
   ["/app/maps/:id"
    {:name ::map}]])

(def router
  (rf-routing/router routes))

(rf/reg-event-db
 :router/match
 (fn [db [_ match]]
   (assoc db :router/match match)))

(rf/reg-sub
 :router/match
 (fn [db _]
   (:router/match db)))

(rf/reg-sub
 :router/route-name
 :<- [:router/match]
 (fn [match _]
   (get-in match [:data :name])))

(rf/reg-sub
 :router/path-params
 :<- [:router/match]
 (fn [match _]
   (:path-params match)))

(rf/reg-fx
 :router/navigate
 (fn [{:keys [name path-params]}]
   (rfe/push-state name (or path-params {}))))

(rf/reg-event-fx
 :router/navigate
 (fn [_ [_ name path-params]]
   {:router/navigate {:name name :path-params path-params}}))

(defn- on-navigate
  "Called by reitit on every route change. A matched route stores its
   match for views to subscribe to. Two cases redirect instead:
   - an unmatched /app/* URL falls back to the maps list at /app;
   - /app/signup before launch falls back to /app/login — signup is
     invite-only (via /invite/:token) until the app has launched.
   Both use `replace-state` so the bounced URL leaves no history entry."
  [match _history]
  (cond
    (nil? match)
    (rfe/replace-state ::maps-list)

    (and (= ::signup (get-in match [:data :name]))
         (not @(rf/subscribe [:launched])))
    (rfe/replace-state ::login)

    :else
    (rf/dispatch [:router/match match])))

(defn start!
  "Begin listening to URL changes and dispatch the initial match."
  []
  (rfe/start! router on-navigate {:use-fragment false}))
