(ns aps.parts.frontend.components.map
  (:require
   ["@xyflow/react" :refer [Background Controls MiniMap Panel
                            ReactFlow ReactFlowProvider useReactFlow]]
   ["lucide-react" :refer [ChevronLeft MousePointer2 Spline]]
   [aps.parts.common.observe :as o]
   [aps.parts.frontend.adapters.reactflow :as adapter]
   [aps.parts.frontend.api.queue :as queue]
   [aps.parts.frontend.components.delete-confirmation-modal :refer [delete-confirmation-modal]]
   [aps.parts.frontend.components.edges :refer [edge-types PartsConnectionLine]]
   [aps.parts.frontend.components.nodes :refer [node-types]]
   [aps.parts.frontend.components.toolbar.button :refer [button]]
   [aps.parts.frontend.components.toolbar.sidebar :refer [sidebar]]
   [re-frame.core :as rf]
   [uix.core :refer [$ defui use-callback use-effect use-state]]
   [uix.re-frame :as uix.rf]))

;; Tool selector — drives the canvas mode. Two groups, rendered as separate
;; `.join` button strips with a small gap between them:
;; - `mode-tools` change *how* the canvas responds (Move / Connect),
;;   shown as icon-only Lucide glyphs with text via tooltip.
;; - `part-tools` are "armed creation" modes; clicking the canvas places a
;;   part of the matching type. Kept as text labels — Part shapes have
;;   their own visual identity in the canvas, no need to add tool icons.
(def ^:private mode-tools
  [{:mode :move :icon MousePointer2 :tooltip "Move"}
   {:mode :connect :icon Spline :tooltip "Connect"}])

(def ^:private part-tools
  [{:mode :add-unknown :label "Unknown"}
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

(defn- plural [n one many] (if (= 1 n) one many))

(defn- delete-prompt
  "Given a non-nil pending-deletes map of `{:parts #{ids} :relationships #{ids}}`,
   return the copy for the confirmation modal as
   `{:title ... :body ... :confirm-label ...}`."
  [{:keys [parts relationships]}]
  (let [part-count (count parts)
        rel-count  (count relationships)]
    (cond
      (and (pos? part-count) (pos? rel-count))
      {:title         "Delete selection?"
       :body          (str part-count " " (plural part-count "part" "parts")
                           " and "
                           rel-count " " (plural rel-count "connection" "connections")
                           " will be removed from the map.")
       :confirm-label "Delete"}

      (pos? part-count)
      {:title         (if (= 1 part-count)
                        "Delete this part?"
                        (str "Delete " part-count " parts?"))
       :body          (plural part-count
                              "It will be removed from the map, along with any connections to it."
                              "They will be removed from the map, along with any connections to them.")
       :confirm-label (plural part-count "Delete part" "Delete parts")}

      :else
      {:title         (if (= 1 rel-count)
                        "Delete this connection?"
                        (str "Delete " rel-count " connections?"))
       :body          (plural rel-count
                              "The link between these parts will be removed from the map."
                              "These links will be removed from the map.")
       :confirm-label (plural rel-count "Delete connection" "Delete connections")})))

(defui map-canvas []
  (let [demo                  (uix.rf/use-subscribe [:demo])
        minimal               (uix.rf/use-subscribe [:minimal-demo])
        map-id                (uix.rf/use-subscribe [:map/id])
        map-title             (uix.rf/use-subscribe [:map/title])
        parts                 (uix.rf/use-subscribe [:map/parts])
        relationships         (uix.rf/use-subscribe [:map/relationships])
        selected-node-ids     (uix.rf/use-subscribe [:ui/selected-node-ids])
        selected-edge-ids     (uix.rf/use-subscribe [:ui/selected-edge-ids])
        tool-mode             (uix.rf/use-subscribe [:ui/tool-mode])
        nodes                 (adapter/parts->nodes parts selected-node-ids)
        edges                 (adapter/relationships->edges relationships selected-edge-ids)
        rf-instance           (useReactFlow)

        set-tool-mode         (use-callback
                               (fn [mode] (rf/dispatch [:ui/tool-mode-set mode]))
                               [])

        [pending-deletes
         set-pending-deletes] (use-state nil)

        queue-delete          (use-callback
                               (fn [entity-key id]
                                 ;; Functional update so concurrent calls from
                                 ;; onNodesChange + onEdgesChange (same Delete
                                 ;; keypress, mixed selection) accumulate
                                 ;; instead of clobbering.
                                 (set-pending-deletes
                                  (fn [prev]
                                    (-> (or prev {:parts #{} :relationships #{}})
                                        (update entity-key conj id)))))
                               [])

        cancel-delete         (use-callback
                               (fn [] (set-pending-deletes nil))
                               [])

        confirm-delete        (use-callback
                               (fn []
                                 (let [{:keys [parts relationships]} pending-deletes]
                                   (doseq [id parts]
                                     (o/track "Part deleted" {:demo demo})
                                     (rf/dispatch [:map/part-remove id]))
                                   (doseq [id relationships]
                                     (o/track "Relationship deleted" {:demo demo})
                                     (rf/dispatch [:map/relationship-remove id]))
                                   (set-pending-deletes nil)))
                               [pending-deletes demo])

        dispatch-intent       (use-callback
                               (fn [intent]
                                 (case (:intent intent)
                                   :part-position-frame
                                   (rf/dispatch [:map/part-update-position
                                                 (:id intent)
                                                 (:position intent)])

                                   :part-moved
                                   (rf/dispatch [:map/part-finish-position-change
                                                 (:id intent)
                                                 (:position intent)])

                                   :part-selected
                                   (rf/dispatch [:selection/toggle-node
                                                 (:id intent)
                                                 (:selected? intent)])

                                   :part-removed
                                   (queue-delete :parts (:id intent))

                                   :relationship-selected
                                   (rf/dispatch [:selection/toggle-edge
                                                 (:id intent)
                                                 (:selected? intent)])

                                   :relationship-removed
                                   (queue-delete :relationships (:id intent))

                                   :relationship-connected
                                   (do (o/track "Relationship created" {:demo demo})
                                       (rf/dispatch [:map/relationship-create
                                                     (select-keys intent [:source_id :target_id])]))

                                   (o/warn "map.dispatch-intent" "unknown intent" intent)))
                               [demo queue-delete])

        on-nodes-change       (use-callback
                               (fn [changes]
                                 (o/debug "map.on-nodes-change" "nodes changed" changes)
                                 (run! dispatch-intent
                                       (adapter/translate-nodes-change changes)))
                               [dispatch-intent])

        on-edges-change       (use-callback
                               (fn [changes]
                                 (o/debug "map.on-edges-change" "edges changed" changes)
                                 (run! dispatch-intent
                                       (adapter/translate-edges-change changes)))
                               [dispatch-intent])

        on-connect            (use-callback
                               (fn [connection]
                                 (o/debug "map.on-connect" "connection created" connection)
                                 (dispatch-intent (adapter/translate-connect connection)))
                               [dispatch-intent])

        on-pane-click         (use-callback
                               (fn [^js event]
                                 (when-let [part-type (add-mode->part-type tool-mode)]
                                   (let [pos (.screenToFlowPosition
                                              rf-instance
                                              #js {:x (.-clientX event)
                                                   :y (.-clientY event)})]
                                     (o/track "Part created" {:type part-type :demo demo})
                                     (rf/dispatch [:map/part-create
                                                   {:type       part-type
                                                    :position_x (.-x pos)
                                                    :position_y (.-y pos)}]))))
                               [tool-mode rf-instance demo])]

    (use-effect
     (fn []
       (when map-id
         (o/info "map.lifecycle" "starting event queue for map" map-id)
         (queue/start map-id)
         (fn []
           (o/info "map.lifecycle" "stopping event queue")
           (queue/stop))))
     [map-id])

    (use-effect
     (fn []
       (let [handler (fn [^js e]
                       (when (non-input-target? e)
                         (case (.-key e)
                           ("v" "V" "Escape") (set-tool-mode :move)
                           ("c" "C")          (set-tool-mode :connect)
                           nil)))]
         (.addEventListener js/document "keydown" handler)
         (fn [] (.removeEventListener js/document "keydown" handler))))
     [set-tool-mode])

    ($ :div {:class "map-container"}
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
       ($ :div {:class (cond-> "map-view"
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
                        :nodesDraggable          (= tool-mode :move)
                        :zoomOnScroll            (not minimal)
                        :preventScrolling        (not minimal)}
             ($ Controls)
             (when-not minimal
               (if demo
                 ;; Playground: mini logo linking back to the marketing site.
                 ($ Panel {:position "top-left" :class "logo"}
                    ($ :a {:href     "/"
                           :on-click #(o/track "Playground logo click" {:demo demo})}
                       ($ :svg
                          {:aria-label "Previous",
                           :class      "fill-current size-4",
                           :slot       "previous",
                           :xmlns      "http://www.w3.org/2000/svg",
                           :viewBox    "0 0 24 24"}
                          ($ :path {:fill "currentColor", :d "M15.75 19.5 8.25 12l7.5-7.5"}))
                       ($ :img {:src "/images/parts-logo-mini.svg"})))
                 ;; Authenticated map view: back-to-list chevron + map name,
                 ;; styled as a button group to match the part-creation tools.
                 ($ Panel {:position "top-left"}
                    ($ :div {:class "join shadow-xs"}
                       ($ :a {:href       "/app"
                              :class      "btn btn-sm join-item bg-white"
                              :aria-label "Back to maps"}
                          ($ ChevronLeft {:size 16}))
                       ($ :span {:class "btn btn-sm join-item bg-white pointer-events-none"}
                          map-title)))))
             ($ Panel {:position (if minimal "top-left" "top-center")
                       :class    "toolbar shadow-xs"}
                ($ :div {:class "flex gap-2"}
                   (for [group [mode-tools part-tools]]
                     ($ :div {:key   (-> group first :mode name)
                              :class "join"}
                        (map (fn [{:keys [mode label icon tooltip]}]
                               ($ button {:key      (name mode)
                                          :label    label
                                          :icon     (when icon ($ icon {:size 16}))
                                          :tooltip  tooltip
                                          :on-click #(set-tool-mode mode)
                                          :active?  (= tool-mode mode)}))
                             group)))))
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
                              :size    1}))))
       (let [{:keys [title body confirm-label]} (when pending-deletes
                                                  (delete-prompt pending-deletes))]
         ($ delete-confirmation-modal
            {:show          (some? pending-deletes)
             :title         title
             :body          body
             :confirm-label confirm-label
             :on-confirm    confirm-delete
             :on-close      cancel-delete})))))

(defui map-view []
  ($ ReactFlowProvider
     ($ map-canvas)))
