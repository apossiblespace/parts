(ns parts.frontend.components.node-form
  (:require
   [uix.core :refer [defui $]]
   [parts.common.constants :refer [part-labels]]))

(defui node-form [{:keys [node on-save on-cancel]}]
  (let [{:keys [id type data]} node
        [form-data set-form-data] (uix.core/use-state {:type type
                                                      :label (get data "label")})]
    ($ :div {:class "node-form"}
       ($ :div {:class "form-row"}
          ($ :label "Type:")
          ($ :select {:value (:type form-data)
                     :onChange #(set-form-data assoc :type (.. % -target -value))}
             (->> part-labels
                  (map (fn [[k {:keys [label]}]]
                         ($ :option {:key k :value k}
                            label))))))
       ($ :div {:class "form-row"}
          ($ :label "Label:")
          ($ :input {:type "text"
                    :value (:label form-data)
                    :onChange #(set-form-data assoc :label (.. % -target -value))}))

       ($ :div {:class "form-actions"}
          ($ :button {:onClick #(on-cancel)} "Cancel")
          ($ :button {:onClick #(on-save id form-data)} "Save")))))
