(ns aps.parts.frontend.components.map
  (:require
   ["@xyflow/react" :refer [Background Controls MiniMap Panel
                            ReactFlow ReactFlowProvider useReactFlow]]
   ["lucide-react" :refer [ChevronDown ChevronLeft ChevronUp Download
                           FilePenLine Hand History MousePointer2 Plus
                           Spline Undo2]]
   [aps.parts.common.constants :as constants]
   [aps.parts.common.geometry :as geometry]
   [aps.parts.common.observe :as o]
   [aps.parts.frontend.adapters.reactflow :as adapter]
   [aps.parts.frontend.api.queue :as queue]
   [aps.parts.frontend.components.delete-confirmation-modal :refer [delete-confirmation-modal]]
   [aps.parts.frontend.components.edges :refer [edge-types PartsConnectionLine]]
   [aps.parts.frontend.components.inline-text-field :refer [inline-text-field]]
   [aps.parts.frontend.components.nodes :refer [node-types]]
   [aps.parts.frontend.components.relationship-type-dropdown :refer [close-dropdown! relationship-type-dropdown]]
   [aps.parts.frontend.components.time-travel-bar :refer [time-travel-bar]]
   [aps.parts.frontend.components.toolbar.button :refer [button]]
   [aps.parts.frontend.components.toolbar.sidebar :refer [sidebar]]
   [aps.parts.frontend.dates :as dates]
   [aps.parts.frontend.state.time-travel :as time-travel]
   [aps.parts.frontend.state.toolbar :as toolbar]
   [re-frame.core :as rf]
   [uix.core :refer [$ defui use-callback use-effect use-ref use-state]]
   [uix.re-frame :as uix.rf]))

(def ^:private session-glide-ms
  "Duration of the Time-travel session-switch glide."
  250)

(defn- ease-out-cubic [t]
  (- 1 (js/Math.pow (- 1 t) 3)))

;; Tool palette — the active tool decides what a drag means (ADR-0015).
;; Three groups with different semantics, deliberately styled differently
;; so simultaneous highlights read as intended:
;; - `mode-tools` are the persistent tools: Select (default — click/marquee
;;   selects, drag moves) and Hand (drag pans). Exactly one is active
;;   whenever no creation tool is armed.
;; - `part-tools` are one-shot armed-creation modes (text buttons):
;;   clicking the canvas places a Part of the matching type, then springs
;;   back to Select; shift-click placement keeps the tool armed. Text
;;   labels — Part shapes have their own visual identity in the canvas.
;; - the Connect split button pairs the one-shot Connect tool with the
;;   relationship-type control — a persistent type selector: always
;;   exactly one type selected; every drawn edge gets that type.
(def ^:private mode-tools
  [{:mode :select :label "Select" :shortcut "V" :icon MousePointer2}
   {:mode :hand :label "Hand" :shortcut "H" :icon Hand}])

(defn- indefinite-article [word]
  (if (re-find #"(?i)^[aeiou]" word) "an" "a"))

;; NOTE: these labels' rendered width feeds the `part-label-class`
;; threshold — adding or renaming a type means re-measuring that stage.
(def ^:private part-tools
  (mapv (fn [{:keys [label] :as tool}]
          (assoc tool :create-label
                 (str "Create " (indefinite-article label) " " label " part")))
        [{:mode :add-unknown :label "Unknown"}
         {:mode :add-exile :label "Exile"}
         {:mode :add-firefighter :label "Firefighter"}
         {:mode :add-manager :label "Manager"}]))

(def ^:private add-mode->part-type
  {:add-unknown     "unknown"
   :add-exile       "exile"
   :add-firefighter "firefighter"
   :add-manager     "manager"})

;; Stable JS identities for ReactFlow props. A fresh #js array per render
;; busts ReactFlow's memoized renderer — re-registering its key listeners
;; and reconfiguring d3-zoom on every drag frame.
(def ^:private middle-mouse-pan-buttons #js [1])
(def ^:private multi-selection-key-codes #js ["Meta" "Shift"])

(defn- non-input-target?
  "True unless the keydown originated inside a form input. Keeps the tool
   shortcuts (V/H/Escape) from stealing keystrokes while typing in the
   sidebar's relationship/notes editors or an inline label."
  [^js event]
  (let [tag (.. event -target -tagName)]
    (not (or (= "INPUT" tag) (= "TEXTAREA" tag)))))

(defn- plural [n one many] (if (= 1 n) one many))

(defn- event->flow-position
  "The pointer event's position in Map (flow) coordinates."
  [^js rf-instance ^js event]
  (let [p (.screenToFlowPosition rf-instance
                                 #js {:x (.-clientX event)
                                      :y (.-clientY event)})]
    {:x (.-x p) :y (.-y p)}))

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
  "The right half of the Connect split button: a colour dot showing the
   persistent relationship type — the ink the next drawn edge uses — and
   a caret opening a drop-up menu of all types, each a dot + name. The
   whole segment is the menu trigger; the caret is affordance, not a
   separate hit zone. The Connect half carries the icon and label, so
   this half is just the ink."
  []
  (let [selected              (uix.rf/use-subscribe [:ui/relationship-type])
        label                 (get-in constants/relationship-labels [selected :label])
        ;; Picking from the menu blurs the dropdown while the cursor is
        ;; still on the control, which would flash the tooltip back in.
        ;; Suppress it from selection until the pointer leaves.
        [tip-suppressed?
         set-tip-suppressed!] (use-state false)]
    ;; Direct child of the join, tooltip riding on the same element —
    ;; wrapper levels break the join's stretch and seam collapse (see
    ;; the .toolbar .join rules in main.css). -ml-px overlaps the seam
    ;; borders: daisyUI's join-item margin rule can't see an item that
    ;; is the first child of its wrapper.
    ($ relationship-type-dropdown
       {:selected           selected
        :on-select          (fn [type]
                              (rf/dispatch [:ui/relationship-type-set type])
                              (set-tip-suppressed! true))
        :dropdown-class     (str "dropdown-top -ml-px"
                                 (when-not tip-suppressed?
                                   " tooltip tooltip-top"))
        :data-tip           (str "Relationship type: " label)
        :on-mouse-leave     #(set-tip-suppressed! false)
        :trigger-class      "btn btn-sm join-item bg-base-100 flex items-center gap-1.5"
        :trigger-aria-label (str "Relationship type: " label)}
       ($ :span {:class "type-dot"
                 :style {:background-color
                         (constants/relationship-colors selected)}})
       ($ ChevronUp {:size 12}))))

;; Narrow-window degradation of the chrome. The Map name is the only
;; variable-width element and shrinks continuously as a flex item — no
;; breakpoint can be right for content of unknown length. Everything
;; else is constant-width, so these discrete stages are sound viewport
;; thresholds. Each carries ~25-30px over its measured fit limit: font
;; rendering varies by platform, and a stage firing a hair early is
;; invisible while one firing late overlaps the minimap.
(def ^:private part-label-class "max-[870px]:hidden")
(def ^:private history-label-class "max-[680px]:hidden")
(def ^:private connect-label-class "max-[450px]:hidden")
(def ^:private minimap-hidden-class "max-[650px]:hidden")
;; The right spacer's min-width is the minimap's 200px + spacing; it
;; collapses at the same 650px stage the minimap hides at.
(def ^:private minimap-reserve-class "flex-1 min-w-[210px] max-[650px]:min-w-2")

;; `shrink` overrides the flex-shrink:0 daisyUI puts on buttons —
;; without it the whole truncation chain is moot.
(def ^:private map-name-width-classes "shrink min-w-16 max-w-96")
(def ^:private map-name-text-class "truncate min-w-0")

(def ^:private save-status-copy
  {:saved  "All changes saved"
   :dirty  "Unsaved changes"
   :saving "Saving…"
   :error  "Saving failed — changes may not be stored"})

(def ^:private spinner-min-ms
  "A batch POST lands in tens of milliseconds; a spinner that flashes
   for one frame is jarring, so once shown it stays up at least this
   long (display smoothing only — the underlying status stays honest)."
  500)

(defui save-status-indicator
  "The app's single save-feedback surface (silent autosave — see
   CONTEXT.md): green at rest, yellow while changes wait in the queue's
   debounce window, a spinner while a request is in flight, red after a
   failure. The slot is fixed-width so state changes never shift the row."
  []
  (let [status             (uix.rf/use-subscribe [:map/save-status])
        [shown set-shown!] (use-state status)
        spinner-shown-at   (use-ref 0)]
    (use-effect
     (fn []
       (if (= :saving status)
         (do (reset! spinner-shown-at (js/Date.now))
             (set-shown! :saving)
             js/undefined)
         ;; `remaining` is only positive within the hold window, so no
         ;; explicit was-the-spinner-shown check is needed.
         (let [remaining (- spinner-min-ms
                            (- (js/Date.now) @spinner-shown-at))]
           (if (pos? remaining)
             (let [timer (js/setTimeout #(set-shown! status) remaining)]
               #(js/clearTimeout timer))
             (do (set-shown! status)
                 js/undefined)))))
     [status])
    ;; macOS proxy-icon position; the fixed 16px slot keeps the
    ;; dot/spinner swap from shifting the title.
    ($ :span {:class      (str "w-3 shrink-0 flex items-center justify-center "
                               "tooltip tooltip-bottom")
              :data-tip   (save-status-copy shown)
              :aria-label (save-status-copy shown)
              :role       "status"
              ;; The name around it is click-to-rename; the dot is not.
              :on-click   (fn [^js e] (.stopPropagation e))}
       (if (= :saving shown)
         ($ :span {:class "loading loading-spinner loading-xs text-base-content/40"})
         ($ :span {:class (str "status status-md "
                               (case shown
                                 :dirty "status-warning"
                                 :error "status-error"
                                 "status-success"))})))))

(defui map-name-widgets
  "Left group of the top chrome row for the authenticated single-map view:
   a back-to-list chevron, the Map's name, and a chevron-down dropdown
   trigger — all rendered as one `join` button group. The dropdown's menu
   carries per-Map actions (Rename, Download as PDF; the Scrubber will
   join it).

   The Map name is an `inline-text-field`, controlled on `:editing?` — click
   it directly to rename (`:edit-on :click`), or use the Rename menu item,
   which just sets `editing?` true. The commit goes through `:map/rename`
   (bitemporal `map_metadata`, see ADR-0002).

   Not used in the playground, which renders a mini-logo instead."
  []
  (let [title                   (uix.rf/use-subscribe [:map/title])
        map-id                  (uix.rf/use-subscribe [:map/id])
        the-sessions            (uix.rf/use-subscribe [:map/sessions])
        active-session          (uix.rf/use-subscribe [:session/active])
        undoable?               (uix.rf/use-subscribe [:session/undoable?])
        time-travelling?        (uix.rf/use-subscribe [:time-travel/active?])
        ;; The caller owns the edit-mode bit; both the title click and the
        ;; Rename menu item flip it true, commit/cancel flip it false.
        [editing? set-editing!] (use-state false)]
    ($ :div {:class "flex gap-2 min-w-0 chrome-item"}
       ($ :div {:class "shadow-xs shrink-0"}
          ($ :a {:href       "/app"
                 :class      "btn btn-sm join-item bg-base-100"
                 :aria-label "Back to maps"}
             ($ ChevronLeft {:size 16})))
       (if time-travelling?
         ;; Read-only while viewing the past: the name is a plain
         ;; label — no rename, no actions menu (the viewed-Session
         ;; PDF gets its own affordance later).
         ;; The wrapper must be flex: a plain block shrinks while its
         ;; .btn child (a non-item) keeps its width and overflows.
         ($ :div {:class "shadow-xs min-w-0 flex"}
            ($ :div {:class (str "btn btn-sm bg-base-100 gap-1.5 cursor-default "
                                 map-name-width-classes)}
               ;; The indicator keeps its hover tooltip; only the label
               ;; text is click-inert in the mode.
               ($ save-status-indicator)
               ($ :span {:class (str map-name-text-class " pointer-events-none")}
                  title)))
         ($ :div {:class "join shadow-xs min-w-0"}
            ($ inline-text-field
               {:value              title
                :aria-label         "Map name"
                :display-class      (str "btn btn-sm join-item bg-base-100 gap-1.5 "
                                         map-name-width-classes)
                :display-text-class map-name-text-class
                :display-prefix     ($ save-status-indicator)
                :input-class        "input input-sm join-item w-48"
                :editing?           editing?
                :edit-on            :click
                :on-edit-start      #(set-editing! true)
                :on-cancel          #(set-editing! false)
                :on-commit          (fn [new-title]
                                      (o/track "Map renamed" {})
                                      (rf/dispatch [:map/rename new-title])
                                      (set-editing! false))})
            (when active-session
              ($ :div {:class (str "btn btn-sm join-item bg-base-100 "
                                   "pointer-events-none")}
                 (str "Session " (:ordinal active-session)
                      (when-let [d (dates/format-date
                                    dates/short-date-format
                                    (:anchor_valid_at active-session))]
                        (str " · " d)))))
            ;; -ml-px: join seam fix — see relationship-type-control.
            ;; dropdown-bottom is explicit because the join forces
            ;; display:flex on dropdowns (alignment), and daisyUI's
            ;; default placement relies on static flow — in a flex
            ;; container that would put the menu BESIDE the trigger.
            ($ :div {:class "dropdown dropdown-bottom -ml-px"}
               ($ :div {:tabIndex   0
                        :role       "button"
                        :class      "btn btn-sm btn-square join-item bg-base-100"
                        :aria-label "Map actions"}
                  ($ ChevronDown {:size 16}))
               ($ :ul {:tabIndex 0
                       :class    "dropdown-content menu menu-sm z-10 mt-1 w-44"}
                  ;; Session actions lead — starting a session is the
                  ;; most common item in this menu.
                  (when active-session
                    ($ :<>
                       ($ :li
                          ($ :a {:on-click (fn []
                                             (o/track "Session started" {})
                                             (rf/dispatch [:session/start])
                                             (close-dropdown!))}
                             ($ Plus {:size 16})
                             "Start new session"))
                       (when undoable?
                         ($ :li
                            ($ :a {:on-click (fn []
                                               (o/track "Session undone" {})
                                               (rf/dispatch [:session/delete
                                                             (:id active-session)])
                                               (close-dropdown!))}
                               ($ Undo2 {:size 16})
                               "Undo new session")))
                       ($ :li ($ :hr))))
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
                        "Export map data"))))))
       (when (time-travel/has-history? the-sessions)
         (let [toggle-label (if time-travelling?
                              "Back to editing"
                              "Session history")]
           ($ :div {:class "shadow-xs shrink-0"}
              ($ :button {:class        (str "btn btn-sm "
                                             (if time-travelling?
                                               "btn-primary"
                                               "bg-base-100")
                                             " flex items-center gap-1.5"
                                             " tooltip tooltip-bottom")
                          :data-tip     (str toggle-label " — T")
                          :aria-label   toggle-label
                          :aria-pressed (boolean time-travelling?)
                          :on-click     (fn []
                                          (if time-travelling?
                                            (do (o/track "Time travel exited" {})
                                                (rf/dispatch [:time-travel/exit]))
                                            (do (o/track "Time travel entered" {})
                                                (rf/dispatch [:time-travel/enter]))))}
                 ($ History {:size 16})
                 ($ :span {:class history-label-class} "History"))))))))

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
        parts                 (uix.rf/use-subscribe [:canvas/parts])
        relationships         (uix.rf/use-subscribe [:canvas/relationships])
        viewed-ordinal        (uix.rf/use-subscribe [:canvas/viewed-ordinal])
        session-badges?       (uix.rf/use-subscribe [:canvas/session-badges?])
        time-travelling?      (uix.rf/use-subscribe [:time-travel/active?])
        selected-node-ids     (uix.rf/use-subscribe [:ui/selected-node-ids])
        selected-edge-ids     (uix.rf/use-subscribe [:ui/selected-edge-ids])
        tool-mode             (uix.rf/use-subscribe [:ui/tool-mode])
        ;; The read-only seam (ADR-0014): false until the Map's Sessions
        ;; load and one is active; demo Maps are always true.
        editable?             (uix.rf/use-subscribe [:canvas/editable?])

        ;; Render-only mirror of the marquee buffer below: nil when no
        ;; marquee is active. React state (not re-frame) so each update
        ;; flushes before the next mousemove — the live highlight stays
        ;; in lockstep with ReactFlow's select emissions.
        [marquee-overlay
         set-marquee-overlay] (use-state nil)

        ;; Session-switch glide: tween the Parts that persist across the
        ;; switch (`time-travel/interpolate-parts`). Local React state,
        ;; not re-frame — per-frame updates stay out of app-db (same
        ;; reasoning as the marquee overlay); the ref is the synchronous
        ;; read for a tween starting mid-flight. Editing mode bypasses
        ;; this state entirely: drag frames must not tween, nor pay a
        ;; second render.
        [glide-parts
         set-glide-parts]     (use-state nil)
        glide-parts-ref       (use-ref nil)
        glide-raf             (use-ref nil)
        fitted-map-id         (use-ref nil)
        shown-parts           (if (and time-travelling? glide-parts)
                                glide-parts
                                parts)

        nodes                 (adapter/parts->nodes
                               shown-parts
                               (if marquee-overlay
                                 (toolbar/marquee-preview-ids
                                  selected-node-ids (:parts marquee-overlay))
                                 selected-node-ids)
                               {:resizable?      (toolbar/resize-armed?
                                                  tool-mode
                                                  (count selected-node-ids)
                                                  editable?)
                                :session-badges? session-badges?
                                :viewed-ordinal  viewed-ordinal})
        edges                 (adapter/relationships->edges
                               relationships selected-edge-ids
                               {:session-badges? session-badges?
                                :viewed-ordinal  viewed-ordinal})
        rf-instance           (useReactFlow)

        set-tool-mode         (use-callback
                               (fn [mode] (rf/dispatch [:ui/tool-mode-set mode]))
                               [])

        ;; Clicking an armed one-shot tool again disarms it back to
        ;; Select — one policy for the part tools and Connect alike.
        toggle-tool           (fn [mode]
                                (set-tool-mode
                                 (if (= tool-mode mode)
                                   toolbar/default-tool
                                   mode)))

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

        ;; nil when no marquee is active; the whole gesture during one —
        ;; `{:origin <flow point> :parts {id selected?}}`. The ref is the
        ;; authoritative, synchronously-readable copy; marquee-overlay
        ;; above mirrors it for rendering. Why the buffering exists: the
        ;; marquee section in state.toolbar.
        marquee-buffer        (use-ref nil)

        ;; The ref and its render mirror always move together; this is
        ;; the only writer.
        set-marquee!          (use-callback
                               (fn [buffer]
                                 (reset! marquee-buffer buffer)
                                 (set-marquee-overlay buffer))
                               [])

        dispatch-intent       (use-callback
                               (fn [intent]
                                 (if-let [buffer' (some-> @marquee-buffer
                                                          (toolbar/marquee-buffer-add intent))]
                                   (set-marquee! buffer')
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

                                     (o/warn "map.dispatch-intent" "unknown intent" intent))))
                               [demo queue-delete set-marquee!])

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

        ;; Set by on-connect, consumed by on-connect-end: the one-shot
        ;; spring-back must happen at gesture end, where the release
        ;; event's shiftKey is available (onConnect carries no event).
        connect-landed?       (use-ref false)

        on-connect            (use-callback
                               (fn [connection]
                                 (o/debug "map.on-connect" "connection created" connection)
                                 (dispatch-intent (adapter/translate-connect connection))
                                 (reset! connect-landed? true))
                               [dispatch-intent])

        ;; One-shot Connect (ADR-0015): a landed connection springs the
        ;; tool back to Select; Shift keeps it armed for batch drawing.
        on-connect-end        (use-callback
                               (fn [^js event _state]
                                 (when @connect-landed?
                                   (reset! connect-landed? false)
                                   (when (= :connect tool-mode)
                                     (set-tool-mode
                                      (toolbar/tool-mode-after-create
                                       :connect (boolean (.-shiftKey event)))))))
                               [tool-mode set-tool-mode])

        ;; Latest Map content for gesture-end reads. on-selection-end is
        ;; a ReactFlow memo prop: closing over the subscriptions directly
        ;; would mint it a fresh identity on every position frame and
        ;; bust that memo — it reads through this ref instead.
        map-content           (use-ref nil)

        on-selection-start    (use-callback
                               ;; The rect's start corner is tracked in the
                               ;; buffer because ReactFlow nulls its own
                               ;; userSelectionRect before onSelectionEnd
                               ;; fires — the rect must be reconstructable
                               ;; from the two pointer events.
                               (fn [^js event]
                                 (set-marquee!
                                  {:origin (event->flow-position rf-instance event)
                                   :parts  {}}))
                               [rf-instance set-marquee!])

        on-selection-end      (use-callback
                               (fn [^js event]
                                 (when-let [{:keys [origin] part-selects :parts}
                                            @marquee-buffer]
                                   ;; dispatch-sync: the committed selection
                                   ;; must be in place before the overlay
                                   ;; clears, or the highlight blinks off for
                                   ;; a frame between the two renders.
                                   (doseq [[id selected?] part-selects]
                                     (rf/dispatch-sync
                                      [:selection/toggle-node id selected?]))
                                   ;; Edges join the selection when the rect
                                   ;; crossed their drawn line — our own
                                   ;; hit-test, since ReactFlow's rule (every
                                   ;; edge of a selected node) over-selects.
                                   (let [{:keys [parts relationships]} @map-content
                                         rect                          (geometry/corners->rect
                                                                        origin
                                                                        (event->flow-position rf-instance event))]
                                     (doseq [id (geometry/marquee-hit-relationship-ids
                                                 parts relationships rect)]
                                       (rf/dispatch-sync
                                        [:selection/toggle-edge id true])))
                                   (set-marquee! nil)))
                               [rf-instance set-marquee!])

        on-pane-click         (use-callback
                               (fn [^js event]
                                 (when-let [part-type (add-mode->part-type tool-mode)]
                                   (let [{:keys [x y]} (event->flow-position
                                                        rf-instance event)]
                                     (o/track "Part created" {:type part-type :demo demo})
                                     (rf/dispatch [:map/part-create
                                                   {:type       part-type
                                                    :position_x x
                                                    :position_y y}])
                                     (set-tool-mode
                                      (toolbar/tool-mode-after-create
                                       tool-mode (.-shiftKey event))))))
                               [tool-mode rf-instance demo set-tool-mode])]

    (use-effect
     (fn []
       (reset! map-content {:parts parts :relationships relationships})
       js/undefined)
     [parts relationships])

    (use-effect
     (fn []
       (if-not time-travelling?
         ;; Track the live positions silently (ref only, no re-render):
         ;; entering the mode tweens FROM whatever was last on screen.
         (do (reset! glide-parts-ref parts)
             (set-glide-parts nil))
         (let [from  @glide-parts-ref
               start (js/performance.now)
               step  (fn step [now]
                       (let [t     (min 1 (/ (- now start) session-glide-ms))
                             frame (time-travel/interpolate-parts
                                    from parts (ease-out-cubic t))]
                         (reset! glide-parts-ref frame)
                         (set-glide-parts frame)
                         (when (< t 1)
                           (reset! glide-raf (js/requestAnimationFrame step)))))]
           (reset! glide-raf (js/requestAnimationFrame step))))
       (fn [] (js/cancelAnimationFrame @glide-raf)))
     [parts time-travelling?])

    (use-effect
     (fn []
       (when map-id
         (o/info "map.lifecycle" "starting event queue for map" map-id)
         (queue/start map-id)
         (fn []
           (o/info "map.lifecycle" "stopping event queue")
           (queue/stop))))
     [map-id])

    ;; A Map drawn far from the origin would land off-screen, so fit the
    ;; view on open — after the parts arrive (async; mount is too early).
    ;; maxZoom 1 keeps a small Map at 100% instead of blowing it up.
    (use-effect
     (fn []
       (when (and map-id
                  (not demo)
                  (seq parts)
                  (not= @fitted-map-id map-id))
         (reset! fitted-map-id map-id)
         (.fitView ^js rf-instance #js {:padding 0.2 :maxZoom 1}))
       js/undefined)
     [demo map-id parts rf-instance])

    (use-effect
     (fn []
       (let [on-down (fn [^js e]
                       ;; Bare keys only: Cmd+H (hide) etc. must stay the
                       ;; browser's. Escape also lets ReactFlow clear the
                       ;; selection itself.
                       (when (and (non-input-target? e)
                                  ;; An open modal owns the keyboard —
                                  ;; Escape must close the dialog, not
                                  ;; also exit a mode or reset the tool.
                                  (nil? (.querySelector js/document
                                                        "dialog[open]"))
                                  (not (or (.-metaKey e)
                                           (.-ctrlKey e)
                                           (.-altKey e))))
                         (if-let [travel-event (and time-travelling?
                                                    (time-travel/key-event (.-key e)))]
                           (rf/dispatch travel-event)
                           (if (time-travel/toggle-key? (.-key e))
                             ;; enter is a pure no-op without history.
                             (rf/dispatch (if time-travelling?
                                            [:time-travel/exit]
                                            [:time-travel/enter]))
                             (if-let [held (toolbar/spring-tool (.-key e))]
                               ;; Not mid-marquee: flipping the tool then would
                               ;; abort ReactFlow's gesture with the selection
                               ;; buffer still armed.
                               (when (and (not (.-repeat e))
                                          (nil? @marquee-buffer))
                                 (rf/dispatch [:ui/tool-spring-down held]))
                               (when-let [tool (toolbar/shortcut-tool (.-key e))]
                                 (set-tool-mode tool)))))))
             ;; Release is unguarded: wherever focus went mid-hold, the
             ;; hold must end. Window blur too, or a Cmd-Tab away leaves
             ;; the canvas stuck in Hand.
             on-up   (fn [^js e]
                       (when (toolbar/spring-tool (.-key e))
                         (rf/dispatch [:ui/tool-spring-up])))
             on-blur (fn [_e]
                       (rf/dispatch [:ui/tool-spring-up]))]
         (.addEventListener js/document "keydown" on-down)
         (.addEventListener js/document "keyup" on-up)
         (.addEventListener js/window "blur" on-blur)
         (fn []
           (.removeEventListener js/document "keydown" on-down)
           (.removeEventListener js/document "keyup" on-up)
           (.removeEventListener js/window "blur" on-blur))))
     [set-tool-mode time-travelling?])

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
       ($ :div {:class (str "map-view"
                            (when minimal " minimal")
                            (when time-travelling? " time-travelling")
                            " mode-" (name tool-mode))}
          ;; ⌘/Ctrl-scroll zoom rides on a ReactFlow default not visible
          ;; below (`zoomActivationKeyCode`, converting `panOnScroll`).
          ;; Space-drag is NOT ReactFlow's: `panActivationKeyCode` is
          ;; disabled in favour of the spring-loaded Hand hold (keydown
          ;; effect above), so holding Space is the real Hand tool —
          ;; cursor, palette light, nothing draggable — not a pan filter.
          (let [interaction (toolbar/tool-interaction tool-mode editable?)]
            ($ ReactFlow {:nodes                   nodes
                          :edges                   edges
                          :onNodesChange           on-nodes-change
                          :onEdgesChange           on-edges-change
                          :onConnect               on-connect
                          :onConnectEnd            on-connect-end
                          ;; Connecting is drag-only; RF's click-connect
                          ;; can't render a preview line and reads as
                          ;; dead clicks.
                          :connectOnClick          false
                          ;; A connection only starts after this much
                          ;; pointer travel, so a plain click on a Part
                          ;; can't mint a (permanent, bitemporal)
                          ;; self-loop; deliberate out-and-back self-loop
                          ;; drags exceed it on the way out.
                          :connectionDragThreshold 8
                          ;; Parts stay selectable read-only, so the
                          ;; delete key must go too — else Backspace
                          ;; opens a confirm modal for a delete the
                          ;; state layer then drops.
                          :deleteKeyCode           (if editable?
                                                     js/undefined
                                                     nil)
                          :onPaneClick             on-pane-click
                          :onSelectionStart        on-selection-start
                          :onSelectionEnd          on-selection-end
                          :nodeTypes               node-types
                          :edgeTypes               edge-types
                          :connectionLineComponent PartsConnectionLine
                          :panOnDrag               (if (true? (:pan-on-drag interaction))
                                                     true
                                                     middle-mouse-pan-buttons)
                          :selectionOnDrag         (:selection-on-drag interaction)
                          :nodesDraggable          (:nodes-draggable interaction)
                          :elementsSelectable      (:elements-selectable interaction)
                          :nodesConnectable        (:nodes-connectable interaction)
                          ;; A marquee only has to touch a Part to take it —
                          ;; full containment punishes loose lassos around
                          ;; big shapes.
                          :selectionMode           "partial"
                          :multiSelectionKeyCode   multi-selection-key-codes
                          :panActivationKeyCode    nil
                          :panOnScroll             (not minimal)
                          ;; Default 0.5 felt sluggish on a trackpad;
                          ;; +20% per hands-on testing.
                          :panOnScrollSpeed        0.6
                          :zoomOnScroll            false
                          :preventScrolling        (not minimal)
                          ;; Marketing hero (lg only): the demo is a full-bleed
                          ;; background with the headline overlaid on the left, so
                          ;; nudge the initial view right to keep it clear of the
                          ;; text. (Hidden below lg, so this only ever shows there.)
                          :defaultViewport         (if minimal
                                                     #js {:x 620 :y 90 :zoom 1}
                                                     js/undefined)}
               (when-not minimal
                 ($ Controls))
               (let [palette
                     ($ :div {:class "flex items-center gap-2"}
                        ($ :div {:class "join"}
                           (map (fn [{:keys [mode label shortcut icon]}]
                                  ;; Persistent tools: clicking one switches to
                                  ;; it — there is no "off", Select is the
                                  ;; resting state.
                                  ($ button {:key        (name mode)
                                             :icon       ($ icon {:size 16})
                                             :tooltip    (str label " — " shortcut)
                                             :aria-label (str label " tool")
                                             :on-click   #(set-tool-mode mode)
                                             :active?    (= tool-mode mode)}))
                                mode-tools))
                        ;; Time-travel keeps only the two persistent tools —
                        ;; Select and Hand still mean something read-only;
                        ;; the creation tools vanish with the mode.
                        (when-not time-travelling?
                          ($ :<>
                             ($ :div {:class "join"}
                                (map (fn [{:keys [mode label create-label]}]
                                       ;; Clicking an armed tool again disarms it back
                                       ;; to Select. The icon is the Part type's canvas
                                       ;; shape — the button previews the stamp it
                                       ;; places (stencil-palette pattern). The toolbar/
                                       ;; variants are solid-fill: the canvas SVGs'
                                       ;; 0.2-opacity fill washes out at 16px.
                                       ($ button {:key         (name mode)
                                                  :label       label
                                                  :label-class part-label-class
                                                  :icon        ($ :img {:src   (str "/images/nodes/toolbar/"
                                                                                    (add-mode->part-type mode)
                                                                                    ".svg")
                                                                        :alt   ""
                                                                        :class "h-4 w-auto"})
                                                  :tooltip     create-label
                                                  :aria-label  create-label
                                                  :on-click    #(toggle-tool mode)
                                                  :active?     (= tool-mode mode)
                                                  :disabled?   (not editable?)}))
                                     part-tools))
                             ;; The Connect split button: the left half arms the
                             ;; one-shot tool, the right half is the persistent
                             ;; type selector — the ink it draws with (ADR-0015).
                             ($ :div {:class "join"}
                                ($ button {:icon        ($ Spline {:size 16})
                                           :label       "Connect"
                                           :label-class connect-label-class
                                           :tooltip     "Connect — C"
                                           :aria-label  "Connect tool"
                                           :on-click    #(toggle-tool :connect)
                                           :active?     (= tool-mode :connect)
                                           :disabled?   (not editable?)})
                                ($ relationship-type-control)))))]
                 (if demo
                   ;; Playground / marketing hero: the original absolutely
                   ;; positioned panels — the hero's overlay CSS depends on
                   ;; them, and neither context has Sessions or Time-travel.
                   ($ :<>
                      (when-not minimal
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
                              ($ :img {:src "/images/parts-logo-mini.svg"}))))
                      ($ Panel {:position "bottom-center"
                                :class    "toolbar shadow-xs"}
                         palette)
                      ($ Panel {:position "top-right" :className "sidebar-container"}
                         ($ sidebar)))
                   ;; Authenticated view: two full-width chrome ROWS —
                   ;; flex items cannot overlap, and the Map name absorbs
                   ;; the squeeze by truncating (see map-name-widgets).
                   ($ :<>
                      ($ Panel {:position "top-left" :className "top-chrome"}
                         ($ :div {:class "flex items-start gap-2"}
                            ($ map-name-widgets)
                            ;; The session navigator centres in the LEFTOVER
                            ;; space; min-w-fit means it is never crushed
                            ;; below its own width — the name yields instead.
                            ($ :div {:class "flex-1 flex justify-center min-w-fit"}
                               (when time-travelling?
                                 ($ :div {:class "chrome-item"}
                                    ($ time-travel-bar))))
                            ($ :div {:class "w-50 shrink-0 chrome-item"}
                               ($ sidebar))))
                      ($ Panel {:position "bottom-left" :className "bottom-chrome"}
                         ($ :div {:class "flex items-center"}
                            ;; Equal-flex spacers keep the palette viewport-
                            ;; centred while space allows; their min-widths
                            ;; reserve the zoom controls and minimap, so
                            ;; under pressure the palette slides LEFT —
                            ;; labels intact — before any label stage fires.
                            ($ :div {:class "flex-1 min-w-9"})
                            ($ :div {:class "toolbar shadow-xs shrink-0 chrome-item"}
                               palette)
                            ($ :div {:class minimap-reserve-class}))))))
               (when-not minimal
                 ($ MiniMap {:className   (str "tools parts-minimap shadow-sm "
                                               minimap-hidden-class)
                             :position    "bottom-right"
                             :ariaLabel   "Minimap"
                             :pannable    true
                             :zoomable    true
                             :offsetScale 5}))
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
