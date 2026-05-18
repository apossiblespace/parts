(ns aps.parts.frontend.components.nodes
  (:require
   ["@xyflow/react" :refer [Handle Position]]
   [uix.core :refer [$ as-react defui]]))

(defui parts-node [{:keys [data]}]
  ($ :div {:class "node-wrapper"}
     ($ :div {:class (str "node " (:type data))}
        ;; Whole-node connection overlay: a single Handle stretched over
        ;; the node body. ReactFlow's connectionMode="loose" (set on the
        ;; canvas) makes the source-type handle accept drops too, so one
        ;; Handle is enough to be both "drag from" and "drop on". CSS
        ;; (.mode-connect .connect-handle) enables pointer events only
        ;; in Connect mode so node-body drag still works in Select mode.
        ($ Handle {:type      "source"
                   :position  (.-Top Position)
                   :className "connect-handle"})
        ($ :div {:class "text-center font-medium text-sm/4"}
           (:label data)))))

(def PartsNode
  (as-react
   (fn [{:keys [id type data] :as ^js _props}]
     ($ parts-node {:id   id
                    :type type
                    :data (js->clj data :keywordize-keys true)}))))

(def node-types
  {"default" PartsNode})
