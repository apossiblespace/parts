(ns parts.frontend.components.toolbar.part-form
  (:require
   [parts.common.constants :refer [part-labels]]
   [uix.core :refer [$ defui use-effect use-state]]
   [uix.re-frame :as uix.rf]))

(defui part-form
  "Form for viewing and editing part properties, to render in the sidebar.
   Props:
   - part: The part model to edit
   - on-save: Callback function (id, form-data) when data is saved
   - collapsed: Whether the form should start collapsed"
  [{:keys [part on-save collapsed]}]
  (let [{:keys [id type label notes]} part
        demo (uix.rf/use-subscribe [:demo])
        [form-state set-form-state] (use-state
                                     {:values {:type type
                                               :label label
                                               :notes notes}
                                      :initial {:type type
                                                :label label
                                                :notes notes}
                                      :collapsed? collapsed})
        {:keys [values initial collapsed?]} form-state
        changed? (not= values initial)
        update-field (fn [field value]
                       (set-form-state
                        (fn [state]
                          (assoc-in state [:values field] value))))
        toggle-collapsed (fn []
                           (set-form-state
                            (fn [state]
                              (update state :collapsed? not))))
        handle-save (fn []
                      (on-save id values)
                      (set-form-state
                       (fn [state]
                         (assoc state :initial (:values state))))
                      (when (.-activeElement js/document)
                        (.blur (.-activeElement js/document))))
        handle-submit (fn [e]
                        (.preventDefault e)
                        (when changed?
                          (handle-save)))]

    (use-effect
     (fn []
       (set-form-state
        (fn [state]
          (-> state
              (assoc-in [:values :type] type)
              (assoc-in [:values :label] label)
              (assoc-in [:values :notes] notes)
              (assoc-in [:initial :type] type)
              (assoc-in [:initial :label] label)
              (assoc-in [:initial :notes] notes)
              (assoc :collapsed? collapsed)))))
     [label type notes id collapsed])

    ($ :form {:onSubmit handle-submit
              :class "fieldset node-form p-2 border-b border-b-1 border-base-300"}
       ($ :div {:class "flex justify-between"}
          ($ :h3 {:class "text-xs/4 mr-2 font-bold cursor-pointer w-full pl-3 relative"
                  :on-click toggle-collapsed}
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
             ($ :span (:label values)))
          (when-not collapsed?
            ($ :button {:class "btn btn-primary btn-xs"
                        :disabled (not changed?)
                        :type "submit"}
               "Save")))
       (when-not collapsed?
         ($ :div
            ($ :label {:class "fieldset-label"} "Type:")
            ($ :select {:class "select select-sm mb-1"
                        :value (:type values)
                        :onChange #(update-field :type (.. % -target -value))}
               (->> part-labels
                    (map (fn [[k {:keys [label]}]]
                           ($ :option {:key k :value k}
                              label)))))

            ($ :label {:class "fieldset-label"} "Label:")
            ($ :input {:class "input input-sm mb-1"
                       :type "text"
                       :value (:label values)
                       :onChange #(update-field :label (.. % -target -value))})

            ($ :label {:class "fieldset-label"} "Notes:")
            ($ :textarea {:class "textarea textarea-sm mb-1"
                          :value (:notes values)
                          :onChange #(update-field :notes (.. % -target -value))})

            (when-not demo
              ($ :p {:class "fieldset-label"} (str "id: " id))))))))
