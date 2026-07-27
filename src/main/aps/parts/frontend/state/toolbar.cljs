(ns aps.parts.frontend.state.toolbar
  "Pure decision logic for the canvas tools (ADR-0015): which tool is
   active, what each tool makes a drag mean, and what happens to a
   one-shot tool after it fires.

   Dependency-free so the kaocha cljs suite can unit-test it (that suite
   carries no re-frame); `state/handlers` and the map component consume it."
  (:require
   [aps.parts.common.geometry :as geometry]
   [clojure.string :as str]))

(def default-tool
  "Select is the resting state: every other tool returns here."
  :select)

(def ^:private touch-primary?
  ;; True when the device's primary pointer can't aim precisely — an
  ;; iPad finger, not a mouse or trackpad (an iPad with a trackpad
  ;; attached reports fine and keeps the desktop model). A load-time
  ;; constant, not a per-call query: the interaction maps it selects
  ;; must stay identity-stable for ReactFlow's memoized renderer.
  (and (exists? js/window)
       (.-matches (.matchMedia js/window "(pointer: coarse)"))))

(defn connection-drag-threshold
  "Pointer travel (px) before a Connect drag starts. 8px suits a mouse
   click's stillness; a fingertip's honest tap jitters ~10px, so touch
   needs more headroom or taps in Connect mode start phantom drags."
  ([] (connection-drag-threshold touch-primary?))
  ([touch?] (if touch? 16 8)))

(defn persistent-tools
  "The persistent tool modes the palette offers. Touch-primary devices
   offer none (ADR-0015 amendment): one-finger drag on empty canvas
   already pans in every tool there, so Hand would only be a mode to
   get stranded in — and with Hand gone, Select is the only persistent
   mode, making its button an indicator that controls nothing. Armed
   creation tools disarm by tapping again."
  ([] (persistent-tools touch-primary?))
  ([touch?]
   (if touch? [] [:select :hand])))

(defn relationship-create-attrs
  "Attrs for the Relationship created by an edge-drop: the connection's
   endpoints plus the type currently selected in the toolbar's persistent
   selector. The selector holds a keyword; the model spec wants the string."
  [db connection]
  (merge {:map_id (get-in db [:map :id])
          :type   (name (get-in db [:ui :relationship-type] :unknown))}
         connection))

(defn tool-mode-after-create
  "The tool mode after an armed part-creation click lands. Part tools are
   one-shot: placing a Part springs back to Select so the next pane click
   selects/deselects instead of minting another Part. Shift-click keeps
   the tool armed for batch adds."
  [current-mode shift?]
  (if shift? current-mode default-tool))

(defn shortcut-tool
  "The tool a bare keypress switches to, or nil if the key isn't a tool
   shortcut. V = Select and H = Hand (the industry-standard pair),
   C arms Connect, Escape = back to Select — the disarm-everything key.
   H is inert on touch (a Smart-Keyboard iPad must not arm a tool that
   has no palette button to leave — see `persistent-tools`)."
  ([key] (shortcut-tool key touch-primary?))
  ([key touch?]
   (case (str/lower-case key)
     "h"      (when-not touch? :hand)
     "v"      :select
     "c"      :connect
     "escape" :select
     nil)))

(defn part-chord-key?
  "P opens the two-key Part chord (P then U/E/F/M)."
  [key]
  (= "p" (str/lower-case key)))

(def chord-keys
  "The Part chord's second key per creation tool — the single source
   the router derives from and the toolbar tooltips display."
  {:add-unknown     "U"
   :add-exile       "E"
   :add-firefighter "F"
   :add-manager     "M"})

(def ^:private key->chord-tool
  (into {} (map (fn [[tool k]] [(str/lower-case k) tool])) chord-keys))

(defn chord-tool
  "The creation tool the Part chord's second key arms, or nil — an
   unmatched second key cancels the chord, and the caller routes it as
   if it had been pressed alone."
  [key]
  (key->chord-tool (str/lower-case key)))

(defn modifier-key?
  "A pure modifier keydown (the Shift of Shift+E) — these must not
   advance the chord, or the modifier's own keydown would consume it
   before the real key arrives."
  [key]
  (contains? #{"Shift" "Meta" "Control" "Alt"} key))

(defn chord-step
  "Advance the Part chord one (non-modifier) keydown. The chord is
   consumed by the very next key regardless of what it is:
   - pending + matching key  → {:tool <creation tool>}
   - P (pending or not)      → {:arm? true} (held-P auto-repeat re-arms)
   - anything else           → {} — the key routes as if pressed alone."
  [pending? key]
  (cond
    (and pending? (chord-tool key)) {:tool (chord-tool key)}
    (part-chord-key? key)           {:arm? true}
    :else                           {}))

(defn select-tool
  "Explicitly choose a tool on the `:ui` state map. Also cancels any
   spring-loaded hold — the release must not undo a deliberate choice."
  [ui tool]
  (-> ui
      (assoc :tool-mode tool)
      (dissoc :spring-return-tool)))

(defn choose-relationship-type
  "Pick the persistent relationship type AND arm the Connect tool —
   there is no situation where you choose an ink without wanting to
   draw with it right away."
  [ui type]
  (-> ui
      (assoc :relationship-type type)
      (select-tool :connect)))

(defn spring-tool
  "The tool temporarily held while a key is down: Space spring-loads the
   Hand tool — full Hand behaviour (cursor, lit palette button, nothing
   draggable) until release, not just a pan filter. Nil for other keys,
   and nil everywhere on touch, where Hand doesn't exist."
  ([key] (spring-tool key touch-primary?))
  ([key touch?]
   (when (and (not touch?) (= " " key)) :hand)))

(defn spring-hold
  "Begin a spring-loaded hold on the `:ui` state map: switch to `tool`,
   remembering the current tool to return to. A no-op while a hold is
   already active, which also absorbs keyboard auto-repeat."
  [ui tool]
  (if (:spring-return-tool ui)
    ui
    (assoc ui
           :spring-return-tool (:tool-mode ui default-tool)
           :tool-mode tool)))

(defn spring-release
  "End a spring-loaded hold: return to the remembered tool. A no-op when
   no hold is active."
  [ui]
  (if-let [prev (:spring-return-tool ui)]
    (-> ui
        (assoc :tool-mode prev)
        (dissoc :spring-return-tool))
    ui))

(def ^:private hand-interaction
  {:pan-on-drag         true
   :selection-on-drag   false
   :nodes-draggable     false
   :elements-selectable false
   :nodes-connectable   false})

(def ^:private select-interaction
  {:pan-on-drag         [1]
   :selection-on-drag   true
   :nodes-draggable     true
   :elements-selectable true
   :nodes-connectable   true})

(def ^:private connect-interaction
  ;; A Part's body means "endpoint", not "move" or "select" — the whole
  ;; body is the drag source (see the mode-connect CSS).
  {:pan-on-drag         [1]
   :selection-on-drag   false
   :nodes-draggable     false
   :elements-selectable false
   :nodes-connectable   true})

(def ^:private read-only-interaction
  ;; Selection and pan stay — reading a Part's notes is what viewing is
  ;; for — but nothing can move or connect.
  {:pan-on-drag         [1]
   :selection-on-drag   true
   :nodes-draggable     false
   :elements-selectable true
   :nodes-connectable   false})

;; -- Touch variants --------------------------------------------------------
;; xyflow cannot give a touch device both the marquee and pinch-zoom: its
;; pan filter only checks the pan-button array against mousedown, so any
;; one-finger drag starts a pan regardless — and disabling pan outright
;; kills pinch-zoom with it. So on touch-primary devices drag-empty pans
;; (the iPad convention) in every tool, and group selection is the
;; long-press marquee gesture below.

(defn- touch-interaction
  "Derive a base interaction's touch counterpart: drag-empty pans (the
   library's touch pan cannot be disabled without also losing
   pinch-zoom), the library marquee is off, and the long-press marquee
   is granted exactly where the library marquee was. Deriving instead
   of hand-writing keeps any future interaction map's touch semantics
   correct by construction."
  [base]
  (cond-> (assoc base :pan-on-drag true)
    (:selection-on-drag base) (assoc :selection-on-drag false
                                     :long-press-marquee true)))

(def ^:private select-interaction-touch
  (touch-interaction select-interaction))

(def ^:private connect-interaction-touch
  (touch-interaction connect-interaction))

(def ^:private read-only-interaction-touch
  (touch-interaction read-only-interaction))

(def ^:private touch-variant
  ;; Base interaction → its touch counterpart. Hand is absent on
  ;; purpose: it already means the same thing everywhere.
  {select-interaction    select-interaction-touch
   connect-interaction   connect-interaction-touch
   read-only-interaction read-only-interaction-touch})

(defn tool-interaction
  "What a drag means under the active tool, as data for the ReactFlow
   props (ADR-0015). In Select — and any armed one-shot creation tool —
   dragging empty canvas draws a marquee, so left-drag must NOT pan:
   `:pan-on-drag [1]` keeps only middle-mouse-drag panning (the other
   accelerators: trackpad scroll via panOnScroll, and Space as a
   spring-loaded Hand hold). The Hand tool is the opposite: dragging
   only pans, and nothing is selectable or draggable, so a mis-click
   can never move a Part.

   On a read-only canvas every tool but Hand collapses to the read-only
   interaction: select and pan, never move or connect.

   On touch-primary devices (`touch-primary?`, the default for the
   `touch?` arm) every tool swaps in its touch variant: drag-empty pans
   and the marquee is off — see the touch-variant defs above for why.

   Returns identity-stable values — fresh maps each call would bust
   ReactFlow's memoized renderer via the props built from them."
  ([tool] (tool-interaction tool true))
  ([tool editable?] (tool-interaction tool editable? touch-primary?))
  ([tool editable? touch?]
   (let [base (cond
                (= :hand tool)    hand-interaction
                (not editable?)   read-only-interaction
                (= :connect tool) connect-interaction
                :else             select-interaction)]
     (if touch?
       (get touch-variant base base)
       base))))

;; -- Touch long-press marquee ----------------------------------------------
;; The touch interaction maps trade the marquee away for panning (see the
;; touch-variant defs). Group selection comes back as press-and-hold: keep
;; a finger still on empty canvas until the hold arms, then drag to draw
;; the rect — the Freeform pattern. This state machine is the pure core;
;; the map component owns the timer, the pointer events, and the commit.
;;
;; States: nil (idle / cancelled) → {:phase :holding} → {:phase :active}.
;; A hold that moves past the slop radius cancels to nil — that drag was
;; a pan, and the pan is already running (the machine only observes; it
;; never owned the viewport).

(def marquee-hold-ms
  "How long the touch must stay still before the hold arms. iOS's own
   long-press recognisers sit around this value."
  500)

(def ^:private marquee-hold-slop-px
  "Movement tolerated before the hold is read as a pan. Matches the
   ~10px finger tremor iOS recognisers allow; the 8px desktop connect
   threshold is too tight for a fingertip."
  10)

(defn marquee-hold-begin
  "A touch landed on empty canvas: start holding at `point`. The hold is
   bound to `pointer-id` — the whole gesture belongs to that one finger
   (see `marquee-hold-owns?`)."
  [point pointer-id]
  {:phase :holding :origin point :pointer-id pointer-id})

(defn marquee-hold-owns?
  "Does this pointer own the gesture? Guards every event after the
   initial down: a second finger landing mid-gesture must neither drag
   the marquee's corner nor commit or cancel the selection — only the
   finger that began the hold may drive it."
  [state pointer-id]
  (= pointer-id (:pointer-id state)))

(defn marquee-hold-arm
  "The hold timer fired: a still hold becomes an active marquee anchored
   at its origin. Nil (no-op) for a cancelled or already-armed state."
  [state]
  (when (= :holding (:phase state))
    (assoc state :phase :active :current (:origin state))))

(defn marquee-hold-rect
  "The marquee rect `{:x :y :width :height}` of an active hold, spanning
   origin and current whichever way the drag ran. Nil until armed."
  [state]
  (when (= :active (:phase state))
    (geometry/corners->rect (:origin state) (:current state))))

(defn- rect-past-slop?
  [state]
  (when-let [{:keys [width height]} (marquee-hold-rect state)]
    (or (> width marquee-hold-slop-px)
        (> height marquee-hold-slop-px))))

(defn marquee-hold-move
  "Fold a pointer move into the hold. While holding, tremor within the
   slop radius is ignored and larger movement cancels to nil — the drag
   is a pan. While active, the move drags the marquee's far corner and,
   once the rect clears the slop, latches `:dragged`."
  [{:keys [phase origin] :as state} {:keys [x y] :as point}]
  (case phase
    :holding (let [dx (- x (:x origin))
                   dy (- y (:y origin))]
               (when (<= (+ (* dx dx) (* dy dy))
                         (* marquee-hold-slop-px marquee-hold-slop-px))
                 state))
    :active  (let [state' (assoc state :current point)]
               (cond-> state'
                 (rect-past-slop? state') (assoc :dragged true)))
    nil))

(defn marquee-hold-dragging?
  "Has the armed marquee demonstrably been dragged? Latched — once true
   it stays true for the rest of the gesture, so the armed-ring fade
   cannot reverse when the corner is dragged back near the origin. From
   the first real drag onward the rect, not the ring, is the gesture's
   indicator."
  [state]
  (boolean (:dragged state)))

;; -- Marquee selection buffering -------------------------------------------
;; During a marquee drag ReactFlow emits per-mousemove select changes.
;; Round-tripping each through re-frame updates the node props out of
;; lockstep with those emissions and the canvas flickers. So during the
;; gesture the selects accumulate in a buffer, render through a local
;; overlay (`marquee-preview-ids`), and commit to re-frame once, at
;; gesture end.

(defn marquee-buffer-add
  "Fold a selection intent into the marquee buffer, or return nil for an
   intent that isn't the marquee's to intercept. Part selects accumulate
   under `:parts` — the latest selected? per id wins. Relationship
   selects are swallowed (buffer returned unchanged): ReactFlow
   auto-selects every edge connected to a selected node, which reads as
   over-selection — the edges the rect actually crosses are hit-tested
   at gesture end instead (`geometry/marquee-hit-relationship-ids`)."
  [buffer {:keys [intent id selected?]}]
  (case intent
    :part-selected         (assoc-in buffer [:parts id] selected?)
    :relationship-selected buffer
    nil))

(defn marquee-preview-ids
  "The selected ids to render while a marquee is active: the committed
   selection with the in-gesture buffer's adds and removes applied on top."
  [committed-ids overlay]
  (reduce-kv (fn [ids id selected?]
               (if selected? (conj ids id) (disj ids id)))
             (set committed-ids)
             overlay))

(defn resize-armed?
  "Resize belongs to the Select tool, single selection only (ADR-0015):
   corners resize, whole body moves. A marquee selection is for
   move/delete — group-resize is deferred — and no other tool shows
   handles at all. Each node then shows handles iff it is the one
   selected."
  [tool selected-count editable?]
  (and editable?
       (= :select tool)
       (= 1 selected-count)))
