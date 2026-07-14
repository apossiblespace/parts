(ns aps.parts.frontend.state.toolbar
  "Pure decision logic for the canvas tools (ADR-0015): which tool is
   active, what each tool makes a drag mean, and what happens to a
   one-shot tool after it fires.

   Dependency-free so the kaocha cljs suite can unit-test it (that suite
   carries no re-frame); `state/handlers` and the map component consume it."
  (:require
   [clojure.string :as str]))

(def default-tool
  "Select is the resting state: every other tool returns here."
  :select)

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
   C arms Connect, Escape = back to Select — the disarm-everything key."
  [key]
  (case (str/lower-case key)
    "h"      :hand
    "v"      :select
    "c"      :connect
    "escape" :select
    nil))

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
   draggable) until release, not just a pan filter. Nil for other keys."
  [key]
  (when (= " " key) :hand))

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

   Returns identity-stable values — fresh maps each call would bust
   ReactFlow's memoized renderer via the props built from them."
  ([tool] (tool-interaction tool true))
  ([tool editable?]
   (cond
     (= :hand tool)   hand-interaction
     (not editable?)  read-only-interaction
     (= :connect tool) connect-interaction
     :else            select-interaction)))

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
