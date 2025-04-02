(ns parts.frontend.components.system
  (:require
   ["@xyflow/react" :refer [Background Controls MiniMap Panel ReactFlow]]
   [parts.frontend.api.queue :as queue]
   [parts.frontend.state.hook :refer [use-system-state]]
   [parts.frontend.components.edges :refer [edge-types]]
   [parts.frontend.components.nodes :refer [node-types]]
   [parts.frontend.components.toolbar.button :refer [button]]
   [parts.frontend.components.toolbar.sidebar :refer [sidebar]]
   [parts.frontend.context :as ctx]
   [uix.core :refer [$ defui use-effect use-state]]))

(defui system [system-data]
  (let [{:keys [nodes
                edges
                add-part
                update-part
                remove-part
                update-part-position
                add-relationship
                update-relationship
                remove-relationship]} (use-system-state system-data)

        [selected-nodes set-selected-nodes] (use-state nil)
        [selected-edges set-selected-edges] (use-state nil)

        ;; FIXME: Here, we are calling the whole state machinery too often: on
        ;; each drag event we basically regenerate the state and re-render the
        ;; system component. This is what causes the flickering.
        ;;
        ;; We need to do what we are already doing for queuing the events --
        ;; only update the state when dragging has stopped.
        ;;
        ;; While dragging is still happening, we can use the react-flow
        ;; callbacks to update just the visual representation, and don't need to
        ;; access the state at all.
        on-nodes-change (fn [changes]
                          (println "[on-node-change]" changes)
                          (->> (js->clj changes :keywordize-keys true)
                               (run! (fn [change]
                                       (case (:type change)
                                         "position" (when (:position change)
                                                      (update-part-position (:id change) (:position change) (:dragging change)))
                                         "remove" (remove-part (:id change))
                                         nil)))))

        on-edges-change (fn [changes]
                          (->> (js->clj changes :keywordize-keys true)
                               (run! (fn [change]
                                       (when (= "remove" (:type change))
                                         (remove-relationship (:id change)))))))

        on-connect (fn [connection]
                     (let [{:keys [source target]} (js->clj connection :keywordize-keys true)]
                       (add-relationship {:source_id source
                                          :target_id target
                                          :type "unknown"})))

        ;; FIXME: This is not working for some reason, selection does not
        ;; contain any nodes.
        on-selection-change (fn [selection]
                              (println "[on-selection-change]" selection)
                              (let [sel (js->clj selection :keywordize-keys true)]
                                (set-selected-nodes (:nodes sel))
                                (set-selected-edges (:edges sel))))

        create-part-by-type (fn [type]
                              (add-part {:type type
                                         :position_x 390
                                         :position_y 290}))]

    (use-effect
     (fn []
       (println "[system] starting event queue")
       (queue/start)
       (fn []
         (println "[system] stopping event queue")
         (queue/stop)))
     [])

    ($ (.-Provider ctx/update-system-context)
       {:value {:update-node update-part :update-edge update-relationship}}
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
