(ns parts.frontend.components.nodes
  (:require
   ["reactflow" :refer [Handle Position]]
   [uix.core :refer [defui $ use-state]]
   [parts.frontend.components.node-form :refer [node-form]]
   [parts.common.part-types :refer [part-types]]))

(defui parts-node [{:keys [type data is-connectable]}]
  (let [[editing? set-editing] (use-state false)]
    ($ :div {:class (str "node " type)}
       ($ Handle {:type "target"
                  :position (.-Top Position)
                  :isConnectable is-connectable})
       (when (not editing?)
         ($ :div
            (get data "label")
            ($ :button {:onClick #(set-editing true)}
               "✏️")))
       (when editing?
         ($ node-form {:node {:type type :data data}
                       :on-save (fn [id form-data]
                                  ;; FIXME: Implement actual handler
                                  (println id form-data)
                                  (set-editing false))
                       :on-cancel #(set-editing false)}))
       ($ Handle {:type "source"
                :position (.-Bottom Position)
                :isConnectable is-connectable}))))

(def PartsNode
  (uix.core/as-react
   (fn [{:keys [type data isConnectable]}]
     ($ parts-node {:type type
                   :data (js->clj data)
                   :is-connectable isConnectable}))))

(def node-types
  (->> part-types
       keys
       (map #(vector % PartsNode))
       (into {})))
