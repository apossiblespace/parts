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

(defn resync
  "Form `state` after the entity's fields moved from `prev` to `fields`
   underneath the form (a floating window saved one). A changed field
   takes the new value unless it is mid-edit here. Comparing with `prev`,
   not with the last commit, stops a commit's own in-flight echo from
   putting the old value back. Returns `state` itself when nothing
   applies, so React bails out."
  [state prev fields]
  (reduce (fn [st [k v]]
            (if (and (not= v (get prev k))
                     (= (get-in st [:values k]) (get-in st [:initial k])))
              (-> st (assoc-in [:values k] v) (assoc-in [:initial k] v))
              st))
          state fields))
