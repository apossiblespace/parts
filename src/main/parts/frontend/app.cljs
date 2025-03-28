(ns parts.frontend.app
  (:require
   ["htmx.org" :default htmx]
   [parts.frontend.components.system :refer [system]]
   [parts.frontend.context :refer [auth-provider]]
   [uix.core :refer [$ defui]]
   [uix.dom]))

(def system-data
  {:nodes
   [{:id "1" :position {:x 300 :y 130} :type "manager" :data {:label "Manager"}}
    {:id "2" :position {:x 200 :y 300} :type "exile" :data {:label "Exile"}}
    {:id "3" :position {:x 100 :y 130} :type "firefighter" :data {:label "Firefighter"}}]
   :edges
   [{:id "e1-2" :source "1" :target "2" :data {:relationship "unknown"}}
    {:id "e3-2" :source "3" :target "2" :data {:relationship "protective"}}]})

(defui app []
  ($ auth-provider {}
     ($ system system-data)))

(defonce root
  (when-let [root-element (js/document.getElementById "root")]
    (uix.dom/create-root root-element)))

(defn render-app
  "Render the app if root element exists"
  []
  (when root
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
