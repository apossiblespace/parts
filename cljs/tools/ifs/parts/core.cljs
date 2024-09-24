(ns tools.ifs.parts.core
  (:require ["d3" :as d3]
            ["htmx.org" :default htmx]))

(defn create-visualization [el]
  (js/console.log "Creating visualization")
  (let [svg (-> d3
                (.select el)
                (.append "svg")
                (.attr "width" 600)
                (.attr "height" 400))]
    (-> svg
        (.append "circle")
        (.attr "cx" 300)
        (.attr "cy" 200)
        (.attr "r" 100)
        (.style "fill" "steelblue"))))

(defn ^:export init []
  (create-visualization (.getElementById js/document "chart"))
  (.on htmx "htmx:load"
       (fn [_event]
         (js/console.log "htmx loaded!"))))
