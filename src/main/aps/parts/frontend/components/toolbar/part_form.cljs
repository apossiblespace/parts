(ns aps.parts.frontend.components.toolbar.part-form
  (:require
   [aps.parts.common.constants :refer [color-tags part-labels part-type-order
                                       max-text-length]]
   [aps.parts.common.observe :as o]
   [aps.parts.frontend.components.body-location :refer [location-field]]
   [aps.parts.frontend.components.toolbar.form :as form]
   [aps.parts.frontend.state.conversations :as c]
   [re-frame.core :as rf]
   [uix.core :refer [$ defui use-effect]]
   [uix.re-frame :as uix.rf]))

(defui ^:private conversation-preview
  "The Part's latest two conversation entries, and a button opening the
   conversation window in Part mode (ADR-0018)."
  [{:keys [part]}]
  (let [entries (uix.rf/use-subscribe [:canvas/conversation-entries])
        latest  (take-last 2 (c/for-part entries (:id part)))]
    ($ :div {:class "mt-1 mb-3"}
       ($ :div {:class "flex items-center justify-between mb-1"}
          ($ :label {:class "fieldset-label"} "Conversation:")
          ($ :button {:type     "button"
                      :class    "btn btn-xs"
                      :on-click #(rf/dispatch [:conversation/show :part])}
             "Open"))
       (if (empty? latest)
         ($ :p {:class "text-xs text-base-content/50 italic"}
            "Nothing recorded yet")
         ($ :ul {:class "space-y-1"}
            (for [e latest]
              ($ :li {:key (:id e) :class "text-xs line-clamp-2"}
                 ($ :span {:class "font-semibold"}
                    (c/speaker-label (:speaker e) part) ": ")
                 (:text e))))))))

(defui ^:private color-tag-picker
  "The Colour tag row: \"No colour tag\" then the palette, as round
   swatches. Native radios sharing one name per Part, so arrow keys move
   between them and each is announced by its label."
  [{:keys [part-id value on-change]}]
  ($ :div {:class "mt-1 mb-3"}
     ($ :span
        {:class "fieldset-label" :id (str "color-tag-" part-id)}
        "Colour tag:")
     ($ :div {:class           "flex items-center gap-1.5 mt-1"
              :role            "radiogroup"
              :aria-labelledby (str "color-tag-" part-id)}
        (for [[tag label hex] (cons [nil "No colour tag" nil]
                                    (map (fn [[k {:keys [label hex]}]] [k label hex])
                                         color-tags))]
          ($ :input {:key        (or tag "none")
                     :type       "radio"
                     :name       (str "color-tag-" part-id)
                     :class      (str "color-tag" (when-not tag " none"))
                     :style      (when hex #js {"--tag" hex})
                     :aria-label label
                     :title      label
                     :checked    (= tag value)
                     :onChange   #(on-change tag)})))))

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
  (let [{:keys [id type label notes unburdened color_tag body_location]}
        part

        ;; The notes window (ADR-0017) owns this Part's notes while it is
        ;; open on them: the quick editor below is disabled.
        notes-scope?                                                     (= id (uix.rf/use-subscribe [:notes/scope-id]))

        notes-in-window?
        (and (uix.rf/use-subscribe [:ui/window-open? :notes]) notes-scope?)

        minimal
        (uix.rf/use-subscribe [:minimal-demo])

        {:keys [values collapsed? update-field toggle-collapsed
                commit-field! text-blur text-keys]}
        (form/use-autosave-form
         {:entity-id    id
          :fields       {:type       type
                         :label      label
                         :color_tag  color_tag
                         :unburdened (boolean unburdened)
                         :notes      notes}
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

            ($ color-tag-picker {:part-id   id
                                 :value     (:color_tag values)
                                 :on-change #(commit-field! :color_tag %)})

            ($ :label {:class "fieldset-label"} "Burden:")
            ($ :label
               {:class "label text-sm text-base-content mt1 mb-2 cursor-pointer"}
               ($ :input {:type     "checkbox"
                          :class    "checkbox checkbox-xs"
                          :checked  (:unburdened values)
                          :onChange #(commit-field! :unburdened
                                                    (.. % -target -checked))})
               "Unburdened?")

            ($ form/notes-header {:disabled? (not notes-scope?)})
            ($ :textarea {:max-length max-text-length
                          :class      "textarea textarea-sm mb-1"
                          :value      (:notes values)
                          :disabled   notes-in-window?
                          :title      (when notes-in-window?
                                        "Editing in the notes window")
                          :onChange   #(update-field :notes
                                                     (.. % -target -value))
                          :on-blur    text-blur
                          :onKeyDown  (text-keys :notes)})

            ;; The marketing hero demo has no floating windows.
            (when-not minimal
              ($ :<>
                 ($ conversation-preview {:part part})
                 ($ location-field
                    {:location body_location
                     :on-open  #(rf/dispatch
                                 [:window/open :body-location])}))))))))
