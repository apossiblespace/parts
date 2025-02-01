(ns parts.frontend.app
  (:require
   ["htmx.org" :default htmx]
   [parts.frontend.components.system :refer [system]]
   [uix.core :refer [defui $]]
   [uix.dom]))

(defui app []
  ($ system))

(defonce root
  (uix.dom/create-root (js/document.getElementById "root")))

(defn ^:export init []
  (.on htmx "htmx:load"
       (fn [_]
         (uix.dom/render-root ($ app) root)
         (let [version (.-version htmx)]
           (js/console.log "HTMX loaded! Version:" version)))))
