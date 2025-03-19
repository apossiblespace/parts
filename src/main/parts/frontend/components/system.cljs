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
   [uix.core :refer [defui $ use-state]]
   [clojure.string :as str]
   [parts.frontend.components.nodes :refer [node-types]]
   [parts.frontend.components.toolbar :refer [parts-toolbar]]
   [parts.frontend.components.sidebar :refer [sidebar]]
   [parts.frontend.utils.node-utils :refer [build-updated-part]]
   [parts.frontend.context :as ctx]))

;; FIXME: This shouldn't be returning a javascript object.
;; Ideally, we would not be manipulating JS outside of the React component at
;; all -- it should all be Clojure.
(defn- new-node [type _opts]
  #js{:id (str (random-uuid))
      :type type
      :position #js{:x 390 :y 290}
      :data #js{:label (str/capitalize type)}})

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

(defn- update-node-callback [setNodes id form-data]
  (println "update-node-callback" id form-data)
  (setNodes
   (fn [nodes]
     (clj->js
      (map (fn [node]
             (let [node-map (js->clj node :keywordize-keys true)]
               (if (= (:id node-map) id)
                 (build-updated-part node-map form-data)
                 node-map)))
           (js->clj nodes :keywordize-keys true))))))

(defn- on-connect-callback [setEdges params]
  (setEdges #(addEdge params %)))

(defui system [{:keys [nodes edges]}]
  (let [node-types (uix.core/use-memo (fn [] (clj->js node-types)) [node-types])
        [nodes setNodes onNodesChange] (useNodesState (clj->js nodes))
        [edges setEdges onEdgesChange] (useEdgesState (clj->js edges))
        [selected-nodes set-selected-nodes] (uix.core/use-state nil)
        [selected-edges set-selected-edges] (uix.core/use-state nil)
        update-node (uix.core/use-callback
                     #(update-node-callback setNodes %1 %2)
                     [setNodes])
        on-connect (uix.core/use-callback
                    #(on-connect-callback setEdges %)
                    [setEdges])
        on-selection-change (uix.core/use-callback
                             (fn [selections]
                               (let [sel (js->clj selections :keywordize-keys true)]
                                 (println "Sel:" sel)
                                 (set-selected-nodes (:nodes sel))
                                 (set-selected-edges (:edges sel))))
                             [])]

    ($ (.-Provider ctx/update-node-context) {:value update-node}
       ($ :div {:class "system-container"}
          ($ :div {:class "system-view"}
             ($ ReactFlow {:nodes nodes
                           :edges edges
                           :onNodesChange onNodesChange
                           :onEdgesChange onEdgesChange
                           :onConnect on-connect
                           :onSelectionChange on-selection-change
                           :nodeTypes node-types}
                ($ MiniMap)
                ($ Controls)
                ($ Panel {:position "top-left" :class "logo"}
                   ($ :img {:src "/images/parts-logo-horizontal.svg" :width 150}))
                ($ Panel {:position "top-right" :class "toolbar"}
                   ($ parts-toolbar
                      ($ :span " Add part: ")
                      ($ :div {:class "join"}
                         ($ :button
                            {:class "btn btn-xs join-item"
                             :on-click (fn [] (setNodes (add-node "unknown")))}
                            "Unknown")
                         ($ :button
                            {:class "btn btn-xs join-item"
                             :on-click (fn [] (setNodes (add-node "exile")))}
                            "Exile")
                         ($ :button
                            {:class "btn btn-xs join-item"
                             :on-click (fn [] (setNodes (add-node "firefighter")))}
                            "Firefighter")
                         ($ :button
                            {:class "btn btn-xs join-item"
                             :on-click (fn [] (setNodes (add-node "manager")))}
                            "Manager"))))
                ($ Background {:variant "dots"
                               :gap 12
                               :size 1})))
          ($ sidebar {:selected-nodes selected-nodes
                      :selected-edges selected-edges})))))
