(ns aps.parts.frontend.state.toolbar
  "Pure decision logic for the canvas toolbar (ADR-0011): what a creation
   gesture produces, and what happens to the armed tool afterwards.

   Dependency-free so the kaocha cljs suite can unit-test it (that suite
   carries no re-frame); `state/handlers` and the map component consume it.")

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
   one-shot: placing a Part disarms back to neutral (nil) so the next pane
   click deselects instead of minting another Part. Shift-click keeps the
   tool armed for batch adds."
  [current-mode shift?]
  (when shift? current-mode))
