(ns parts.frontend.app
  (:require
   ["htmx.org" :default htmx]
   [parts.frontend.components.system :refer [system]]
   [parts.frontend.components.system-list-modal :refer [system-list-modal]]
   [parts.frontend.observe :as o]
   [parts.frontend.state.fx]
   [parts.frontend.state.handlers]
   [parts.frontend.state.subs]
   [parts.frontend.storage.registry :as storage-registry]
   [re-frame.core :as rf]
   [uix.core :refer [$ defui use-state]]
   [uix.dom]
   [uix.re-frame :as uix.rf]))

(def initial-db
  {:demo-mode false
   :system {}
   :systems {:list []
             :loading false}})

(defui app []
  (let [[show-system-list set-show-system-list] (use-state false)
        current-system-id (uix.rf/use-subscribe [:system/id])
        demo (uix.rf/use-subscribe [:demo])]

    ($ :<>
       (when-not demo
         (when-not current-system-id
           ($ system-list-modal
              {:show show-system-list
               :on-close #(set-show-system-list false)})))
       ($ system))))

(defn get-demo-settings
  "Extract demo mode configuration from root element"
  [root-el]
  (when root-el
    (let [mode (.getAttribute root-el "data-demo-mode")]
      (cond
        (= mode "minimal") :minimal
        (= mode "true") true
        :else false))))

(defn setup-app
  "Setup the app with the root element, create root, init state and render"
  [root-el]
  (let [root (uix.dom/create-root root-el)
        demo-mode (get-demo-settings root-el)
        initial-db-with-demo (assoc initial-db :demo-mode demo-mode)]
    ;; Initialize storage backend based on demo mode
    (if demo-mode
      (storage-registry/init-localstorage-backend!)
      (storage-registry/init-http-backend!))
    (rf/dispatch-sync [:app/init-db initial-db-with-demo])
    (rf/dispatch [:app/init-system])
    (uix.dom/render-root ($ app) root)
    {:root root
     :demo-mode demo-mode}))

(defonce app-state (atom nil))

(defn ^:export init []
  ;; Configure HTMX response handling for validation errors
  (set! (.-responseHandling (.-config htmx))
        #js [#js {:code "204" :swap false}                    ; 204 - No Content by default does nothing, but is not an error
             #js {:code "400" :swap true :error false}        ; 400 - Bad Request (validation errors) should swap content
             #js {:code "409" :swap true :error false}        ; 409 - Conflict (duplicate email) should swap content
             #js {:code "[23].." :swap true}                  ; 200 & 300 responses are non-errors and are swapped
             #js {:code "[45].." :swap false :error true}     ; Other 400 & 500 responses are not swapped and are errors
             #js {:code "..." :swap false}])                  ; catch all for any other response code

  (.on htmx "htmx:load"
       (fn [_]
         (when-let [root-el (js/document.getElementById "root")]
           (reset! app-state (setup-app root-el)))
         (let [version (.-version htmx)]
           (o/info "app.init" "HTMX loaded! Version:" version)))))

(defn ^:dev/after-load reload! []
  (o/info "app.reload" "Reloading app...")
  (when-let [root (:root @app-state)]
    (uix.dom/render-root ($ app) root)))
