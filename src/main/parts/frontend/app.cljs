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
  {:demo-mode true
   :system system-data
   :systems {:list []
             :loading false}})

(defui app []
  (let [[show-system-list set-show-system-list] (use-state false)
        current-system-id (uix.rf/use-subscribe [:system/id])
        demo-mode (uix.rf/use-subscribe [:demo-mode])]

    (use-effect
     (fn []
       (when-not demo-mode
         (if-let [stored-id (api-utils/get-current-system-id)]
           (rf/dispatch [:system/load stored-id])
           (set-show-system-list true))))
     [demo-mode])
    ($ :<>
       (when-not demo-mode
         (when-not current-system-id
           ($ system-list-modal
              {:show show-system-list
               :on-close #(set-show-system-list false)})))
       ($ system))))

(defonce root
  (when-let [root-element (js/document.getElementById "root")]
    (uix.dom/create-root root-element)))

(defn render-app
  "Render the app if root element exists"
  []
  (when root
    (rf/dispatch-sync [:app/init-db initial-db])
    (uix.dom/render-root ($ app) root)))

(defn ^:export init []
  (.on htmx "htmx:load"
       (fn [_]
         (render-app)
         (let [version (.-version htmx)]
           (js/console.log "HTMX loaded! Version:" version)))))

(defn ^:dev/after-load reload! []
  (js/console.log "Reloading app...")
  (uix.dom/render-root ($ app) root))
