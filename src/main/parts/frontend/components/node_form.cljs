(ns parts.frontend.components.node-form
  (:require
   [parts.common.constants :refer [part-labels]]
   [uix.core :refer [$ defui]]))

(defui node-form [{:keys [node on-save collapsed]}]
  (println "collapsed" collapsed)
  (let [{:keys [id type data]} node
        [initial-form-data set-initial-form-data] (uix.core/use-state {:type type, :label (:label data)})
        [form-data set-form-data] (uix.core/use-state initial-form-data)
        [collapsed? set-collapsed] (uix.core/use-state collapsed)
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
          ($ :h3 {:class "text-xs/4 mr-2 font-bold cursor-pointer w-full pl-3 relative"
                  :on-click #(set-collapsed (not collapsed?))}
             (if collapsed?
               ($ :svg
                  {:xmlns "http://www.w3.org/2000/svg"
                   :viewBox "0 0 16 16"
                   :fill "currentColor"
                   :class "size-4 absolute -left-1"}
                  ($ :path
                     {:fill-rule "evenodd"
                      :d "M6.22 4.22a.75.75 0 0 1 1.06 0l3.25 3.25a.75.75 0 0 1 0 1.06l-3.25 3.25a.75.75 0 0 1-1.06-1.06L8.94 8 6.22 5.28a.75.75 0 0 1 0-1.06Z"
                      :clip-rule "evenodd"}))
               ($ :svg
                  {:xmlns "http://www.w3.org/2000/svg"
                   :viewBox "0 0 16 16"
                   :fill "currentColor"
                   :class "size-4 absolute -left-1"}
                  ($ :path
                     {:fill-rule "evenodd"
                      :d "M4.22 6.22a.75.75 0 0 1 1.06 0L8 8.94l2.72-2.72a.75.75 0 1 1 1.06 1.06l-3.25 3.25a.75.75 0 0 1-1.06 0L4.22 7.28a.75.75 0 0 1 0-1.06Z"
                      :clip-rule "evenodd"})))
             ($ :span (:label form-data)))
          (when-not collapsed?
            ($ :button {:class "btn btn-primary btn-xs"
                        :disabled (not changed?)
                        :type "submit"}
               "Save")))
       (when-not collapsed?
         ($ :div
            ($ :label {:class "fieldset-label"} "Type:")
            ($ :select {:class "select select-sm mb-1"
                        :value (:type form-data)
                        :onChange #(set-form-data assoc :type (.. % -target -value))}
               (->> part-labels
                    (map (fn [[k {:keys [label]}]]
                           ($ :option {:key k :value k}
                              label)))))

            ($ :label {:class "fieldset-label"} "Label:")
            ($ :input {:class "input input-sm mb-1"
                       :type "text"
                       :value (:label form-data)
                       :onChange #(set-form-data assoc :label (.. % -target -value))})

            ($ :p {:class "fieldset-label"} (str "id: " id)))))))
