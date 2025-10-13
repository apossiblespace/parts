(ns aps.parts.frontend.components.system
  (:require
   ["@xyflow/react" :refer [Background Controls MiniMap Panel ReactFlow]]
   [aps.parts.frontend.observe :as o]
   [aps.parts.frontend.adapters.reactflow :as adapter]
   [aps.parts.frontend.api.queue :as queue]
   [aps.parts.frontend.components.edges :refer [edge-types]]
   [aps.parts.frontend.components.nodes :refer [node-types]]
   [aps.parts.frontend.components.toolbar.button :refer [button]]
   [aps.parts.frontend.components.toolbar.sidebar :refer [sidebar]]
   [re-frame.core :as rf]
   [uix.core :refer [$ defui use-callback use-effect]]
   [uix.re-frame :as uix.rf]))

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
                           (o/debug "system.on-nodes-change" "nodes changed" changes)
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
                                                     (o/track "Part deleted" {:demo demo})
                                                     (rf/dispatch [:system/part-remove
                                                                   (:id change)]))
                                          (o/warn "system.on-nodes-change" "unhandled change type" change)))))) [demo])

        on-edges-change (use-callback
                         (fn [changes]
                           (o/debug "system.on-edges-change" "edges changed" changes)
                           (->> (js->clj changes :keywordize-keys true)
                                (run! (fn [change]
                                        (case (:type change)
                                          "select" (rf/dispatch [:selection/toggle-edge
                                                                 (:id change)
                                                                 (:selected change)])
                                          "remove" (do
                                                     (o/track "Relationship deleted" {:demo demo})
                                                     (rf/dispatch [:system/relationship-remove
                                                                   (:id change)]))
                                          (o/warn "system.on-edges-change" "unhandled change type" change)))))) [demo])

        on-connect (use-callback
                    (fn [connection]
                      (o/debug "system.on-connect" "connection created" connection)
                      (o/track "Relationship created" {:demo demo})
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
        ;; on-selection-change (use-callback
        ;;                      (fn [selection]
        ;;                        (o/debug "system.on-selection-change" "selection changed" selection)
        ;;                        (let [sel (js->clj selection :keywordize-keys true)]
        ;;                          (rf/dispatch [:selection/set sel])))
        ;;                      [])

        create-part-by-type (fn [type]
                              (o/debug "system.create-part-by-type" "creating part" type)
                              (o/track "Part created" {:type type :demo demo})
                              (rf/dispatch [:system/part-create {:type type}]))]

    (use-effect
     (fn []
       (when system-id
         (o/info "system.lifecycle" "starting event queue for system" system-id)
         (queue/start system-id)
         (fn []
           (o/info "system.lifecycle" "stopping event queue")
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
                           :on-click #(o/track "Playground logo click" {:demo demo})}
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
