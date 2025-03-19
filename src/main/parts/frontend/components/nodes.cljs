(ns parts.frontend.components.nodes
  (:require
   ["reactflow" :refer [Handle Position]]
   [uix.core :refer [defui $]]
   [parts.common.constants :refer [part-labels]]))

(defui parts-node [{:keys [type data is-connectable]}]
  ($ :div {:class "node-wrapper"}
     ($ :div {:class (str "node " type)}
        ($ Handle {:type "target"
                   :position (.-Top Position)
                   :isConnectable is-connectable})
        ($ :div {:class "text-center font-medium text-sm/4"}
           (:label data))
        ($ Handle {:type "source"
                   :position (.-Bottom Position)
                   :isConnectable is-connectable}))))

(def PartsNode
  (uix.core/as-react
   (fn [{:keys [id type data isConnectable] :as ^js props}]
     ($ parts-node {:id id
                    :type type
                    :data (js->clj data :keywordize-keys true)
                    :is-connectable isConnectable}))))

(def node-types
  (->> part-labels
       keys
       (map #(vector % PartsNode))
       (into {})))
