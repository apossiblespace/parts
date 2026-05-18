(ns aps.parts.frontend.components.system
  (:require
   ["@xyflow/react" :refer [Background Controls MiniMap Panel
                            ReactFlow ReactFlowProvider useReactFlow]]
   [aps.parts.common.observe :as o]
   [aps.parts.frontend.adapters.reactflow :as adapter]
   [aps.parts.frontend.api.queue :as queue]
   [aps.parts.frontend.components.edges :refer [edge-types PartsConnectionLine]]
   [aps.parts.frontend.components.nodes :refer [node-types]]
   [aps.parts.frontend.components.toolbar.button :refer [button]]
   [aps.parts.frontend.components.toolbar.sidebar :refer [sidebar]]
   [re-frame.core :as rf]
   [uix.core :refer [$ defui use-callback use-effect]]
   [uix.re-frame :as uix.rf]))

;; Tool selector — drives the canvas mode. The order here is the toolbar's
;; left-to-right rendering order. :select and :connect are interaction modes;
;; the :add-* entries are "armed creation" modes (click on the canvas places
;; a part of the matching type).
(def ^:private tools
  [{:mode :select :label "Select"}
   {:mode :connect :label "Connect"}
   {:mode :add-unknown :label "Unknown"}
   {:mode :add-exile :label "Exile"}
   {:mode :add-firefighter :label "Firefighter"}
   {:mode :add-manager :label "Manager"}])

(def ^:private add-mode->part-type
  {:add-unknown     "unknown"
   :add-exile       "exile"
   :add-firefighter "firefighter"
   :add-manager     "manager"})

(defn- non-input-target?
  "True unless the keydown originated inside a form input. Lets V/C/Esc
   not steal keystrokes from the sidebar's relationship/notes editors."
  [^js event]
  (let [tag (.. event -target -tagName)]
    (not (or (= "INPUT" tag) (= "TEXTAREA" tag)))))

(defui system-canvas []
  (let [demo              (uix.rf/use-subscribe [:demo])
        minimal           (uix.rf/use-subscribe [:minimal-demo])
        system-id         (uix.rf/use-subscribe [:system/id])
        parts             (uix.rf/use-subscribe [:system/parts])
        relationships     (uix.rf/use-subscribe [:system/relationships])
        selected-node-ids (uix.rf/use-subscribe [:ui/selected-node-ids])
        selected-edge-ids (uix.rf/use-subscribe [:ui/selected-edge-ids])
        tool-mode         (uix.rf/use-subscribe [:ui/tool-mode])
        nodes             (adapter/parts->nodes parts selected-node-ids)
        edges             (adapter/relationships->edges relationships selected-edge-ids)
        rf-instance       (useReactFlow)

        set-tool-mode     (use-callback
                           (fn [mode] (rf/dispatch [:ui/tool-mode-set mode]))
                           [])

        dispatch-intent   (use-callback
                           (fn [intent]
                             (case (:intent intent)
                               :part-position-frame
                               (rf/dispatch [:system/part-update-position
                                             (:id intent)
                                             (:position intent)])

                               :part-moved
                               (rf/dispatch [:system/part-finish-position-change
                                             (:id intent)
                                             (:position intent)])

                               :part-selected
                               (rf/dispatch [:selection/toggle-node
                                             (:id intent)
                                             (:selected? intent)])

                               :part-removed
                               (do (o/track "Part deleted" {:demo demo})
                                   (rf/dispatch [:system/part-remove (:id intent)]))

                               :relationship-selected
                               (rf/dispatch [:selection/toggle-edge
                                             (:id intent)
                                             (:selected? intent)])

                               :relationship-removed
                               (do (o/track "Relationship deleted" {:demo demo})
                                   (rf/dispatch [:system/relationship-remove (:id intent)]))

                               :relationship-connected
                               (do (o/track "Relationship created" {:demo demo})
                                   (rf/dispatch [:system/relationship-create
                                                 (select-keys intent [:source_id :target_id])]))

                               (o/warn "system.dispatch-intent" "unknown intent" intent)))
                           [demo])

        on-nodes-change   (use-callback
                           (fn [changes]
                             (o/debug "system.on-nodes-change" "nodes changed" changes)
                             (run! dispatch-intent
                                   (adapter/translate-nodes-change changes)))
                           [dispatch-intent])

        on-edges-change   (use-callback
                           (fn [changes]
                             (o/debug "system.on-edges-change" "edges changed" changes)
                             (run! dispatch-intent
                                   (adapter/translate-edges-change changes)))
                           [dispatch-intent])

        on-connect        (use-callback
                           (fn [connection]
                             (o/debug "system.on-connect" "connection created" connection)
                             (dispatch-intent (adapter/translate-connect connection)))
                           [dispatch-intent])

        on-pane-click     (use-callback
                           (fn [^js event]
                             (when-let [part-type (add-mode->part-type tool-mode)]
                               (let [pos (.screenToFlowPosition
                                          rf-instance
                                          #js {:x (.-clientX event)
                                               :y (.-clientY event)})]
                                 (o/track "Part created" {:type part-type :demo demo})
                                 (rf/dispatch [:system/part-create
                                               {:type       part-type
                                                :position_x (.-x pos)
                                                :position_y (.-y pos)}]))))
                           [tool-mode rf-instance demo])]

    (use-effect
     (fn []
       (when system-id
         (o/info "system.lifecycle" "starting event queue for system" system-id)
         (queue/start system-id)
         (fn []
           (o/info "system.lifecycle" "stopping event queue")
           (queue/stop))))
     [system-id])

    (use-effect
     (fn []
       (let [handler (fn [^js e]
                       (when (non-input-target? e)
                         (case (.-key e)
                           ("v" "V" "Escape") (set-tool-mode :select)
                           ("c" "C")          (set-tool-mode :connect)
                           nil)))]
         (.addEventListener js/document "keydown" handler)
         (fn [] (.removeEventListener js/document "keydown" handler))))
     [set-tool-mode])

    ($ :div {:class "system-container"}
       ;; Single SVG marker definition for every edge arrowhead.
       ;; fill="context-stroke" makes the marker fill inherit the
       ;; referencing path's stroke colour — so the .edge-<type> CSS
       ;; rules drive both the line and the arrowhead.
       ($ :svg {:width  0
                :height 0
                :style  #js {:position "absolute"}}
          ($ :defs
             ($ :marker {:id           "edge-arrow"
                         :viewBox      "0 0 10 10"
                         :refX         9
                         :refY         5
                         :markerUnits  "strokeWidth"
                         :markerWidth  6
                         :markerHeight 6
                         :orient       "auto-start-reverse"}
                ($ :path {:d    "M 0 0 L 10 5 L 0 10 z"
                          :fill "context-stroke"}))))
       ($ :div {:class (cond-> "system-view"
                         minimal   (str " minimal")
                         tool-mode (str " mode-" (name tool-mode)))}
          ($ ReactFlow {:nodes                   nodes
                        :edges                   edges
                        :onNodesChange           on-nodes-change
                        :onEdgesChange           on-edges-change
                        :onConnect               on-connect
                        :onPaneClick             on-pane-click
                        ;; :onSelectionChange on-selection-change
                        :nodeTypes               node-types
                        :edgeTypes               edge-types
                        :connectionLineComponent PartsConnectionLine
                        :nodesDraggable          (= tool-mode :select)
                        :zoomOnScroll            (not minimal)
                        :preventScrolling        (not minimal)}
             ($ Controls)
             (when-not minimal
               ($ Panel {:position "top-left" :class "logo"}
                  (if demo
                    ($ :a {:href     "/"
                           :on-click #(o/track "Playground logo click" {:demo demo})}
                       ($ :svg
                          {:aria-label "Previous",
                           :class      "fill-current size-4",
                           :slot       "previous",
                           :xmlns      "http://www.w3.org/2000/svg",
                           :viewBox    "0 0 24 24"}
                          ($ :path {:fill "currentColor", :d "M15.75 19.5 8.25 12l7.5-7.5"}))
                       ($ :img {:src "/images/parts-logo-mini.svg"}))
                    ($ :img {:src "/images/parts-logo-horizontal.svg" :width 150}))))
             ($ Panel {:position (if minimal "top-left" "top-center")
                       :class    "toolbar shadow-xs"}
                ($ :div {:class "join"}
                   (map (fn [{:keys [mode label]}]
                          ($ button {:key      (name mode)
                                     :label    label
                                     :on-click #(set-tool-mode mode)
                                     :active?  (= tool-mode mode)}))
                        tools)))
             ($ Panel {:position "top-right" :className "sidebar-container"}
                ($ sidebar))
             (when-not minimal
               ($ MiniMap {:className   "tools parts-minimap shadow-sm"
                           :position    "bottom-right"
                           :ariaLabel   "Minimap"
                           :pannable    true
                           :zoomable    true
                           :offsetScale 5}))
             (when-not minimal
               ($ Background {:variant "dots"
                              :gap     12
                              :size    1})))))))

(defui system []
  ($ ReactFlowProvider
     ($ system-canvas)))
