(ns parts.frontend.components.system
  (:require
   ["reactflow" :refer [ReactFlow
                        MiniMap
                        Controls
                        Background
                        useNodesState
                        useEdgesState
                        addEdge]]
   [uix.core :refer [defui $]]
   [parts.frontend.components.nodes :refer [node-types]]))

(def initial-nodes
  [
   {:id "1" :position {:x 200 :y 30} :type "manager" :data {:label "Manager"}}
   {:id "2" :position {:x 100 :y 200} :type "exile" :data {:label "Exile"}}
   {:id "3" :position {:x 30 :y 30} :type "firefighter" :data {:label "Firefighter"}}
   ])

(def initial-edges
  [{:id "e1-2" :source "1" :target "2"}
   {:id "e3-2" :source "3" :target "2"}])

;; NOTE: Layouting
;; https://reactflow.dev/learn/layouting/layouting
;; https://d3js.org/d3-force
;; https://marvl.infotech.monash.edu/webcola/

(defui system []
  (let [node-types (uix.core/use-memo (fn [] (clj->js node-types)) [node-types])
        [nodes setNodes onNodesChange] (useNodesState (clj->js initial-nodes))
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
                     :onConnect on-connect
                     :nodeTypes node-types}
          ($ MiniMap)
          ($ Controls)
          ($ Background {:variant "dots"
                        :gap 12
                        :size 1})))))
