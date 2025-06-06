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
  (let [demo (uix.rf/use-subscribe [:demo])
        minimal (uix.rf/use-subscribe [:minimal-demo])
        system-id (uix.rf/use-subscribe [:system/id])
        parts (uix.rf/use-subscribe [:system/parts])
        relationships (uix.rf/use-subscribe [:system/relationships])
        selected-node-ids (uix.rf/use-subscribe [:ui/selected-node-ids])
        selected-edge-ids (uix.rf/use-subscribe [:ui/selected-edge-ids])
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
                                          "remove" (do
                                                     ;; Track part deletion
                                                     (when (js/window.plausible)
                                                       (js/window.plausible "Part Deleted" #js {:props #js {:demo demo}}))
                                                     (rf/dispatch [:system/part-remove
                                                                   (:id change)]))
                                          (js/console.log "[on-nodes-change][UNHANDLED]" change)))))) [demo])

        on-edges-change (use-callback
                         (fn [changes]
                           (js/console.log "[on-edges-change]" changes)
                           (->> (js->clj changes :keywordize-keys true)
                                (run! (fn [change]
                                        (case (:type change)
                                          "select" (rf/dispatch [:selection/toggle-edge
                                                                 (:id change)
                                                                 (:selected change)])
                                          "remove" (do
                                                     ;; Track relationship deletion
                                                     (when (js/window.plausible)
                                                       (js/window.plausible "Relationship Deleted" #js {:props #js {:demo demo}}))
                                                     (rf/dispatch [:system/relationship-remove
                                                                   (:id change)]))
                                          (js/console.log "[on-edges-change][UNHANDLED]" change)))))) [demo])

        on-connect (use-callback
                    (fn [connection]
                      (js/console.log "[on-connect]" connection)
                      ;; Track relationship creation
                      (when (js/window.plausible)
                        (js/window.plausible "Relationship Created" #js {:props #js {:demo demo}}))
                      (let [params (js->clj connection :keywordize-keys true)
                            source-id (:source params)
                            target-id (:target params)]
                        (rf/dispatch [:system/relationship-create
                                      {:source_id source-id
                                       :target_id target-id}])))
                    [demo])

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
                              ;; Track part creation by type
                              (when (js/window.plausible)
                                (js/window.plausible "Part Created" #js {:props #js {:type type :demo demo}}))
                              (rf/dispatch [:system/part-create {:type type}]))]

    (use-effect
     (fn []
       (when system-id
         (js/console.log "[system] starting event queue for system: " system-id)
         (queue/start system-id)
         (fn []
           (js/console.log "[system] stopping event queue")
           (queue/stop))))
     [system-id])

    ($ :div {:class "system-container"}
       ($ :div {:class (if minimal
                         "system-view minimal"
                         "system-view")}
          ($ ReactFlow {:nodes nodes
                        :edges edges
                        :onNodesChange on-nodes-change
                        :onEdgesChange on-edges-change
                        :onConnect on-connect
                        ;; :onSelectionChange on-selection-change
                        :nodeTypes (clj->js node-types)
                        :edgeTypes (clj->js edge-types)
                        :zoomOnScroll (not minimal)
                        :preventScrolling (not minimal)}
             ($ Controls)
             (when-not minimal
               ($ Panel {:position "top-left" :class "logo"}
                  (if demo
                    ($ :a {:href "/"
                           :on-click #(when (js/window.plausible)
                                        (js/window.plausible "Playground Logo Click" #js {:props #js {:demo demo}}))}
                       ($ :svg
                          {:aria-label "Previous",
                           :class "fill-current size-4",
                           :slot "previous",
                           :xmlns "http://www.w3.org/2000/svg",
                           :viewBox "0 0 24 24"}
                          ($ :path {:fill "currentColor", :d "M15.75 19.5 8.25 12l7.5-7.5"}))
                       ($ :img {:src "/images/parts-logo-mini.svg"}))
                    ($ :img {:src "/images/parts-logo-horizontal.svg" :width 150}))))
             ($ Panel {:position (if minimal "top-left" "top-center")
                       :class "toolbar shadow-xs"}
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
             (when-not minimal
               ($ MiniMap {:className "tools parts-minimap shadow-sm"
                           :position "bottom-right"
                           :ariaLabel "Minimap"
                           :pannable true
                           :zoomable true
                           :offsetScale 5}))
             (when-not minimal
               ($ Background {:variant "dots"
                              :gap 12
                              :size 1})))))))
