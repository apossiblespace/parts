(ns aps.parts.frontend.components.map
  (:require
   ["@xyflow/react" :refer [Background Controls MiniMap Panel
                            ReactFlow ReactFlowProvider useReactFlow]]
   ["lucide-react" :refer [ChevronDown ChevronLeft ChevronUp Download
                           FilePenLine Spline]]
   [aps.parts.common.constants :as constants]
   [aps.parts.common.observe :as o]
   [aps.parts.frontend.adapters.reactflow :as adapter]
   [aps.parts.frontend.api.queue :as queue]
   [aps.parts.frontend.components.delete-confirmation-modal :refer [delete-confirmation-modal]]
   [aps.parts.frontend.components.edges :refer [edge-types PartsConnectionLine]]
   [aps.parts.frontend.components.inline-text-field :refer [inline-text-field]]
   [aps.parts.frontend.components.nodes :refer [node-types]]
   [aps.parts.frontend.components.relationship-type-dropdown :refer [close-dropdown! relationship-type-dropdown]]
   [aps.parts.frontend.components.toolbar.button :refer [button]]
   [aps.parts.frontend.components.toolbar.sidebar :refer [sidebar]]
   [aps.parts.frontend.state.toolbar :as toolbar]
   [re-frame.core :as rf]
   [uix.core :refer [$ defui use-callback use-effect use-state]]
   [uix.re-frame :as uix.rf]))

;; Toolbar — the canvas has no modes (ADR-0011); the toolbar only chooses
;; what a creation gesture produces. Two groups with different semantics,
;; deliberately styled differently so two simultaneous highlights read as
;; intended:
;; - `part-tools` are one-shot armed-creation modes (text buttons):
;;   clicking the canvas places a Part of the matching type, then disarms;
;;   shift-click placement keeps the tool armed. Text labels — Part shapes
;;   have their own visual identity in the canvas, no need for tool icons.
;; - the relationship-type control is a persistent type selector: always
;;   exactly one type selected; every drawn edge gets that type.
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
  "True unless the keydown originated inside a form input. Lets Escape
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

(defui relationship-type-control
  "The toolbar's persistent relationship-type selector (ADR-0011): a Spline
   icon (connections as material, not a mode), a colour dot showing the
   current type — the ink the next drawn edge uses — and a caret opening a
   drop-up menu of all types, each a dot + name. The whole control is the
   menu trigger; the caret is affordance, not a separate hit zone."
  []
  (let [selected              (uix.rf/use-subscribe [:ui/relationship-type])
        label                 (get-in constants/relationship-labels [selected :label])
        ;; Picking from the menu blurs the dropdown while the cursor is
        ;; still on the control, which would flash the tooltip back in.
        ;; Suppress it from selection until the pointer leaves.
        [tip-suppressed?
         set-tip-suppressed!] (use-state false)]
    ($ :div {:class          (if tip-suppressed? "" "tooltip tooltip-top")
             :data-tip       (str "Relationship type: " label)
             :on-mouse-leave #(set-tip-suppressed! false)}
       ($ relationship-type-dropdown
          {:selected           selected
           :on-select          (fn [type]
                                 (rf/dispatch [:ui/relationship-type-set type])
                                 (set-tip-suppressed! true))
           :dropdown-class     "dropdown-top"
           :trigger-class      "btn btn-sm bg-white flex items-center gap-1.5"
           :trigger-aria-label (str "Relationship type: " label)}
          ($ Spline {:size 16})
          ($ :span {:class "type-dot"
                    :style {:background-color
                            (constants/relationship-colors selected)}})
          ($ ChevronUp {:size 12})))))

(defui map-name-panel
  "Top-left panel for the authenticated single-map view: a back-to-list
   chevron, the Map's name, and a chevron-down dropdown trigger — all
   rendered as one `join` button group. The dropdown's menu carries
   per-Map actions (Rename, Download as PDF; the Scrubber will join it).

   The Map name is an `inline-text-field`, controlled on `:editing?` — click
   it directly to rename (`:edit-on :click`), or use the Rename menu item,
   which just sets `editing?` true. The commit goes through `:map/rename`
   (bitemporal `map_metadata`, see ADR-0002).

   Not used in the playground, which renders a mini-logo instead."
  []
  (let [title                   (uix.rf/use-subscribe [:map/title])
        map-id                  (uix.rf/use-subscribe [:map/id])
        ;; The caller owns the edit-mode bit; both the title click and the
        ;; Rename menu item flip it true, commit/cancel flip it false.
        [editing? set-editing!] (use-state false)]
    ($ Panel {:position "top-left"}
       ($ :div {:class "flex gap-2"}
          ($ :div {:class "shadow-xs"}
             ($ :a {:href       "/app"
                    :class      "btn btn-sm join-item bg-white"
                    :aria-label "Back to maps"}
                ($ ChevronLeft {:size 16})))
          ($ :div {:class "join shadow-xs"}
             ($ inline-text-field
                {:value         title
                 :aria-label    "Map name"
                 :display-class "btn btn-sm join-item bg-white"
                 :input-class   "input input-sm join-item w-48"
                 :editing?      editing?
                 :edit-on       :click
                 :on-edit-start #(set-editing! true)
                 :on-cancel     #(set-editing! false)
                 :on-commit     (fn [new-title]
                                  (o/track "Map renamed" {})
                                  (rf/dispatch [:map/rename new-title])
                                  (set-editing! false))})
             ($ :div {:class "dropdown"}
                ($ :div {:tabIndex   0
                         :role       "button"
                         :class      "btn btn-sm btn-square join-item bg-white"
                         :aria-label "Map actions"}
                   ($ ChevronDown {:size 16}))
                ($ :ul {:tabIndex 0
                        :class    "dropdown-content menu menu-sm z-10 mt-1 w-44"}
                   ($ :li
                      ($ :a {:on-click (fn []
                                         (set-editing! true)
                                         (close-dropdown!))}
                         ($ FilePenLine {:size 16})
                         "Rename"))
                   ($ :li
                      ;; Native `<a download>` does the work: the browser
                      ;; sends the cookie-authenticated GET, follows the
                      ;; server's `Content-Disposition` for the filename,
                      ;; and opens the Save dialog. Cache-Control on
                      ;; `/render.pdf` is `no-cache`, so an
                      ;; edit-then-redownload flow always sees the fresh
                      ;; PDF via the ETag revalidation.
                      ($ :a {:href     (str "/api/maps/" map-id "/render.pdf")
                             :download ""
                             :on-click (fn []
                                         (o/track "Map PDF downloaded" {})
                                         (close-dropdown!))}
                         ($ Download {:size 16})
                         "Download PDF"))
                   ($ :li ($ :hr))
                   ($ :li
                      ;; Like the PDF above (native `<a download>`), but the full
                      ;; Map history as JSON — the data-subject export (ADR-0010).
                      ;; Used far less than Rename / PDF, so it sits below a
                      ;; separator and drops the icon; the empty w-4 spacer keeps
                      ;; its label aligned with the icon'd items above.
                      ($ :a {:href     (str "/api/maps/" map-id "/export.json")
                             :download ""
                             :on-click (fn []
                                         (o/track "Map data exported" {})
                                         (close-dropdown!))}
                         ($ :span {:class "w-4 shrink-0"})
                         "Export map data")))))))))

(defui save-error-banner
  "Persistent warning shown when a change batch failed and was rolled back
   server-side: the canvas no longer matches the stored Map. Stays up until
   the Map is reloaded — the condition persists, so the banner does too
   (reloading replaces `:map`, which clears the flag)."
  []
  (let [save-error (uix.rf/use-subscribe [:map/save-error])]
    (when save-error
      ;; `flex w-max` overrides the daisyUI alert's full-width grid so the
      ;; banner hugs its content instead of spanning the canvas.
      ($ :div {:class (str "absolute top-4 left-1/2 -translate-x-1/2 z-50 "
                           "alert alert-warning shadow-lg "
                           "flex w-max max-w-2xl items-center gap-3 px-4 py-2")
               :role  "alert"}
         ($ :span {:class "text-sm"}
            "Some recent changes couldn’t be saved — this Map may be out of date.")
         ($ :button {:class    "btn btn-sm whitespace-nowrap"
                     :on-click #(.reload (.-location js/window))}
            "Reload Map")))))

(defui map-canvas []
  (let [demo                  (uix.rf/use-subscribe [:demo])
        minimal               (uix.rf/use-subscribe [:minimal-demo])
        map-id                (uix.rf/use-subscribe [:map/id])
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

                                   :part-resize-frame
                                   (rf/dispatch [:map/part-update-size
                                                 (:id intent)
                                                 (:dimensions intent)])

                                   :part-resized
                                   (do (o/track "Part resized" {:demo demo})
                                       (rf/dispatch [:map/part-finish-size-change
                                                     (:id intent)
                                                     (:dimensions intent)]))

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
                                                    :position_y (.-y pos)}])
                                     (set-tool-mode
                                      (toolbar/tool-mode-after-create
                                       tool-mode (.-shiftKey event))))))
                               [tool-mode rf-instance demo set-tool-mode])]

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
                       (when (and (non-input-target? e)
                                  (= "Escape" (.-key e)))
                         ;; Disarm any armed part tool; ReactFlow itself
                         ;; clears the selection on Escape.
                         (set-tool-mode nil)))]
         (.addEventListener js/document "keydown" handler)
         (fn [] (.removeEventListener js/document "keydown" handler))))
     [set-tool-mode])

    ($ :div {:class "map-container"}
       ($ save-error-banner)
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
                 ;; Authenticated map view: back chevron + editable name.
                 ($ map-name-panel)))
             ($ Panel {:position "bottom-center"
                       :class    "toolbar shadow-xs"}
                ($ :div {:class "flex items-center gap-2"}
                   ($ :div {:class "join"}
                      (map (fn [{:keys [mode label]}]
                             ;; Clicking an armed tool again disarms it.
                             ;; The icon is the Part type's canvas shape —
                             ;; the button previews the stamp it places
                             ;; (stencil-palette pattern). The toolbar/
                             ;; variants are solid-fill: the canvas SVGs'
                             ;; 0.2-opacity fill washes out at 16px.
                             ($ button {:key      (name mode)
                                        :label    label
                                        :icon     ($ :img {:src   (str "/images/nodes/toolbar/"
                                                                       (add-mode->part-type mode)
                                                                       ".svg")
                                                           :alt   ""
                                                           :class "h-4 w-auto"})
                                        :on-click #(set-tool-mode
                                                    (when-not (= tool-mode mode) mode))
                                        :active?  (= tool-mode mode)}))
                           part-tools))
                   ($ relationship-type-control)))
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
