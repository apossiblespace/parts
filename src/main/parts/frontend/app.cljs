(ns parts.frontend.app
  (:require
   ["htmx.org" :default htmx]))

(defn ^:export init []
  (.on htmx "htmx:load"
       (fn [evt]
         (let [version (.-version htmx)]
           (js/console.log "HTMX loaded! Version:" version)))))
