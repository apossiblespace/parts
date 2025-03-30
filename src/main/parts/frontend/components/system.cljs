(ns parts.frontend.components.system
  (:require
   ["@xyflow/react" :refer [Background Controls MiniMap Panel ReactFlow addEdge useEdgesState useNodesState]]
   [clojure.string :as str]
   [parts.frontend.api.queue :as queue]
   [parts.frontend.components.edges :refer [edge-types]]
   [parts.frontend.components.nodes :refer [node-types]]
   [parts.frontend.components.toolbar.button :refer [button]]
   [parts.frontend.components.toolbar.sidebar :refer [sidebar]]
   [parts.frontend.context :as ctx]
   [parts.frontend.utils.node-utils :refer [build-updated-part]]
   [parts.frontend.adapters.reactflow :as a]
   [uix.core :refer [$ defui use-callback use-effect use-memo use-state]]))

;; FIXME: This shouldn't be returning a javascript object.
;; Ideally, we would not be manipulating JS outside of the React component at
;; all -- it should all be Clojure.
(defn- new-node [type _opts]
  #js {:id (str (random-uuid))
       :type type
       :position #js {:x 390 :y 290}
       :data #js {:label (str/capitalize type)}})

(defn- add-node
  ([type]
   (add-node type {}))
  ([type opts]
   (fn [current-nodes]
     (let [node (new-node type opts)]
       (.concat current-nodes
                #js [node])
       (queue/add-events! :node [{:id (:id node)
                                  :type "create"
                                  :data {:type (:type node)
                                         :label (get-in node [:data :label])}}])))))

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
           (js->clj nodes :keywordize-keys true)))))
  (queue/add-events! :node [{:id id
                             :type "update"
                             :data form-data}]))

(defn- update-edge-callback [setEdges id form-data]
  (println "update-edge-callback" id form-data)
  (setEdges
   (fn [edges]
     (clj->js
      (map (fn [edge]
             (let [edge-map (js->clj edge :keywordize-keys true)]
               (if (= (:id edge-map) id)
                 (-> edge-map
                     (assoc-in [:data :relationship] (:relationship form-data))
                     (assoc :className (str "edge-" (:relationship form-data))))
                 edge-map)))
           (js->clj edges :keywordize-keys true)))))
  (queue/add-events! :edge [{:id id
                             :type "update"
                             :data form-data}]))

(defn- with-default-relationship [edge-params]
  (update-in (js->clj edge-params :keywordize-keys true)
             [:data]
             #(merge {:relationship :unknown} %)))

(defn- on-connect-callback [setEdges params]
  (setEdges #(addEdge (clj->js (with-default-relationship params)) %))
  (queue/add-events! :edge [(assoc params :type "create")]))

(defui system [{:keys [parts relationships]}]
  (let [node-types (use-memo (fn [] (clj->js node-types)) [node-types])
        edge-types (use-memo (fn [] (clj->js edge-types)) [edge-types])
        [nodes setNodes onNodesChange] (useNodesState (a/parts->nodes parts))
        [edges setEdges onEdgesChange] (useEdgesState (a/relationships->edges relationships))
        [selected-nodes set-selected-nodes] (use-state nil)
        [selected-edges set-selected-edges] (use-state nil)
        on-nodes-change (fn [changes]
                          (println "[on-nodes-change]" changes)
                          (queue/add-events! :node (js->clj changes :keywordize-keys true))
                          (onNodesChange changes))
        on-edges-change (fn [changes]
                          (println "[on-edges-change]" changes)
                          (queue/add-events! :edge (js->clj changes :keywordize-keys true))
                          (onEdgesChange changes))
        update-node (use-callback
                     #(update-node-callback setNodes %1 %2)
                     [setNodes])
        update-edge (use-callback
                     #(update-edge-callback setEdges %1 %2)
                     [setEdges])
        on-connect (use-callback
                    #(on-connect-callback setEdges %)
                    [setEdges])
        on-selection-change (use-callback
                             (fn [selections]
                               (let [sel (js->clj selections :keywordize-keys true)]
                                 (set-selected-nodes (:nodes sel))
                                 (set-selected-edges (:edges sel))))
                             [])]

    (use-effect
     (fn []
       (println "[system] mounting")
       (queue/start)
       (fn []
         (println "[system] unmounting")
         (queue/stop)))
     [])

    ($ (.-Provider ctx/update-system-context) {:value {:update-node update-node :update-edge update-edge}}
       ($ :div {:class "system-container"}
          ($ :div {:class "system-view"}
             ($ ReactFlow {:nodes nodes
                           :edges edges
                           :onNodesChange on-nodes-change
                           :onEdgesChange on-edges-change
                           :onConnect on-connect
                           :onSelectionChange on-selection-change
                           :nodeTypes node-types
                           :edgeTypes edge-types}
                ($ Controls)
                ($ Panel {:position "top-left" :class "logo"}
                   ($ :img {:src "/images/parts-logo-horizontal.svg" :width 150}))
                ($ Panel {:position "top-center" :class "toolbar shadow-xs"}
                   ($ :div {:class "join"}
                      ($ button {:label "Unknown"
                                 :on-click (fn [] (setNodes (add-node "unknown")))})
                      ($ button {:label "Exile"
                                 :on-click (fn [] (setNodes (add-node "exile")))})
                      ($ button {:label "Firefighter"
                                 :on-click (fn [] (setNodes (add-node "firefighter")))})
                      ($ button {:label "Manager"
                                 :on-click (fn [] (setNodes (add-node "manager")))})))
                ($ Panel {:position "top-right" :className "sidebar-container"}
                   ($ sidebar {:selected-nodes selected-nodes
                               :selected-edges selected-edges}))
                ($ MiniMap {:className "tools parts-minimap shadow-sm"
                            :position "bottom-right"
                            :ariaLabel "Minimap"
                            :pannable true
                            :zoomable true
                            :offsetScale 5})
                ($ Background {:variant "dots"
                               :gap 12
                               :size 1})))))))
