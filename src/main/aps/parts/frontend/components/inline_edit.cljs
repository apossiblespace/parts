(ns aps.parts.frontend.components.inline-edit
  "Pure commit logic shared by the inline-edit UI primitives
   (`inline-text-field`, and an eventual `inline-text-area`). Deliberately
   free of uix/React so it can be unit-tested directly under the cljs suite —
   the primitives are thin shells over this rule."
  (:require
   [clojure.string :as str]))

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
