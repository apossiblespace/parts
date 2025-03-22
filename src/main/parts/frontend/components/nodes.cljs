(ns parts.frontend.components.nodes
  (:require
   ["@xyflow/react" :refer [Handle Position]]
   [parts.common.constants :refer [part-labels]]
   [uix.core :refer [$ defui]]))

(defui parts-node [{:keys [type data]}]
  ($ :div {:class "node-wrapper"}
     ($ :div {:class (str "node " type)}
        ($ Handle {:type "target"
                   :position (.-Top Position)})
        ($ :div {:class "text-center font-medium text-sm/4"}
           (:label data))
        ($ Handle {:type "source"
                   :position (.-Bottom Position)}))))

(def PartsNode
  (uix.core/as-react
    (fn [{:keys [id type data] :as ^js _props}]
      ($ parts-node {:id id
                     :type type
                     :data (js->clj data :keywordize-keys true)}))))

(def node-types
  (->> part-labels
       keys
       (map #(vector % PartsNode))
       (into {})))
