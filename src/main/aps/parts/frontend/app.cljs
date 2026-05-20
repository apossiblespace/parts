(ns aps.parts.frontend.app
  (:require
   ["htmx.org" :default htmx]
   [aps.parts.common.observe :as o]
   [aps.parts.frontend.components.auth-screen :refer [auth-screen]]
   [aps.parts.frontend.components.map :refer [map-view]]
   [aps.parts.frontend.components.maps-list :refer [maps-list]]
   [aps.parts.frontend.router :as router]
   [aps.parts.frontend.state.fx]
   [aps.parts.frontend.state.handlers]
   [aps.parts.frontend.state.subs]
   [aps.parts.frontend.storage.registry :as storage-registry]
   [re-frame.core :as rf]
   [uix.core :refer [$ defui use-effect]]
   [uix.dom]
   [uix.re-frame :as uix.rf]))

(def initial-db
  {:demo-mode false
   :launched  false
   :map       {}
   :maps      {:list    []
               :loading false}})

(defui map-route
  "Canvas route (/app/maps/:id). Fetches the routed Map when the id
   changes, then renders the canvas."
  [{:keys [map-id]}]
  (let [loaded-id (uix.rf/use-subscribe [:map/id])]
    (use-effect
     (fn []
       (when (and map-id (not= map-id loaded-id))
         (rf/dispatch [:map/fetch map-id]))
       js/undefined)
     [map-id loaded-id])
    ($ map-view)))

(defui router-view
  "Renders the matched SPA route. Shown only once the user is
   authenticated."
  []
  (let [route-name  (uix.rf/use-subscribe [:router/route-name])
        path-params (uix.rf/use-subscribe [:router/path-params])]
    (case route-name
      ::router/maps-list ($ maps-list)
      ::router/map       ($ map-route {:map-id (:id path-params)})
      ;; No match yet (initial render before the router fires) — show
      ;; nothing rather than flashing wrong content.
      nil)))

(defn- spinner []
  ($ :div {:class "min-h-screen flex items-center justify-center bg-gray-50"}
     ($ :span {:class "loading loading-spinner loading-lg"})))

(defui app-root
  "SPA root. Decides what to show from the current route + auth state:
   - auth check in flight                  → spinner
   - /app/signup (launched, not logged in) → signup screen
   - not logged in                         → login screen. Covers
       /app/login, a protected route hit while unauthed (gate-in-place —
       the URL stays put), and /app/signup pre-launch (degrades to login).
   - logged in on an auth route            → redirected into the app
   - logged in on a protected route        → the client-side router."
  []
  (let [auth-loading (uix.rf/use-subscribe [:auth/loading])
        logged-in    (uix.rf/use-subscribe [:auth/logged-in])
        launched     (uix.rf/use-subscribe [:launched])
        route-name   (uix.rf/use-subscribe [:router/route-name])
        auth-route?  (contains? #{::router/login ::router/signup} route-name)]
    ;; A logged-in user who lands on an auth route belongs in the app.
    (use-effect
     (fn []
       (when (and logged-in auth-route?)
         (rf/dispatch [:router/navigate {:name ::router/maps-list}]))
       js/undefined)
     [logged-in auth-route?])
    (cond
      auth-loading
      (spinner)

      (and (= route-name ::router/signup) launched (not logged-in))
      ($ auth-screen {:mode :signup})

      (not logged-in)
      ($ auth-screen {:mode :login})

      ;; Logged in but still on /app/login or /app/signup — the effect
      ;; above is navigating into the app; hold a spinner meanwhile.
      auth-route?
      (spinner)

      :else
      ($ router-view))))

(defn get-demo-settings
  "Extract demo mode configuration from root element"
  [root-el]
  (when root-el
    (let [mode (.getAttribute root-el "data-demo-mode")]
      (cond
        (= mode "minimal") :minimal
        (= mode "true")    true
        :else              false))))

(defn get-launched
  "Read the runtime launch toggle from the root element. Mirrors the
   server-side `aps.parts.launch/launched?` flag."
  [root-el]
  (= "true" (some-> root-el (.getAttribute "data-launched"))))

(defn- setup-demo
  "Playground boot path: localStorage storage, no router, no auth gate —
   mount the canvas directly."
  [demo-mode launched]
  (storage-registry/init-localstorage-backend!)
  (rf/dispatch-sync [:app/init-db (assoc initial-db
                                         :demo-mode demo-mode
                                         :launched  launched)])
  (rf/dispatch [:app/init-demo-map])
  ($ map-view))

(defn- setup-spa
  "App boot path (/app/*): HTTP storage, start the client-side router and
   the auth gate."
  [launched]
  (storage-registry/init-http-backend!)
  (rf/dispatch-sync [:app/init-db (assoc initial-db :launched launched)])
  (router/start!)
  ($ app-root))

(defn setup-app
  "Set up the app: create the React root, pick the boot path from the
   shell's data attributes, render. The playground renders a server shell
   with data-demo-mode; the /app shell does not."
  [root-el]
  (let [root      (uix.dom/create-root root-el)
        demo-mode (get-demo-settings root-el)
        launched  (get-launched root-el)
        element   (if demo-mode
                    (setup-demo demo-mode launched)
                    (setup-spa launched))]
    (uix.dom/render-root element root)
    {:root      root
     :demo-mode demo-mode}))

(defonce app-state (atom nil))

(defn- boot!
  "Mount the React app on #root if the page has one."
  []
  (when-let [root-el (js/document.getElementById "root")]
    (reset! app-state (setup-app root-el))))

(defn ^:export init []
  ;; Configure HTMX response handling for validation errors. HTMX still
  ;; drives the marketing page's server-rendered forms (waitlist signup).
  (set! (.-responseHandling (.-config htmx))
        #js [#js {:code "204" :swap false} ; 204 - No Content by default does nothing, but is not an error
             #js {:code "400" :swap true :error false} ; 400 - Bad Request (validation errors) should swap content
             #js {:code "409" :swap true :error false} ; 409 - Conflict (duplicate email) should swap content
             #js {:code "[23].." :swap true} ; 200 & 300 responses are non-errors and are swapped
             #js {:code "[45].." :swap false :error true} ; Other 400 & 500 responses are not swapped and are errors
             #js {:code "..." :swap false}]) ; catch all for any other response code

  ;; The /app shell has no htmx, so boot the React app once the DOM is
  ;; ready. The landing page still loads htmx; its forms work the same.
  ;; If the document already finished parsing (script at end of body),
  ;; boot immediately rather than waiting for an event that already fired.
  (if (= "loading" (.-readyState js/document))
    (.addEventListener js/document "DOMContentLoaded" boot!)
    (boot!))
  (let [version (.-version htmx)]
    (o/info "app.init" "HTMX loaded! Version:" version)))

(defn- render-root!
  "Re-render whichever boot path is active. Used by hot-reload."
  [root-el root]
  (let [demo-mode (get-demo-settings root-el)]
    (uix.dom/render-root (if demo-mode ($ map-view) ($ app-root)) root)))

(defn ^:dev/after-load reload! []
  (o/info "app.reload" "Reloading app...")
  (when-let [root (:root @app-state)]
    (when-let [root-el (js/document.getElementById "root")]
      (render-root! root-el root))))
