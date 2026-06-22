(ns aps.parts.ops
  "Operator console — one namespace to require in the production REPL that
   gathers the interactive operator helpers otherwise scattered across the
   domain namespaces. Every var here forwards to its source var, resolved at
   call time, so reloading a domain namespace in the REPL is picked up
   immediately; the source's docstring and arglists are carried over so
   `(doc …)` and arg hints still work.

   Erasure is deliberately *not* re-exported — hard-deleting an account should
   stay an explicit reach into `aps.parts.db.erasure`, never a one-alias
   console convenience.

     (require '[aps.parts.ops :as ops])
     (ops/fleet-stats!)
     (ops/user-stats! \"jane@example.com\")
     (ops/billing-status!)
     (ops/set-paid-through! \"jane@example.com\")
     (ops/print-invitation-links!)"
  (:require
   [aps.parts.billing :as billing]
   [aps.parts.invitations :as invitations]
   [aps.parts.stats :as stats]))

(defmacro ^:private re-export
  "Define a local var named like `target` (a qualified symbol) that forwards
   to the source var, dereferenced at call time so source reloads are tracked,
   copying over `:doc` and `:arglists` so REPL help is preserved through the
   facade."
  [target]
  (let [sym (symbol (name target))]
    `(do (def ~sym (fn [& args#] (apply @(var ~target) args#)))
         (alter-meta! (var ~sym) merge
                      (select-keys (meta (var ~target)) [:doc :arglists]))
         (var ~sym))))

;; Billing — concierge account standing
(re-export billing/billing-status!)
(re-export billing/set-paid-through!)
(re-export billing/clear-paid-through!)

;; Stats — account & fleet figures
(re-export stats/user-stats!)
(re-export stats/fleet-stats!)

;; Invitations — onboarding
(re-export invitations/generate-invitation!)
(re-export invitations/revoke-invitation!)
(re-export invitations/pending-waitlist!)
(re-export invitations/print-invitation-links!)
