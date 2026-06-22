(ns hooks.re-export
  "clj-kondo expansion for aps.parts.ops/re-export.

   The macro defines a local var named after its source symbol, so to the
   linter `(re-export billing/billing-status!)` should look like
   `(def billing-status! billing/billing-status!)`: it registers the new var
   and counts the source as used."
  (:require
   [clj-kondo.hooks-api :as api]))

(defn re-export [{:keys [node]}]
  (let [[_ target] (:children node)
        target-sym (api/sexpr target)
        local-name (symbol (name target-sym))
        new-node (api/list-node
                  [(api/token-node 'def)
                   (with-meta (api/token-node local-name) (meta target))
                   target])]
    {:node (with-meta new-node (meta node))}))
