(ns parts.frontend.components.system
  (:require
   ["reactflow" :refer [ReactFlow
                        MiniMap
                        Controls
                        Background
                        useNodesState
                        useEdgesState
                        addEdge]]
   [uix.core :refer [defui $]]))

(def initial-nodes
  [{:id "1" :position {:x 0 :y 0} :data {:label "1"}}
   {:id "2" :position {:x 0 :y 100} :data {:label "2"}}])

(def initial-edges
  [{:id "e1-2" :source "1" :target "2"}])

;; NOTE: Layouting
;; https://reactflow.dev/learn/layouting/layouting
;; https://d3js.org/d3-force
;; https://marvl.infotech.monash.edu/webcola/

(defui system []
  (let [[nodes setNodes onNodesChange] (useNodesState (clj->js initial-nodes))
        [edges setEdges onEdgesChange] (useEdgesState (clj->js initial-edges))
        on-connect (uix.core/use-callback
                   (fn [params]
                     (setEdges (fn [eds] (addEdge params eds))))
                   [setEdges])]
    ($ :div {:style {:width "100vw" :height "100vh"}}
       ($ ReactFlow {:nodes nodes
                     :edges edges
                     :onNodesChange onNodesChange
                     :onEdgesChange onEdgesChange
                     :onConnect on-connect}
          ($ MiniMap)
          ($ Controls)
          ($ Background {:variant "dots"
                        :gap 12
                        :size 1})))))
