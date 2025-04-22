(ns parts.frontend.components.system
  (:require
   ["@xyflow/react" :refer [Background Controls MiniMap Panel ReactFlow]]
   [parts.frontend.adapters.reactflow :as adapter]
   [parts.frontend.api.queue :as queue]
   [parts.frontend.components.edges :refer [edge-types]]
   [parts.frontend.components.nodes :refer [node-types]]
   [parts.frontend.components.toolbar.button :refer [button]]
   [parts.frontend.components.toolbar.sidebar :refer [sidebar]]
   [uix.core :refer [$ defui use-callback use-effect]]
   [uix.re-frame :as uix.rf]
   [re-frame.core :as rf]))

(defui system []
  (let [demo-mode (uix.rf/use-subscribe [:demo-mode])
        system-id (uix.rf/use-subscribe [:system/id])
        parts (uix.rf/use-subscribe [:system/parts])
        relationships (uix.rf/use-subscribe [:system/relationships])
        selected-node-ids (uix.rf/use-subscribe [:ui/selected-node-ids])
        selected-edge-ids (uix.rf/use-subscribe [:ui/selected-edge-ids])
        display-sidebar (or (not demo-mode)
                            (or (seq selected-node-ids) (seq selected-edge-ids)))
        nodes (adapter/parts->nodes parts selected-node-ids)
        edges (adapter/relationships->edges relationships selected-edge-ids)

        on-nodes-change (use-callback
                         (fn [changes]
                           (js/console.log "[on-nodes-change]" changes)
                           (->> (js->clj changes :keywordize-keys true)
                                (run! (fn [change]
                                        (case (:type change)
                                          "position" (when-let [position (:position change)]
                                                       (rf/dispatch [:system/part-update-position
                                                                     (:id change)
                                                                     position])
                                                       (when-not (:dragging change)
                                                         (rf/dispatch [:system/part-finish-position-change
                                                                       (:id change)
                                                                       position])))
                                          "select" (rf/dispatch [:selection/toggle-node
                                                                 (:id change)
                                                                 (:selected change)])
                                          "remove" (rf/dispatch [:system/part-remove
                                                                 (:id change)])
                                          (js/console.log "[on-nodes-change][UNHANDLED]" change)))))) [])

        on-edges-change (use-callback
                         (fn [changes]
                           (js/console.log "[on-edges-change]" changes)
                           (->> (js->clj changes :keywordize-keys true)
                                (run! (fn [change]
                                        (case (:type change)
                                          "select" (rf/dispatch [:selection/toggle-edge
                                                                 (:id change)
                                                                 (:selected change)])
                                          "remove" (rf/dispatch [:system/relationship-remove
                                                                 (:id change)])
                                          (js/console.log "[on-edges-change][UNHANDLED]" change)))))) [])

        on-connect (use-callback
                    (fn [connection]
                      (js/console.log "[on-connect]" connection)
                      (let [params (js->clj connection :keywordize-keys true)
                            source-id (:source params)
                            target-id (:target params)]
                        (rf/dispatch [:system/relationship-create
                                      {:source_id source-id
                                       :target_id target-id}])))
                    [])

        ;; FIXME: We might need to remove this in order to properly handle
        ;; selection by dragging. When this callback is set, dragging does not
        ;; select any edges, for reasons yet unclear.
        on-selection-change (use-callback
                             (fn [selection]
                               (js/console.log "[on-selection-change]" selection)
                               (let [sel (js->clj selection :keywordize-keys true)]
                                 (rf/dispatch [:selection/set sel])))
                             [])

        create-part-by-type (fn [type]
                              (js/console.log "[create-part-by-type]" type)
                              (rf/dispatch [:system/part-create {:type type}]))]

    (use-effect
     (fn []
       (js/console.log "[system] starting event queue")
       (queue/start system-id)
       (fn []
         (js/console.log "[system] stopping event queue")
         (queue/stop)))
     [system-id])

    ($ :div {:class "system-container"}
       ($ :div {:class "system-view"}
          ($ ReactFlow {:nodes nodes
                        :edges edges
                        :onNodesChange on-nodes-change
                        :onEdgesChange on-edges-change
                        :onConnect on-connect
                        ;; :onSelectionChange on-selection-change
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
             (when display-sidebar
               ($ Panel {:position "top-right" :className "sidebar-container"}
                  ($ sidebar)))
             ($ MiniMap {:className "tools parts-minimap shadow-sm"
                         :position "bottom-right"
                         :ariaLabel "Minimap"
                         :pannable true
                         :zoomable true
                         :offsetScale 5})
             ($ Background {:variant "dots"
                            :gap 12
                            :size 1}))))))
