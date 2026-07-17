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
     (ops/print-invitation-links!)
     (ops/send-invitation-email! (ops/generate-invitation! \"jane@example.com\"))

   `send-invitation-email!` is the one var that doesn't forward — a
   short-lived concierge helper defined below, see its section comment."
  (:require
   [aps.parts.billing :as billing]
   [aps.parts.config :as conf]
   [aps.parts.invitations :as invitations]
   [aps.parts.stats :as stats]
   [clojure.string :as cstr]
   [com.brunobonacci.mulog :as mulog]
   [postal.core :as postal]))

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

;; =============================================================================
;; Invitation email — a short-lived concierge helper for the Founding Circle
;; rollout, and deliberately self-contained: the operator's personal address is
;; hard-coded because replies must land with a human, and the SMTP connection
;; rule is a knowing duplicate of the private one in `aps.parts.alerts`, which
;; must stay a closed alert sink rather than become a shared mailer (the real
;; transactional mailer is TASK-014). Nothing records that an email was sent;
;; the mulog `::invitation-email-sent` event is the send trail.

(def ^:private invite-from "gosha@gosha.net")

(def ^:private invite-subject
  "Your invite to Parts, the mapping tool for IFS practitioners")

(def ^:private invite-body-template
  "Hello!

My name is Gosha, I’m one of the creators of Parts (https://parts.ifs.tools), the IFS parts mapping tool.

You’re receiving this email because you requested to be invited to Parts when it’s ready, and your turn has finally come. Your unique invite link is:

[LINK]

(This link is tied to your email address and is only valid to create one account, so please don’t share it!)


Before you dive in, you may want to watch this video walkthrough of Parts that we’ve put together: https://www.youtube.com/watch?v=72YCRfGvcjU

This should help you understand the basic functionality of Parts and start building the maps for your clients.

If you have questions, thoughts, ideas, feature requests, bug reports, or anything else, just hit reply -- I’m sending this from my personal email address, so I will definitely see your message and get back to you quickly.

Please give Parts a try, and let me know how you get on, and how I can help you help your clients.

Thank you for joining us!

Gosha

-- 
https://gosha.net")

(defn invite-message
  "The postal message map for an invite — the pure, testable core of
   `send-invitation-email!`. Plain text; the invite's magic link fills the
   [LINK] placeholder in the body."
  [{:keys [email magic-link]}]
  {:from    invite-from
   :to      email
   :subject invite-subject
   :body    (cstr/replace invite-body-template "[LINK]" magic-link)})

(defn- postal-connection
  "The postal connection map for an SMTP config: 587 is STARTTLS (`:tls`),
   465 (and anything else) is implicit SSL (`:ssl`)."
  [{:keys [host port user pass]}]
  (assoc {:host host :port port :user user :pass pass}
         (if (= 587 port) :tls :ssl) true))

(defn send-invitation-email!
  "Email `invite` — the map returned by `generate-invitation!` — its magic
   link, from the operator's personal address over the operator SMTP
   (`config/smtp-config`). Nil-safe: a nil invite (email already redeemed)
   returns nil without sending, so this composes:

     (send-invitation-email! (generate-invitation! \"jane@example.com\"))

   Throws if SMTP is unconfigured or the send fails — the operator at the
   REPL must see a failed send. Returns the invite on success."
  [invite]
  (when invite
    (let [smtp   (or (conf/smtp-config)
                     (throw (ex-info "SMTP is not configured (PARTS__SMTP__* / PARTS__ALERT__*)"
                                     {:type :config-error})))
          result (postal/send-message (postal-connection smtp)
                                      (invite-message invite))]
      (when-not (= :SUCCESS (:error result))
        (throw (ex-info "Invite email send failed"
                        {:type :smtp-error :result result :email (:email invite)})))
      (mulog/log ::invitation-email-sent :email (:email invite))
      (println (str "Sent invite to " (:email invite)))
      invite)))
