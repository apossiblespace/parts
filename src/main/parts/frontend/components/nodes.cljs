(ns parts.frontend.components.nodes
  (:require
   ["reactflow" :refer [Handle Position]]
   [uix.core :refer [defui $]]))

(defui parts-node [{:keys [type data is-connectable]}]
  ($ :div {:class (str "node " type)}
     ($ Handle {:type "target"
                :position (.-Top Position)
                :isConnectable is-connectable})
     ($ :div (get data "label"))
     ($ Handle {:type "source"
                :position (.-Bottom Position)
                :isConnectable is-connectable})))

(def PartsNode
  (uix.core/as-react
   (fn [{:keys [type data isConnectable]}]
     ($ parts-node {:type type
                   :data (js->clj data)
                   :is-connectable isConnectable}))))

(def node-types
  {:manager PartsNode
   :firefighter PartsNode
   :exile PartsNode})
