(ns aps.parts.frontend.components.toolbar.relationship-form
  (:require
   [aps.parts.common.constants :refer [relationship-colors relationship-labels max-text-length]]
   [aps.parts.common.observe :as o]
   [aps.parts.frontend.components.relationship-type-dropdown :refer [relationship-type-dropdown]]
   [aps.parts.frontend.components.toolbar.form :as form]
   [re-frame.core :as rf]
   [uix.core :refer [$ defui use-effect use-ref]]
   [uix.re-frame :as uix.rf]))

(defui relationship-form
  "Form for viewing and editing relationship properties, to render in the
   sidebar. Autosaving — the commit semantics live in
   `form/use-autosave-form`; the type commits on selection so the canvas
   recolours immediately.
   Props:
   - relationship: The relationship data to edit
   - on-save: Callback function (id, form-data) on each commit
   - on-delete: Callback function (id) — asks for the relationship's
     deletion (confirmation included, same flow as the Delete key)
   - collapsed: Whether the form should start collapsed
   - read-only?: The canvas refuses edits: the fields are disabled, but
     the notes window still opens"
  [{:keys [relationship on-save on-delete collapsed read-only?]}]
  (let [{:keys [id type notes intensity source_id target_id]} relationship
        notes-scope?                                          (= id (uix.rf/use-subscribe [:notes/scope-id]))
        notes-in-window?                                      (and (uix.rf/use-subscribe [:ui/window-open? :notes]) notes-scope?)
        pending-preview                                       (use-ref nil)
        preview-raf                                           (use-ref nil)
        {:keys [values collapsed? update-field toggle-collapsed
                commit-field! text-blur text-keys]}
        (form/use-autosave-form
         {:entity-id id
          :fields    {:type      type
                      :notes     notes
                      :intensity (or intensity 0)}
          :collapsed collapsed
          :on-save   #(on-save id %)})]

    (use-effect
     (fn [] (o/debug "relationship-form" "Relationship" id "from:" source_id "to:" target_id))
     [id source_id target_id])

    ;; Deselection unmounts mid-gesture — don't let an orphaned preview
    ;; overlay outlive the form.
    (use-effect
     (fn []
       (fn [] (rf/dispatch [:map/relationship-preview-clear])))
     [id])

    ($ :div {:class "fieldset edge-form p-2 border-b border-b-1 border-base-300"}
       ($ form/collapsible-header
          {:title        (get-in relationship-labels
                                 [(keyword (:type values)) :label]
                                 "Relationship")
           :collapsed?   collapsed?
           :on-toggle    toggle-collapsed
           :on-delete    (when on-delete #(on-delete id))
           :delete-label "Delete relationship"})
       (when-not collapsed?
         ($ :div
            ;; Only the editing controls: the notes window still opens on a
            ;; read-only canvas (see part-form).
            ($ :fieldset {:class "contents" :disabled read-only?}
               ($ :label {:class "fieldset-label"} "Relationship type:")
               ;; The form holds types as strings (the model's format); the
               ;; dropdown speaks keywords.
               (let [type-kw    (keyword (:type values))
                     type-label (get-in relationship-labels [type-kw :label])]
                 ;; The dropdown is not a form control, so the fieldset does
                 ;; not disable it: read-only shows the same trigger, inert (the
                 ;; disabled fieldset still greys it).
                 (if read-only?
                   ($ :div {:class         "select select-sm mb-1 w-full flex items-center gap-1.5"
                            :aria-disabled true
                            :aria-label    (str "Relationship type: " type-label)}
                      ($ :span {:class "type-dot"
                                :style {:background-color (relationship-colors type-kw)}})
                      type-label)
                   ($ relationship-type-dropdown
                      {:selected           type-kw
                       :on-select          #(commit-field! :type (name %))
                       :dropdown-class     "w-full"
                       :trigger-class      "select select-sm mb-1 w-full flex items-center gap-1.5"
                       :trigger-aria-label (str "Relationship type: " type-label)}
                      ($ :span {:class "type-dot"
                                :style {:background-color (relationship-colors type-kw)}})
                      type-label)))

               ($ :label {:class "fieldset-label"} "Intensity:")
               ;; A slider is continuous, so the autosave convention
               ;; splits: every tick previews (ephemeral overlay), gesture
               ;; end commits the ONE change event. The preview dispatch
               ;; is RAF-coalesced: pointer events can outpace the display.
               (let [commit-intensity #(commit-field! :intensity
                                                      (:intensity values))]
                 ($ :input {:class         "range range-xs mb-1"
                            :type          "range"
                            :min           0
                            :max           100
                            :step          1
                            :aria-label    "Intensity"
                            :value         (:intensity values)
                            :onChange      (fn [e]
                                             (let [v (js/parseFloat
                                                      (.. e -target -value))]
                                               (update-field :intensity v)
                                               (reset! pending-preview v)
                                               (when-not @preview-raf
                                                 (reset! preview-raf
                                                         (js/requestAnimationFrame
                                                          (fn []
                                                            (reset! preview-raf nil)
                                                            (rf/dispatch
                                                             [:map/relationship-preview
                                                              id
                                                              {:intensity @pending-preview}])))))))
                            ;; A canvas-pane deselect never blurs the
                            ;; slider (ReactFlow prevents default on
                            ;; mousedown), so blur alone would lose
                            ;; keyboard adjustments — commit on every
                            ;; gesture end.
                            :on-pointer-up commit-intensity
                            :on-key-up     commit-intensity
                            :on-blur       commit-intensity})))

            ($ form/notes-header {:disabled? (not notes-scope?)})
            ($ :textarea {:max-length max-text-length
                          :class      "textarea textarea-sm mb-1"
                          :value      (:notes values)
                          :disabled   (or read-only? notes-in-window?)
                          :title      (when notes-in-window? "Editing in the notes window")
                          :onChange   #(update-field :notes (.. % -target -value))
                          :on-blur    text-blur
                          :onKeyDown  (text-keys :notes)}))))))
