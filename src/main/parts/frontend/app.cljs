(ns parts.frontend.app
  (:require
   ["htmx.org" :default htmx]
   [parts.frontend.components.system :refer [system]]
   [parts.frontend.context :refer [auth-provider]]
   [parts.frontend.state.subs]
   [parts.frontend.state.handlers]
   [parts.frontend.state.fx]
   [parts.common.models.part :refer [make-part]]
   [parts.common.models.relationship :refer [make-relationship]]
   [uix.core :refer [$ defui]]
   [uix.dom]
   [re-frame.core :as rf]))

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
  {:system
   {:id system-id
    :parts parts
    :relationships relationships}})

(defui app []
  ($ auth-provider {}
     ($ system)))

(defonce root
  (when-let [root-element (js/document.getElementById "root")]
    (uix.dom/create-root root-element)))

(defn render-app
  "Render the app if root element exists"
  []
  (when root
    (js/console.log "dispatching init-db")
    (rf/dispatch-sync [:app/init-db system-data])
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
