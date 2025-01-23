(ns tools.ifs.parts.core
  (:require
   ["reactflow" :refer [Background Controls ReactFlow ReactFlowProvider]]
   [uix.core :refer [$ defui]]
   [uix.dom]))

(def initial-nodes
  [{:id "1" :data {:label "1"} :position {:x 250 :y 25}}
   {:id "2" :data {:label "2"} :position {:x 250 :y 125}}])

(def initial-edges
  [{:id "e1-2" :source "1" :target "2"}])

(defui flow-diagram []
  (let [[nodes set-nodes] (uix.core/use-state initial-nodes)
        [edges set-edges] (uix.core/use-state initial-edges)]
    ($ ReactFlowProvider
       ($ :div {:style {:width "100%" :height "600px"}}
          ($ ReactFlow
             {:nodes (clj->js nodes)
              :edges (clj->js edges)
              :fitView true})
          ($ Background)
          ($ Controls)))))

(defonce root
  (uix.dom/create-root (js/document.getElementById "root")))

(defn ^:export init []
  (uix.dom/render-root
    ($ flow-diagram)
    root))
