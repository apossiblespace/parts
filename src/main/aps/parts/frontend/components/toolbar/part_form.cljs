(ns aps.parts.frontend.components.toolbar.part-form
  (:require
   ["lucide-react/dist/esm/icons/maximize-2" :default Maximize2]
   [aps.parts.common.constants :refer [part-labels part-type-order]]
   [aps.parts.common.observe :as o]
   [aps.parts.frontend.components.body-location :refer [location-field]]
   [aps.parts.frontend.components.toolbar.form :as form]
   [re-frame.core :as rf]
   [uix.core :refer [$ defui use-effect]]
   [uix.re-frame :as uix.rf]))

(defui part-form
  "Form for viewing and editing part properties, to render in the sidebar.
   Autosaving — the commit semantics live in `form/use-autosave-form`;
   the label is the blank-reverting field.
   Props:
   - part: The part model to edit
   - on-save: Callback function (id, form-data) on each commit
   - on-delete: Callback function (id) — asks for the part's deletion
     (confirmation included, same flow as the Delete key)
   - collapsed: Whether the form should start collapsed"
  [{:keys [part on-save on-delete collapsed]}]
  (let [{:keys [id type label notes body_location]} part
        ;; The notes window (ADR-0017) owns notes while it is open: the
        ;; quick editor below is disabled so two editors never race.
        notes-window-open?                          (uix.rf/use-subscribe [:ui/window-open? :notes])
        ;; body_location is not a form field: it is edited in its
        ;; floating window (ADR-0017), which saves on its own. Keeping
        ;; it out of `fields` means a notes commit can never write a
        ;; stale point over the window's save.
        {:keys [values collapsed? update-field toggle-collapsed
                commit-field! text-blur text-keys]}
        (form/use-autosave-form
         {:entity-id    id
          :fields       {:type  type
                         :label label
                         :notes notes}
          :collapsed    collapsed
          :revert-blank :label
          :on-save      #(on-save id %)})]

    (use-effect
     (fn [] (o/debug "part-form" "Part" id))
     [id])

    ($ :div {:class "fieldset node-form p-2 border-b border-b-1 border-base-300"}
       ($ form/collapsible-header {:title        (:label values)
                                   :collapsed?   collapsed?
                                   :on-toggle    toggle-collapsed
                                   :on-delete    (when on-delete #(on-delete id))
                                   :delete-label "Delete part"})
       (when-not collapsed?
         ($ :div
            ($ :label {:class "fieldset-label"} "Type:")
            ($ :select {:class    "select select-sm mb-1"
                        :value    (:type values)
                        :onChange #(commit-field! :type (.. % -target -value))}
               (->> part-type-order
                    (map (fn [k]
                           ($ :option {:key k :value k}
                              (get-in part-labels [k :label]))))))

            ($ :label {:class "fieldset-label"} "Label:")
            ($ :input {:class     "input input-sm mb-1"
                       :type      "text"
                       :value     (:label values)
                       :onChange  #(update-field :label (.. % -target -value))
                       :on-blur   text-blur
                       :onKeyDown (text-keys :label :blur-on-enter? true)})

            ;; Same row shape as Body location's (label left, small button
            ;; right, gap below), so the textarea stays a plain textarea
            ;; with its scrollbar and grip where they belong.
            ($ :div {:class "flex items-center justify-between mb-1"}
               ($ :label {:class "fieldset-label"} "Notes:")
               ($ :button {:type       "button"
                           :class      "btn btn-xs btn-square"
                           :aria-label "Open notes in a window"
                           :title      "Open notes in a window"
                           :on-click   #(rf/dispatch [:window/open :notes])}
                  ($ Maximize2 {:size 12})))
            ($ :textarea {:class     "textarea textarea-sm mb-1"
                          :value     (:notes values)
                          :disabled  notes-window-open?
                          :title     (when notes-window-open? "Editing in the notes window")
                          :onChange  #(update-field :notes (.. % -target -value))
                          :on-blur   text-blur
                          :onKeyDown (text-keys :notes)})

            ($ location-field {:location body_location
                               :on-open  #(rf/dispatch [:window/open :body-location])}))))))
