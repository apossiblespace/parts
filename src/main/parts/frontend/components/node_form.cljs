(ns parts.frontend.components.node-form
  (:require
   [uix.core :refer [defui $]]
   [parts.common.constants :refer [part-labels]]))

(defui node-form [{:keys [node on-save]}]
  (let [{:keys [id type data]} node
        [form-data set-form-data] (uix.core/use-state {:type type
                                                       :label (:label data)})]
    ($ :fieldset {:class "fieldset node-form"}
       ($ :legend {:class "fieldset-legend"}
          (str "#" id ": " (:label form-data)))

       ($ :label {:class "fieldset-label"} "Type:")
       ($ :select {:class "select"
                   :value (:type form-data)
                   :onChange #(set-form-data assoc :type (.. % -target -value))}
          (->> part-labels
               (map (fn [[k {:keys [label]}]]
                      ($ :option {:key k :value k}
                         label)))))

       ($ :label {:class "fieldset-label"} "Label:")
       ($ :input {:class "input"
                  :type "text"
                  :value (:label form-data)
                  :onChange #(set-form-data assoc :label (.. % -target -value))})

       ($ :button {:class "btn btn-primary"
                   :onClick #(on-save id form-data)}
          "Save"))))
