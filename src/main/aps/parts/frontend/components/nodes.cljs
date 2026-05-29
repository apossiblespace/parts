(ns aps.parts.frontend.components.nodes
  (:require
   ["@xyflow/react" :refer [Handle Position useConnection]]
   [aps.parts.frontend.adapters.reactflow :as adapter]
   [aps.parts.frontend.components.inline-text-field :refer [inline-text-field]]
   [re-frame.core :as rf]
   [uix.core :refer [$ as-react defui use-state]]))

(defui parts-node [{:keys [id data]}]
  ;; Easy-connect pattern (https://reactflow.dev/examples/nodes/easy-connect):
  ;; target handle is permanent and not connectable-as-start; source handle
  ;; sits on top but unmounts during a drag so the target underneath gets the
  ;; drop. Distinct ids matter: ReactFlow's connectionLookup keys connections
  ;; by source/target node+handle strings, and identical handle ids on both
  ;; ends collide for bidirectional pairs (then drag-select misses one edge).
  (let [connecting?             (useConnection (fn [^js c] (.-inProgress c)))
        [editing? set-editing!] (use-state false)]
    ($ :div {:class "node-wrapper"}
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
              :input-class   "input input-xs w-full text-center nodrag nopan"
              :editing?      editing?
              :edit-on       :none
              :on-cancel     #(set-editing! false)
              :on-commit     (fn [new-label]
                               (rf/dispatch [:map/part-update id {:label new-label}])
                               (set-editing! false))})))))

(def PartsNode
  (as-react
   (fn [{:keys [id type data] :as ^js _props}]
     ($ parts-node {:id   id
                    :type type
                    :data (js->clj data :keywordize-keys true)}))))

(def node-types
  #js {:default PartsNode})
