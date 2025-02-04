(ns parts.frontend.components.nodes
  (:require
   ["reactflow" :refer [Handle Position]]
   [uix.core :refer [defui $]]))

(defui manager-node [{:keys [data is-connectable]}]
  ($ :div {:class "node manager"}
     ($ Handle {:type "target"
                :position (.-Top Position)
                :isConnectable is-connectable})
     ($ :div "Manager")
     ($ Handle {:type "source"
                :position (.-Bottom Position)
                :isConnectable is-connectable})))

(defui firefighter-node [{:keys [data is-connectable]}]
  ($ :div {:class "node firefighter"}
     ($ Handle {:type "target"
                :position (.-Top Position)
                :isConnectable is-connectable})
     ($ :div "Firefighter")
     ($ Handle {:type "source"
                :position (.-Bottom Position)
                :isConnectable is-connectable})))

(defui exile-node [{:keys [data is-connectable]}]
  ($ :div {:class "node exile"}
     ($ Handle {:type "target"
                :position (.-Top Position)
                :isConnectable is-connectable})
     ($ :div "Exile")
     ($ Handle {:type "source"
                :position (.-Bottom Position)
                :isConnectable is-connectable})))
