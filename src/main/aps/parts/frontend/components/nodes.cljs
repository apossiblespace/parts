(ns aps.parts.frontend.components.nodes
  (:require
   ["@xyflow/react" :refer [Handle Position]]
   [uix.core :refer [$ as-react defui]]))

(defui parts-node [{:keys [data]}]
  ($ :div {:class "node-wrapper"}
     ($ :div {:class (str "node " (:type data))}
        ;; Whole-node connection overlay: two stacked invisible Handles
        ;; covering the node body. CSS (.mode-connect .connect-handle)
        ;; enables pointer events only in Connect mode so node-body drag
        ;; keeps working in Select mode.
        ($ Handle {:type      "source"
                   :position  (.-Top Position)
                   :className "connect-handle"})
        ($ Handle {:type      "target"
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
