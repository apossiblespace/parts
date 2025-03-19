(ns parts.frontend.components.sidebar
  (:require
   [parts.frontend.components.auth-status :refer [auth-status]]
   [parts.frontend.components.node-form :refer [node-form]]
   [parts.frontend.context :as ctx]
   [uix.core :refer [$ defui]]))

(defui sidebar
  "Display the main sidebar"
  [{:keys [selected-nodes selected-edges]}]
  (println "Sidebar selected nodes:" selected-nodes)
  (println "Sidebar selected edges:" selected-edges)
  (let [update-node (uix.core/use-context ctx/update-node-context)]
    ($ :div {:class "sidebar"}
       ($ auth-status)
       (when (seq selected-nodes)
         ($ :section {:class "selected-nodes"}
            (map
             (fn [node]
               ($ node-form {:key (:id node)
                             :node node
                             :on-save (fn [id form-data]
                                        (println "on-save called" id form-data)
                                        (when update-node
                                          (update-node id form-data)))}))
             selected-nodes)))
       (when (seq selected-edges)
         ($ :div {:class "selected-edges"}
            "Selected Edges")))))
