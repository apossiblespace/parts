(ns aps.parts.frontend.auth-app
  "Minimal auth app for the landing page.
   Mounts on #auth-root and handles login/signup modals triggered by custom events.
   Also redirects logged-in users to their system."
  (:require
   [aps.parts.frontend.api.core :as api]
   [aps.parts.frontend.components.login-modal :refer [login-modal]]
   [aps.parts.frontend.components.signup-modal :refer [signup-modal]]
   [aps.parts.frontend.state.fx]
   [aps.parts.frontend.state.handlers]
   [aps.parts.frontend.state.subs]
   [aps.parts.frontend.storage.registry :as storage-registry]
   [cljs.core.async :refer [go <!]]
   [re-frame.core :as rf]
   [uix.core :refer [$ defui use-state use-effect]]
   [uix.dom]
   [uix.re-frame :as uix.rf]))

(def initial-db
  {:demo-mode false
   :system    {}
   :systems   {:list    []
               :loading false}})

(defn redirect-to-system!
  "Fetch user's systems and redirect to the first one"
  []
  (go
    (let [response (<! (api/get-systems))]
      (when (= 200 (:status response))
        (let [systems (:body response)]
          (when-let [system-id (:id (first systems))]
            (set! (.-href js/window.location)
                  (str "/systems/" system-id))))))))

(defui auth-app []
  (let [[show-login set-show-login]   (use-state false)
        [show-signup set-show-signup] (use-state false)
        user                          (uix.rf/use-subscribe [:auth/user])
        auth-loading                  (uix.rf/use-subscribe [:auth/loading])]

    ;; Redirect logged-in users to their system
    (use-effect
     (fn []
       (when (and user (not auth-loading))
         (redirect-to-system!))
       js/undefined)
     [user auth-loading])

    ;; Listen for custom events from server-rendered buttons
    (use-effect
     (fn []
       (let [login-handler  #(set-show-login true)
             signup-handler #(set-show-signup true)]
         (.addEventListener js/window "parts:open-login" login-handler)
         (.addEventListener js/window "parts:open-signup" signup-handler)
         (fn []
           (.removeEventListener js/window "parts:open-login" login-handler)
           (.removeEventListener js/window "parts:open-signup" signup-handler))))
     [])

    ($ :<>
       ($ login-modal
          {:show     show-login
           :on-close #(set-show-login false)})
       ($ signup-modal
          {:show       show-signup
           :on-close   #(set-show-signup false)
           :on-success (fn [result]
                         ;; Redirect to the new system after successful signup
                         (when-let [system-id (get-in result [:body :system_id])]
                           (set! (.-href js/window.location)
                                 (str "/systems/" system-id))))}))))

(defn ^:export init []
  (when-let [root-el (js/document.getElementById "auth-root")]
    (let [root (uix.dom/create-root root-el)]
      ;; Initialize HTTP backend for API calls
      (storage-registry/init-http-backend!)
      (rf/dispatch-sync [:app/init-db initial-db])
      (rf/dispatch [:auth/check-auth])
      (uix.dom/render-root ($ auth-app) root))))
