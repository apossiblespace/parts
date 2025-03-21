(ns parts.frontend.components.toolbar.nodes
  (:require
   [parts.frontend.components.node-form :refer [node-form]]
   [parts.frontend.context :as ctx]
   [uix.core :refer [$ defui]]))

(defui node-tools
  "Renders a tool palette that displays the selected nodes for editing"
  [{:keys [selected-nodes]}]
  (let [update-node (uix.core/use-context ctx/update-node-context)
        node-count (count selected-nodes)
        multiple-nodes (> node-count 1)]
    ($ :div {:class "node-tools"}
       (when multiple-nodes
         ($ :div {:class "text-xs p-2"} (str node-count " parts selected:")))
       (when (seq selected-nodes)
         ($ :section {:class "selected-nodes"}
            (map
              (fn [node]
                ($ node-form {:key (str (:id node) node-count)
                              :node node
                              :collapsed multiple-nodes
                              :on-save (fn [id form-data]
                                         (when update-node
                                           (update-node id form-data)))}))
              selected-nodes))))))
