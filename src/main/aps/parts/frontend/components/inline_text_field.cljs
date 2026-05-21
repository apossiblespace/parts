(ns aps.parts.frontend.components.inline-text-field
  "A reusable click-to-edit single-line text primitive.

   Pure props: it owns the *edit interaction* (display/edit toggle, focus,
   commit on Enter/blur, cancel on Escape) but knows nothing about
   re-frame or what the value means. Callers pass a `value` and an
   `on-commit` callback — the mechanism lives here, the policy stays with
   the caller.

   `on-commit` fires exactly when the user makes a real, valid change:
   the trimmed draft passes `validate` and differs from `value`. A no-op
   or invalid commit collapses to a silent cancel — no callback, the
   display simply falls back to `value`.

   Named for the single-line case on purpose — a multi-line sibling
   (`inline-text-area`) would be its own component."
  (:require
   [clojure.string :as str]
   [uix.core :refer [$ defui use-effect use-ref use-state]]))

(defn commit-value
  "Decide what a commit should persist. Given the user's `draft` text, the
   current committed `value`, and a `validate` predicate, return the value
   to persist — or nil to cancel.

   nil means cancel silently: an empty/whitespace draft, a draft that
   fails `validate`, or a no-op (trimmed draft equal to `value`)."
  [draft value validate]
  (let [trimmed (str/trim draft)]
    (when (and (validate trimmed)
               (not= trimmed value))
      trimmed)))

(defui inline-text-field
  "Click-to-edit single-line text. Props:
   - :value         current committed text — shown when not editing
   - :on-commit     (fn [new-value]) — called only on a valid, changed commit
   - :validate      (optional) predicate on the trimmed draft; default non-blank
   - :display-class CSS classes for the display element
   - :input-class   CSS classes for the <input> shown while editing
   - :aria-label    accessible label, applied in both states"
  [{:keys [value on-commit validate display-class input-class aria-label]}]
  (let [validate                (or validate (complement str/blank?))
        [editing? set-editing!] (use-state false)
        [draft set-draft!]      (use-state "")
        input-ref               (use-ref nil)
        ;; Synchronous re-entry guard. Committing on Enter unmounts the
        ;; <input>, which itself fires onBlur — without this flag both the
        ;; Enter path and the unmount-blur would run `finish`. The ref
        ;; flips the instant the first call runs, so the second is inert.
        done-ref                (use-ref false)
        start-edit              (fn []
                                  (reset! done-ref false)
                                  (set-draft! (or value ""))
                                  (set-editing! true))
        finish                  (fn [commit?]
                                  (when-not @done-ref
                                    (reset! done-ref true)
                                    (when commit?
                                      (when-let [v (commit-value draft value validate)]
                                        (on-commit v)))
                                    (set-editing! false)))]

    ;; Entering edit mode: focus the field and select its contents so the
    ;; user can immediately type a replacement.
    (use-effect
     (fn []
       (when (and editing? @input-ref)
         (.focus @input-ref)
         (.select @input-ref)))
     [editing?])

    (if editing?
      ($ :input
         {:ref         input-ref
          :class       input-class
          :type        "text"
          :value       draft
          :aria-label  aria-label
          :on-change   #(set-draft! (.. % -target -value))
          :on-blur     #(finish true)
          :on-key-down (fn [^js e]
                         (case (.-key e)
                           "Enter"  (do (.preventDefault e) (finish true))
                           "Escape" (do (.preventDefault e) (finish false))
                           nil))})
      ($ :span
         {:class      display-class
          :role       "button"
          :tabIndex   0
          :aria-label aria-label
          :on-click   start-edit}
         value))))
