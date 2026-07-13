(ns aps.parts.frontend.components.inline-text-field
  "A reusable inline-edit single-line text primitive — the first of the app's
   UI primitives.

   Controlled on `:editing?`: the primitive owns the edit *mechanism* (display
   ↔ input toggle, focus + select on open, commit on Enter/blur, cancel on
   Escape, trim/validate/no-op detection, the synchronous re-entry guard), but
   the caller owns *when* editing happens. The caller holds the `editing?` bit
   and decides which gesture flips it — single-click for a toolbar title,
   double-click for a canvas node, a menu item or keyboard shortcut elsewhere.

   For ergonomics the component offers a built-in entry gesture via `:edit-on`
   (`:click` / `:double-click`), which simply calls `:on-edit-start`; pass
   `:none` to drive editing purely from outside (e.g. a double-click on a
   surrounding element). Either way the caller is the one that sets `:editing?`.

   `on-commit` fires exactly when the user makes a real, valid change: the
   trimmed input passes `validate` and differs from `value`. A no-op, blank, or
   invalid commit collapses to `on-cancel` instead — the name is never silently
   destroyed.

   Named for the single-line case on purpose — a multi-line sibling
   (`inline-text-area`) would be its own component."
  (:require
   [aps.parts.frontend.components.inline-edit :refer [commit-value]]
   [clojure.string :as str]
   [uix.core :refer [$ defui use-effect use-ref]]))

(defui inline-text-field
  "Inline-edit single-line text. Props:
   - :value          current committed text — shown when not editing
   - :editing?       controlled — render the <input> when true, the display span when false
   - :on-edit-start  (fn []) — fired by an `:edit-on` gesture; caller flips `:editing?` true
   - :on-commit      (fn [new-value]) — a valid, changed commit; caller applies it and flips `:editing?` false
   - :on-cancel      (fn []) — edit ended without a commit (Esc / blank / no-op); caller flips `:editing?` false
   - :edit-on        :click (default) | :double-click | :none — built-in entry gesture on the display span
   - :validate       (optional) predicate on the trimmed draft; default non-blank
   - :display-class  CSS classes for the display element
   - :display-text-class  (optional) wraps the display text in a span with
                     these classes — needed for `truncate`, which can't
                     ellipsize a bare text node inside a flex display element
   - :input-class    CSS classes for the <input> shown while editing
   - :aria-label     accessible label, applied in both states"
  [{:keys [value editing? on-edit-start on-commit on-cancel validate edit-on
           display-class display-text-class input-class aria-label]}]
  (let [validate  (or validate (complement str/blank?))
        edit-on   (or edit-on :click)
        input-ref (use-ref nil)
        ;; Synchronous re-entry guard. Committing on Enter blurs/unmounts the
        ;; <input>, which itself fires onBlur — without this flag both the
        ;; Enter path and the unmount-blur would run `finish`. The ref flips
        ;; the instant the first call runs, so the second is inert. It is
        ;; re-armed each time an edit session opens (the effect below).
        done-ref  (use-ref false)
        finish    (fn [commit?]
                    (when-not @done-ref
                      (reset! done-ref true)
                      (if-let [v (and commit?
                                      @input-ref
                                      (commit-value (.-value @input-ref) value validate))]
                        (on-commit v)
                        (when on-cancel (on-cancel)))))]

    ;; Opening an edit session: re-arm the guard, then focus + select so the
    ;; user can immediately type a replacement. The <input> renders with
    ;; `:default-value` (uncontrolled), so its text is already in the DOM by
    ;; the time this effect runs — `.select` highlights the whole value.
    (use-effect
     (fn []
       (when editing?
         (reset! done-ref false)
         (when @input-ref
           (.focus @input-ref)
           (.select @input-ref)))
       js/undefined)
     [editing?])

    (if editing?
      ($ :input
         {:ref           input-ref
          :class         input-class
          :type          "text"
          :default-value (or value "")
          :aria-label    aria-label
          :on-blur       #(finish true)
          :on-key-down   (fn [^js e]
                           (case (.-key e)
                             "Enter"  (do (.preventDefault e) (finish true))
                             "Escape" (do (.preventDefault e) (finish false))
                             nil))})
      ($ :span
         (cond-> {:class      display-class
                  :aria-label aria-label}
           (= edit-on :click)        (assoc :role     "button"
                                            :tabIndex 0
                                            :on-click (fn [] (when on-edit-start (on-edit-start))))
           (= edit-on :double-click) (assoc :on-double-click (fn [] (when on-edit-start (on-edit-start)))))
         (if display-text-class
           ($ :span {:class display-text-class} value)
           value)))))
