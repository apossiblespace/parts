(ns parts.frontend.components.system
  (:require
   ["reactflow" :refer [ReactFlow
                        MiniMap
                        Controls
                        Background
                        useNodesState
                        useEdgesState
                        Panel
                        addEdge]]
   [uix.core :refer [defui $]]
   [parts.frontend.components.nodes :refer [node-types]]))

(def initial-nodes
  [
   {:id "1" :position {:x 300 :y 130} :type "manager" :data {:label "Manager"}}
   {:id "2" :position {:x 200 :y 300} :type "exile" :data {:label "Exile"}}
   {:id "3" :position {:x 100 :y 130} :type "firefighter" :data {:label "Firefighter"}}
   ])

(def initial-edges
  [{:id "e1-2" :source "1" :target "2"}
   {:id "e3-2" :source "3" :target "2"}])

;; FIXME: This shouldn't be returning a javascript object.
;; Ideally, we would not be manipulating JS outside of the React component at
;; all -- it should all be Clojure.
(defn- new-node [type _opts]
  #js{:id (str (random-uuid))
   :type type
   :position #js{:x 390 :y 290}
   :data #js{:label type}})

(defn- add-node
  ([type]
   (add-node type {}))
  ([type opts]
   (fn [current-nodes]
     (.concat current-nodes
              #js[(new-node type opts)]))))

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
    ($ :div {:style {:width "100vw" :height "100vh"} :class "system-view"}
       ($ ReactFlow {:nodes nodes
                     :edges edges
                     :onNodesChange onNodesChange
                     :onEdgesChange onEdgesChange
                     :onConnect on-connect
                     :nodeTypes node-types}
          ($ MiniMap)
          ($ Controls)
          ($ Panel {:position "top-left" :class "logo"}
             ($ :img {:src "/images/parts-logo-horizontal.svg" :width 150}))
          ($ Panel {:position "top-right" :class "toolbar"}
             ($ :span "Add: ")
             ($ :button
                {:on-click
                 (fn []
                   (setNodes (add-node "exile")))}
                "Exile")
             ($ :button
                {:on-click
                 (fn []
                   (setNodes (add-node "firefighter")))}
                "Firefighter")
             ($ :button
                {:on-click
                 (fn []
                   (setNodes (add-node "manager")))}
                "Manager"))
          ($ Background {:variant "dots"
                        :gap 12
                        :size 1})))))
