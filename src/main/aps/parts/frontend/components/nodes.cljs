(ns aps.parts.frontend.components.nodes
  (:require
   ["@xyflow/react" :refer [Handle Position]]
   [uix.core :refer [$ as-react defui]]))

(defui parts-node [{:keys [data]}]
  ($ :div {:class "node-wrapper"}
     ($ :div {:class (str "node " (:type data))}
        ($ Handle {:type     "target"
                   :position (.-Top Position)})
        ($ :div {:class "text-center font-medium text-sm/4"}
           (:label data))
        ($ Handle {:type     "source"
                   :position (.-Bottom Position)}))))

(def PartsNode
  (as-react
   (fn [{:keys [id type data] :as ^js _props}]
     ($ parts-node {:id   id
                    :type type
                    :data (js->clj data :keywordize-keys true)}))))

(def node-types
  {"default" PartsNode})
