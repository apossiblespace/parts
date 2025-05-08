(ns parts.frontend.app
  (:require
   ["htmx.org" :default htmx]
   [parts.common.models.part :refer [make-part]]
   [parts.common.models.relationship :refer [make-relationship]]
   [parts.frontend.api.utils :as api-utils]
   [parts.frontend.components.system :refer [system]]
   [parts.frontend.components.system-list-modal :refer [system-list-modal]]
   [parts.frontend.state.fx]
   [parts.frontend.state.handlers]
   [parts.frontend.state.subs]
   [re-frame.core :as rf]
   [uix.core :refer [$ defui use-effect use-state]]
   [uix.dom]
   [uix.re-frame :as uix.rf]))

(def system-id (str (random-uuid)))

(def parts
  [(make-part {:type "manager"
               :label "Manager"
               :position_x 300
               :position_y 130
               :system_id system-id})
   (make-part {:type "exile"
               :label "Exile"
               :position_x 200
               :position_y 300
               :system_id system-id})
   (make-part {:type "firefighter"
               :label "Firefighter"
               :position_x 100
               :position_y 130
               :system_id system-id})])

(def relationships
  [(make-relationship {:type "unknown"
                       :source_id (:id (nth parts 0))
                       :target_id (:id (nth parts 1))
                       :system_id system-id})
   (make-relationship {:type "protective"
                       :source_id (:id (nth parts 2))
                       :target_id (:id (nth parts 1))
                       :system_id system-id})])

(def system-data
  {:id system-id
   :parts parts
   :relationships relationships})

(def initial-db
  {:demo-mode false
   :system system-data
   :systems {:list []
             :loading false}})

(defui app []
  (let [[show-system-list set-show-system-list] (use-state false)
        current-system-id (uix.rf/use-subscribe [:system/id])
        demo (uix.rf/use-subscribe [:demo])]

    (use-effect
     (fn []
       (when-not demo
         (if-let [stored-id (api-utils/get-current-system-id)]
           (rf/dispatch [:system/load stored-id])
           (set-show-system-list true))))
     [demo])
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
    (rf/dispatch-sync [:app/init-db initial-db-with-demo])
    (uix.dom/render-root ($ app) root)
    {:root root
     :demo-mode demo-mode}))

(defonce app-state (atom nil))

(defn ^:export init []
  (.on htmx "htmx:load"
       (fn [_]
         (when-let [root-el (js/document.getElementById "root")]
           (reset! app-state (setup-app root-el)))
         (let [version (.-version htmx)]
           (js/console.log "HTMX loaded! Version:" version)))))

(defn ^:dev/after-load reload! []
  (js/console.log "Reloading app...")
  (when-let [root (:root @app-state)]
    (uix.dom/render-root ($ app) root)))
