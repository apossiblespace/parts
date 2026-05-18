(ns aps.parts.frontend.components.nodes
  (:require
   ["@xyflow/react" :refer [Handle Position useConnection]]
   [aps.parts.frontend.adapters.reactflow :as adapter]
   [uix.core :refer [$ as-react defui]]))

(defui parts-node [{:keys [data]}]
  ;; Easy-connect pattern (https://reactflow.dev/examples/nodes/easy-connect):
  ;; target handle is permanent and not connectable-as-start; source handle
  ;; sits on top but unmounts during a drag so the target underneath gets the
  ;; drop. Distinct ids matter: ReactFlow's connectionLookup keys connections
  ;; by source/target node+handle strings, and identical handle ids on both
  ;; ends collide for bidirectional pairs (then drag-select misses one edge).
  (let [connecting? (useConnection (fn [^js c] (.-inProgress c)))]
    ($ :div {:class "node-wrapper"}
       ($ :div {:class (str "node " (:type data))}
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
          ($ :div {:class "text-center font-medium text-sm/4"}
             (:label data))))))

(def PartsNode
  (as-react
   (fn [{:keys [id type data] :as ^js _props}]
     ($ parts-node {:id   id
                    :type type
                    :data (js->clj data :keywordize-keys true)}))))

(def node-types
  #js {:default PartsNode})
