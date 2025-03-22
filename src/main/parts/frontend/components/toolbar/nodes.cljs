(ns parts.frontend.components.toolbar.nodes
  (:require
   [parts.frontend.components.node-form :refer [node-form]]
   [parts.frontend.components.toolbar.header :refer [header]]
   [parts.frontend.context :as ctx]
   [uix.core :refer [$ defui use-context]]))

(defui node-tools
  "Renders a tool palette that displays the selected nodes for editing"
  [{:keys [selected-nodes]}]
  (let [{:keys [update-node]} (use-context ctx/update-system-context)
        node-count (count selected-nodes)
        multiple-nodes (> node-count 1)]
    (when (seq selected-nodes)
      ($ :div {:class "tools node-tools"}
        ($ header {:title "Selected parts" :count node-count})
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
