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
   [parts.frontend.components.nodes :refer [node-types]]
   [parts.frontend.context :as ctx]))

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

(defui system [{:keys [nodes edges]}]
  (let [node-types (uix.core/use-memo (fn [] (clj->js node-types)) [node-types])
        [nodes setNodes onNodesChange] (useNodesState (clj->js nodes))
        [edges setEdges onEdgesChange] (useEdgesState (clj->js edges))
        update-node (uix.core/use-callback
                     (fn [id form-data]
                       (setNodes (fn [nodes]
                                   (clj->js
                                    (map (fn [node]
                                           (let [node-map (js->clj node :keywordize-keys true)]
                                             (if (= (:id node-map) id)
                                               (-> node-map
                                                   (assoc :type (:type form-data))
                                                   (assoc :data {"label" (:label form-data)}))
                                               node-map)))
                                         (js->clj nodes :keywordize-keys true))))))
                     [setNodes])
        on-connect (uix.core/use-callback
                   (fn [params]
                     (setEdges (fn [eds] (addEdge params eds))))
                   [setEdges])]
    ($ :div {:style {:width "100vw" :height "100vh"} :class "system-view"}
       ($ (.-Provider ctx/update-node-context) {:value update-node}
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
                            :size 1}))))))
