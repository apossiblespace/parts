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
   - :value               current committed text — shown when not editing
   - :on-commit           (fn [new-value]) — called only on a valid, changed commit
   - :validate            (optional) predicate on the trimmed draft; default non-blank
   - :display-class       CSS classes for the display element
   - :input-class         CSS classes for the <input> shown while editing
   - :aria-label          accessible label, applied in both states
   - :start-edit-trigger  (optional) any value — when it *changes*, the field
                          enters edit mode. The value itself doesn't matter;
                          only that it changed since the last render. Lets a
                          caller (e.g. a Rename menu item) request edit mode
                          without owning the rest of the field's state."
  [{:keys [value on-commit validate display-class input-class aria-label
           start-edit-trigger]}]
  (let [validate                (or validate (complement str/blank?))
        [editing? set-editing!] (use-state false)
        [draft set-draft!]      (use-state "")
        input-ref               (use-ref nil)
        ;; Synchronous re-entry guard. Committing on Enter unmounts the
        ;; <input>, which itself fires onBlur — without this flag both the
        ;; Enter path and the unmount-blur would run `finish`. The ref
        ;; flips the instant the first call runs, so the second is inert.
        done-ref                (use-ref false)
        ;; Skips the initial mount run of the `:start-edit-trigger` effect
        ;; below, so the field starts in display mode regardless of the
        ;; trigger's initial value.
        mounted?                (use-ref false)
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

    ;; External trigger: any *change* to `:start-edit-trigger` after mount
    ;; enters edit mode. The value is opaque — only the change matters.
    ;; `use-effect` always fires once on mount, so the `mounted?` ref skips
    ;; that first run; otherwise the field would open in edit mode on load.
    ;; (A `(when start-edit-trigger …)` guard does NOT work: in
    ;; ClojureScript `0` is truthy, so a caller's initial `0` would fire.)
    ;;
    ;; `^:lint/disable` on the deps: exhaustive-deps wants `start-edit`
    ;; listed, but it's a fresh closure every render — listing it would
    ;; re-run this effect on *every* render and force edit mode constantly.
    ;; We deliberately depend only on the trigger; the effect's closure is
    ;; recreated each render, so when it does fire it already sees the
    ;; current `value` via the latest `start-edit`.
    (use-effect
     (fn []
       (if @mounted?
         (start-edit)
         (reset! mounted? true))
       js/undefined)
     ^:lint/disable [start-edit-trigger])

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
