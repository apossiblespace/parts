(ns aps.parts.frontend.components.nodes
  (:require
   ["@xyflow/react" :refer [Handle NodeResizer Position useConnection]]
   [aps.parts.common.constants :as constants]
   [aps.parts.frontend.adapters.reactflow :as adapter]
   [aps.parts.frontend.components.inline-text-field :refer [inline-text-field]]
   [re-frame.core :as rf]
   [uix.core :refer [$ as-react defui use-state]]))

(defui parts-node [{:keys [id data selected]}]
  ;; Easy-connect pattern (https://reactflow.dev/examples/nodes/easy-connect),
  ;; adapted to the direct-manipulation model (ADR-0011): the source handle
  ;; is clipped to a boundary ring (drag from the ring starts a connection;
  ;; the interior stays draggable-to-move), and unmounts during a drag so
  ;; the target handle underneath — the whole node, a forgiving drop zone —
  ;; gets the drop. The `connecting` wrapper class is what switches the
  ;; target handle's pointer-events on. Distinct ids matter: ReactFlow's
  ;; connectionLookup keys connections by source/target node+handle strings,
  ;; and identical handle ids on both ends collide for bidirectional pairs
  ;; (then drag-select misses one edge).
  (let [connecting?             (useConnection (fn [^js c] (.-inProgress c)))
        [editing? set-editing!] (use-state false)]
    ($ :div {:class (str "node-wrapper" (when connecting? " connecting"))}
       ;; Resize affordance: corner handles, aspect-locked, bounded
       ;; (TASK-032). :resizable arrives via node data (the canvas's
       ;; resize-armed decision — see toolbar/resize-armed?), so no
       ;; per-node subscriptions. The connecting lines between handles
       ;; are hidden in CSS — edge midpoints belong to the connect ring.
       ($ NodeResizer {:isVisible       (boolean (and selected
                                                      (:resizable data)))
                       :keepAspectRatio true
                       :minWidth        constants/part-min-size
                       :minHeight       constants/part-min-size
                       :maxWidth        constants/part-max-size
                       :maxHeight       constants/part-max-size})
       ($ :div {:class           (str "node " (:type data))
                ;; Double-click the whole shape (a bigger target than the
                ;; label text) to edit the label in place. stopPropagation
                ;; keeps the dblclick from bubbling to ReactFlow; the
                ;; preceding single-clicks still select the node.
                :on-double-click (fn [^js e]
                                   (.stopPropagation e)
                                   (set-editing! true))}
          ($ Handle {:type               "target"
                     :position           (.-Top Position)
                     :id                 adapter/target-handle-id
                     :className          "connect-handle"
                     :isConnectableStart false})
          (when-not connecting?
            ($ Handle {:type      "source"
                       :position  (.-Top Position)
                       :id        adapter/source-handle-id
                       :className "connect-handle"}))
          ;; The label is an inline-text-field, controlled on `editing?` and
          ;; opened by the shape's double-click (`:edit-on :none` — the node
          ;; owns the gesture). The <input> carries ReactFlow's `nodrag nopan`
          ;; so typing/selecting never drags the node or pans the canvas.
          ;; Commit goes through `:map/part-update` (the existing change-event
          ;; path); a blank/no-op commit cancels, so the name is never erased.
          ($ inline-text-field
             {:value         (:label data)
              :aria-label    "Part label"
              :display-class "text-center font-medium text-sm/4"
              ;; shrink-0 + a fixed width lets the single-line editor overflow
              ;; the small node and stay centered (the .node flex box centers
              ;; it), instead of being clamped to the node's padded interior.
              :input-class   "input input-sm text-center nodrag nopan shrink-0 w-32"
              :editing?      editing?
              :edit-on       :none
              :on-cancel     #(set-editing! false)
              :on-commit     (fn [new-label]
                               (rf/dispatch [:map/part-update id {:label new-label}])
                               (set-editing! false))})))))

(def PartsNode
  (as-react
   (fn [{:keys [id type data selected] :as ^js _props}]
     ($ parts-node {:id       id
                    :type     type
                    :selected selected
                    :data     (js->clj data :keywordize-keys true)}))))

(def node-types
  #js {:default PartsNode})
