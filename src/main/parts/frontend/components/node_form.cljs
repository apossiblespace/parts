(ns parts.frontend.components.node-form
  (:require
   [uix.core :refer [defui $]]
   [parts.common.constants :refer [part-labels]]))

(defui node-form [{:keys [node on-save]}]
  (let [{:keys [id type data]} node
        [initial-form-data set-initial-form-data] (uix.core/use-state {:type type, :label (:label data)})
        [form-data set-form-data] (uix.core/use-state initial-form-data)
        changed? (not= form-data initial-form-data)
        handle-save (fn []
                      (on-save id form-data)
                      (set-initial-form-data form-data)
                      (when (.-activeElement js/document)
                        (.blur (.-activeElement js/document))))
        handle-submit (fn [e]
                        (.preventDefault e)
                        (when changed?
                          (handle-save)))]
    ($ :form {:onSubmit handle-submit
              :class "fieldset node-form p-2 border-b border-b-1 border-base-300"}
       ($ :div {:class "flex justify-between"}
          ($ :h3 {:class "text-xs/4 mr-2 font-bold"} (:label form-data))
          ($ :button {:class "btn btn-primary btn-xs"
                      :disabled (not changed?)
                      :type "submit"}
             "Save"))

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

       ($ :p {:class "fieldset-label"} (str "id: " id)))))
