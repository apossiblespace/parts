(ns parts.frontend.components.edge-form
  (:require
   [parts.common.constants :refer [relationship-labels]]
   [uix.core :refer [$ defui use-effect use-state]]))

(defui edge-form
  "Form for viewing and editing edge properties, to render in the sidebar.
   Props:
   - edge: The edge data to edit
   - on-save: Callback function (id, form-data) when data is saved
   - collapsed: Whether the form should start collapsed"
  [{:keys [edge on-save collapsed]}]
  (let [{:keys [id source target data]} edge
        [form-state set-form-state] (use-state
                                      {:values {:relationship (:relationship data)}
                                       :initial {:relationship (:relationship data)}
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
                          (handle-save)))
        relationship-type (keyword (:relationship values))]

    (use-effect
      (fn []
        (set-form-state
          (fn [state]
            (-> state
                (assoc-in [:values :relationship] (:relationship data))
                (assoc-in [:initial :relationship] (:relationship data))
                (assoc :collapsed? collapsed)))))
      [data id collapsed])

    ($ :form {:onSubmit handle-submit
              :class "fieldset edge-form p-2 border-b border-b-1 border-base-300"}
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
             ($ :span (get-in relationship-labels [(keyword (:relationship values)) :label] "Relationship")))
          (when-not collapsed?
            ($ :button {:class "btn btn-primary btn-xs"
                        :disabled (not changed?)
                        :type "submit"}
               "Save")))
       (when-not collapsed?
         ($ :div
            ($ :label {:class "fieldset-label"} "Relationship type:")
            ($ :select {:class "select select-sm mb-1"
                        :value (:relationship values)
                        :onChange #(update-field :relationship (.. % -target -value))}
               (->> relationship-labels
                    (map (fn [[k {:keys [label]}]]
                           ($ :option {:key k :value (name k)}
                              label)))))

            ($ :div {:class "flex justify-between text-xs text-base-content/70 mt-2"}
              ($ :span (str "From: " source))
              ($ :span (str "To: " target)))

            ($ :p {:class "fieldset-label"} (str "id: " id)))))))
