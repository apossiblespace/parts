(ns aps.parts.frontend.components.toolbar.part-form
  (:require
   [aps.parts.common.constants :refer [part-labels]]
   [aps.parts.common.observe :as o]
   [aps.parts.frontend.components.body-location :refer [location-field]]
   [aps.parts.frontend.components.toolbar.form :as form]
   [uix.core :refer [$ defui use-effect]]))

(defui part-form
  "Form for viewing and editing part properties, to render in the sidebar.
   Autosaving — the commit semantics live in `form/use-autosave-form`;
   the label is the blank-reverting field.
   Props:
   - part: The part model to edit
   - on-save: Callback function (id, form-data) on each commit
   - collapsed: Whether the form should start collapsed"
  [{:keys [part on-save collapsed]}]
  (let [{:keys [id type label notes body_location]} part
        {:keys [values collapsed? update-field toggle-collapsed
                commit-field! text-blur text-keys]}
        (form/use-autosave-form
         {:entity-id    id
          :fields       {:type          type
                         :label         label
                         :notes         notes
                         :body_location body_location}
          :collapsed    collapsed
          :revert-blank :label
          :on-save      (fn [vals]
                          (o/track "Part saved" {:type (:type vals)})
                          (on-save id vals))})]

    (use-effect
     (fn [] (o/debug "part-form" "Part" id))
     [id])

    ($ :div {:class "fieldset node-form p-2 border-b border-b-1 border-base-300"}
       ($ form/collapsible-header {:title      (:label values)
                                   :collapsed? collapsed?
                                   :on-toggle  toggle-collapsed})
       (when-not collapsed?
         ($ :div
            ($ :label {:class "fieldset-label"} "Type:")
            ($ :select {:class    "select select-sm mb-1"
                        :value    (:type values)
                        :onChange #(commit-field! :type (.. % -target -value))}
               (->> part-labels
                    (map (fn [[k {:keys [label]}]]
                           ($ :option {:key k :value k}
                              label)))))

            ($ :label {:class "fieldset-label"} "Label:")
            ($ :input {:class     "input input-sm mb-1"
                       :type      "text"
                       :value     (:label values)
                       :onChange  #(update-field :label (.. % -target -value))
                       :on-blur   text-blur
                       :onKeyDown (text-keys :label :blur-on-enter? true)})

            ($ :label {:class "fieldset-label"} "Notes:")
            ($ :textarea {:class     "textarea textarea-sm mb-1"
                          :value     (:notes values)
                          :onChange  #(update-field :notes (.. % -target -value))
                          :on-blur   text-blur
                          :onKeyDown (text-keys :notes)})

            ($ location-field {:location  (:body_location values)
                               :on-change #(commit-field! :body_location %)}))))))
