(ns tools.ifs.parts.core
  (:require ["d3" :as d3]
            ["htmx.org" :default htmx]))

(def node-data
  [{:type "exile"}
   {:type "exile"}
   {:type "exile"}
   {:type "manager"}
   {:type "firefighter"}
   {:type "firefighter"}])

(defn drag-start [event _d]
  (js/console.log "Drag started")
  (let [subject (d3/select (.-subject event))]
    (.raise subject)))

(defn drag [event _d]
  (js/console.log "Dragging" event _d)
  (let [x (.-x event)
        y (.-y event)
        subject (d3/select (.-subject event))]
    (-> subject
        (.attr "x" 10)
        (.attr "y" 10))))

(defn drag-end [_event _d]
  (js/console.log "Drag ended"))

(defn create-visualization [el]
  (js/console.log "Creating visualization" el)
  (-> d3
      (.select el)
      (.append "svg")
      (.attr "width" 600)
      (.attr "height" 400)))

(defn load-nodes [svg data]
  (let [width 600
        height 400
        drag-behavior (-> d3
                          (.drag)
                          (.on "start" drag-start)
                          (.on "drag" drag)
                          (.on "end" drag-end))]
    (doseq [node data]
      (let [x (rand-int width)
            y (rand-int height)
            type (:type node)
            img-path (str "/images/nodes/" type ".svg")]
        (-> svg
            (.append "image")
            (.attr "xlink:href" img-path)
            (.attr "x" x)
            (.attr "y" y)
            (.attr "width" 50)
            (.attr "height" 50)
            (.attr "class" type)
            (.call drag-behavior))))))

(defn ^:export init []
  (.on htmx "htmx:load"
       (fn [_event]
         (js/console.log "htmx loaded!")
         (let [svg (create-visualization (.getElementById js/document "chart"))]
           (load-nodes svg node-data)))))
