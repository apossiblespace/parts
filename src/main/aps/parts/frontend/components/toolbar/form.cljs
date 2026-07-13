(ns aps.parts.frontend.components.toolbar.form
  "Shared machinery for the sidebar's autosaving entity forms (part /
   relationship). Saving is silent (see CONTEXT.md): text fields commit
   on blur — which includes deselecting the entity — with Escape
   reverting the field, and discrete controls commit on change. One
   implementation so the two forms cannot drift."
  (:require
   [aps.parts.frontend.components.inline-edit :as inline-edit]
   [clojure.string :as str]
   [uix.core :refer [$ defui use-effect use-ref use-state]]))

(defn use-autosave-form
  "State + handlers for an autosaving sidebar form.

   Opts:
   - :entity-id     the edited entity's id — local state re-syncs from
                    `fields` only when this changes (syncing per-field
                    would let a discrete commit's optimistic echo clobber
                    an uncommitted draft in another field)
   - :fields        the entity's current field map
   - :collapsed     whether the form starts collapsed
   - :on-save       (fn [values]) — called with the full field map on
                    each commit
   - :revert-blank  (optional) field keyword whose blank drafts revert to
                    the previous value instead of committing (the canvas
                    inline-label idiom); the kept value is also trimmed,
                    via the same `commit-value` rule the map name uses

   Returns {:values :collapsed? :update-field :toggle-collapsed
            :commit-field! :text-blur :text-keys}. `text-keys` takes the
   field keyword and an optional `:blur-on-enter?` for single-line
   inputs (Enter in a textarea stays a newline)."
  [{:keys [entity-id fields collapsed on-save revert-blank]}]
  (let [[form-state set-form-state]         (use-state {:values     fields
                                                        :initial    fields
                                                        :collapsed? collapsed})
        {:keys [values initial collapsed?]} form-state
        ;; Set by Escape so the blur it triggers doesn't commit the
        ;; just-reverted draft.
        skip-blur-commit?                   (use-ref false)
        update-field                        (fn [field value]
                                              (set-form-state
                                               (fn [state]
                                                 (assoc-in state [:values field] value))))
        toggle-collapsed                    (fn []
                                              (set-form-state
                                               (fn [state]
                                                 (update state :collapsed? not))))
        commit!                             (fn [vals]
                                              (when (not= vals initial)
                                                (on-save vals)
                                                (set-form-state
                                                 (fn [state]
                                                   (assoc state :values vals :initial vals)))))
        commit-field!                       (fn [field value]
                                              (commit! (assoc values field value)))
        text-blur                           (fn [_e]
                                              (if @skip-blur-commit?
                                                (reset! skip-blur-commit? false)
                                                (let [final (if revert-blank
                                                              (assoc values revert-blank
                                                                     (or (inline-edit/commit-value
                                                                          (get values revert-blank)
                                                                          (get initial revert-blank)
                                                                          (complement str/blank?))
                                                                         (get initial revert-blank)))
                                                              values)]
                                                  ;; Snap a reverted field back on-screen even
                                                  ;; when the commit itself no-ops.
                                                  (when (not= final values)
                                                    (set-form-state
                                                     (fn [state] (assoc state :values final))))
                                                  (commit! final))))
        text-keys                           (fn [field & {:keys [blur-on-enter?]}]
                                              (fn [^js e]
                                                (case (.-key e)
                                                  "Escape" (do (.preventDefault e)
                                                               (reset! skip-blur-commit? true)
                                                               (update-field field (get initial field))
                                                               (.blur (.-target e)))
                                                  "Enter"  (when blur-on-enter?
                                                             (.preventDefault e)
                                                             (.blur (.-target e)))
                                                  nil)))]
    (use-effect
     (fn []
       (set-form-state
        (fn [state]
          (assoc state :values fields :initial fields :collapsed? collapsed))))
     ^:lint/disable [entity-id collapsed])
    {:values           values
     :collapsed?       collapsed?
     :update-field     update-field
     :toggle-collapsed toggle-collapsed
     :commit-field!    commit-field!
     :text-blur        text-blur
     :text-keys        text-keys}))

(def ^:private chevron-right
  ($ :svg
     {:xmlns   "http://www.w3.org/2000/svg"
      :viewBox "0 0 16 16"
      :fill    "currentColor"
      :class   "size-4 absolute -left-1"}
     ($ :path
        {:fill-rule "evenodd"
         :d         "M6.22 4.22a.75.75 0 0 1 1.06 0l3.25 3.25a.75.75 0 0 1 0 1.06l-3.25 3.25a.75.75 0 0 1-1.06-1.06L8.94 8 6.22 5.28a.75.75 0 0 1 0-1.06Z"
         :clip-rule "evenodd"})))

(def ^:private chevron-down
  ($ :svg
     {:xmlns   "http://www.w3.org/2000/svg"
      :viewBox "0 0 16 16"
      :fill    "currentColor"
      :class   "size-4 absolute -left-1"}
     ($ :path
        {:fill-rule "evenodd"
         :d         "M4.22 6.22a.75.75 0 0 1 1.06 0L8 8.94l2.72-2.72a.75.75 0 1 1 1.06 1.06l-3.25 3.25a.75.75 0 0 1-1.06 0L4.22 7.28a.75.75 0 0 1 0-1.06Z"
         :clip-rule "evenodd"})))

(defui collapsible-header
  "The entity form's title row: a chevron + title, clicking toggles the
   form body."
  [{:keys [title collapsed? on-toggle]}]
  ($ :div {:class "flex justify-between"}
     ($ :h3 {:class    "text-xs/4 mr-2 font-bold cursor-pointer w-full pl-3 relative"
             :on-click on-toggle}
        (if collapsed? chevron-right chevron-down)
        ($ :span title))))
