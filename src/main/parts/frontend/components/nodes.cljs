(ns parts.frontend.components.nodes
  (:require
   ["reactflow" :refer [Handle Position]]
   [uix.core :refer [defui $ use-state]]
   [parts.frontend.components.node-form :refer [node-form]]
   [parts.common.constants :refer [part-labels]]
   [parts.frontend.context :as ctx]))

(defui parts-node [{:keys [id type data is-connectable]}]
  ($ :div {:class "node-wrapper"}
     ($ :div {:class (str "node " type)}
        ($ Handle {:type "target"
                   :position (.-Top Position)
                   :isConnectable is-connectable})
        ($ :div
           (get data "label")
           ($ Handle {:type "source"
                      :position (.-Bottom Position)
                      :isConnectable is-connectable})))))

(def PartsNode
  (uix.core/as-react
   (fn [{:keys [id type data isConnectable] :as ^js props}]
     ($ parts-node {:id id
                    :type type
                    :data (js->clj data)
                    :is-connectable isConnectable}))))

(def node-types
  (->> part-labels
       keys
       (map #(vector % PartsNode))
       (into {})))
