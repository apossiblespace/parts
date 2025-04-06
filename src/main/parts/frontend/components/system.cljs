(ns parts.frontend.components.system
  (:require
   ["@xyflow/react" :refer [Background Controls MiniMap Panel ReactFlow]]
   [parts.frontend.adapters.reactflow :as adapter]
   [parts.frontend.api.queue :as queue]
   [parts.frontend.components.edges :refer [edge-types]]
   [parts.frontend.components.nodes :refer [node-types]]
   [parts.frontend.components.toolbar.button :refer [button]]
   [parts.frontend.components.toolbar.sidebar :refer [sidebar]]
   [parts.frontend.context :as ctx]
   [uix.core :refer [$ defui use-callback use-effect use-state]]
   [uix.re-frame :as uix.rf]
   [re-frame.core :as rf]))

(defui system []
  (let [parts (uix.rf/use-subscribe [:system/parts])
        relationships (uix.rf/use-subscribe [:system/relationships])
        selected-node-ids (uix.rf/use-subscribe [:ui/selected-node-ids])
        selected-edge-ids (uix.rf/use-subscribe [:ui/selected-edge-ids])

        nodes (adapter/parts->nodes parts selected-node-ids)
        edges (adapter/relationships->edges relationships selected-edge-ids)

        on-nodes-change (use-callback
                         (fn [changes]
                           (println "[on-nodes-change]" changes)
                           (->> (js->clj changes :keywordize-keys true)
                                (run! (fn [change]
                                        (case (:type change)
                                          "position" (when-let [position (:position change)]
                                                       (rf/dispatch [:system/update-part-position
                                                                     (:id change)
                                                                     position])
                                                       (when-not (:dragging change)
                                                         (rf/dispatch [:system/part-finish-position-change
                                                                       (:id change)
                                                                       position])))
                                          "select" (rf/dispatch [:selection/toggle-node
                                                                 (:id change)
                                                                 (:selected change)])
                                          (js/console.log "Unhandled node change:" change)))))) [])

        on-edges-change (use-callback
                         (fn [changes]
                           (println "[on-edges-change]" changes)
                           (->> (js->clj changes :keywordize-keys true)
                                (run! (fn [change]
                                        (case (:type change)
                                          "select" (rf/dispatch [:selection/toggle-edge
                                                                 (:id change)
                                                                 (:selected change)])
                                          (js/console.log "Unhandled edge change:" change)))))) [])

        on-connect (fn [connection]
                     (println "[on-connect]" connection))
                     ;; (let [{:keys [source target]} (js->clj connection :keywordize-keys true)]
                     ;;   (add-relationship {:source_id source
                     ;;                      :target_id target
                     ;;                      :type "unknown"})))

        on-selection-change (use-callback
                             (fn [selection]
                               (println "[on-selection-change]" selection)
                               (let [sel (js->clj selection :keywordize-keys true)]
                                 (rf/dispatch [:selection/set sel])))
                             [])

        create-part-by-type (fn [type]
                              (println "[create-part-by-type]" type))
                              ;; (add-part {:type type
                              ;;            :position_x 390
                              ;;            :position_y 290}))]
        ]

    (use-effect
     (fn []
       (println "[system] starting event queue")
       (queue/start)
       (fn []
         (println "[system] stopping event queue")
         (queue/stop)))
     [])

    ($ (.-Provider ctx/update-system-context)
       {:value {:update-node (use-callback (fn [_ _] (println "update-node")) [])
                :update-edge (use-callback (fn [_ _] (println "update-edge")) [])}}
       ($ :div {:class "system-container"}
          ($ :div {:class "system-view"}
             ($ ReactFlow {:nodes nodes
                           :edges edges
                           :onNodesChange on-nodes-change
                           :onEdgesChange on-edges-change
                           :onConnect on-connect
                           :onSelectionChange on-selection-change
                           :nodeTypes (clj->js node-types)
                           :edgeTypes (clj->js edge-types)}
                ($ Controls)
                ($ Panel {:position "top-left" :class "logo"}
                   ($ :img {:src "/images/parts-logo-horizontal.svg" :width 150}))
                ($ Panel {:position "top-center" :class "toolbar shadow-xs"}
                   ($ :div {:class "join"}
                      ($ button {:label "Unknown"
                                 :on-click #(create-part-by-type "unknown")})
                      ($ button {:label "Exile"
                                 :on-click #(create-part-by-type "exile")})
                      ($ button {:label "Firefighter"
                                 :on-click #(create-part-by-type "firefighter")})
                      ($ button {:label "Manager"
                                 :on-click #(create-part-by-type "manager")})))
                ($ Panel {:position "top-right" :className "sidebar-container"}
                   ($ sidebar))
                ($ MiniMap {:className "tools parts-minimap shadow-sm"
                            :position "bottom-right"
                            :ariaLabel "Minimap"
                            :pannable true
                            :zoomable true
                            :offsetScale 5})
                ($ Background {:variant "dots"
                               :gap 12
                               :size 1})))))))
