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
     (ops/invite-pending-waitlist!)

   `send-invitation-email!` and `invite-pending-waitlist!` are the vars
   that don't forward — concierge helpers defined below, see their
   section comment."
  (:require
   [aps.parts.billing :as billing]
   [aps.parts.config :as conf]
   [aps.parts.invitations :as invitations]
   [aps.parts.mail :as mail]
   [aps.parts.stats :as stats]
   [clojure.string :as cstr]
   [com.brunobonacci.mulog :as mulog]))

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

;; Billing — account standing and operator adjustments
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
;; Invitation email — the operator-facing compose-and-send for invite magic
;; links, delegating the actual send to `aps.parts.mail` (TASK-014, ADR-0016).
;; The From is the mail layer's configured sender on the verified domain;
;; Reply-To is the operator's personal address (`:mail/reply-to`) because
;; replies must land with a human. Nothing records that an email was sent;
;; the mulog `::invitation-email-sent` event is the send trail.

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

If you have questions, thoughts, ideas, feature requests, bug reports, or anything else, just hit reply -- replies come straight to my personal inbox, so I will definitely see your message and get back to you quickly.

Please give Parts a try, and let me know how you get on, and how I can help you help your clients.

Thank you for joining us!

Gosha

-- 
https://gosha.net")

(defn- valid-recipient?
  "One well-formed address, bounded, with no whitespace/control characters —
   validated here so the message core is safe regardless of which caller
   supplies the address."
  [email]
  (and (string? email)
       (<= (count email) 254)
       (some? (re-matches #"[^@\s\p{Cntrl}]+@[^@\s\p{Cntrl}]+\.[^@\s\p{Cntrl}]+" email))))

(defn invite-message
  "The postal message map for an invite — the pure, testable core of
   `send-invitation-email!`. Plain text; the invite's magic link fills the
   [LINK] placeholder in the body. Carries no :from — the sender identity
   belongs to the mail layer — and a Reply-To only when one is configured."
  [{:keys [email magic-link]}]
  (when-not (valid-recipient? email)
    (throw (ex-info "Invalid invite recipient address" {:type :validation})))
  (cond-> {:to      email
           :subject invite-subject
           :body    (cstr/replace invite-body-template "[LINK]" magic-link)}
    (conf/mail-reply-to) (assoc :reply-to (conf/mail-reply-to))))

(defn send-invitation-email!
  "Email `invite` — the map returned by `generate-invitation!` — its magic
   link, via the transactional mailer (`aps.parts.mail`). Nil-safe: a nil
   invite (email already redeemed) returns nil without sending, so this
   composes:

     (send-invitation-email! (generate-invitation! \"jane@example.com\"))

   Throws if mail is unconfigured or the send fails — the operator at the
   REPL must see a failed send. Returns the invite on success."
  [invite]
  (when invite
    (mail/send! (invite-message invite))
    (mulog/log ::invitation-email-sent :email (:email invite))
    (println (str "Sent invite to " (:email invite)))
    invite))

(defn invite-pending-waitlist!
  "Invite everyone `pending-waitlist!` reports: mint each email's
   invitation and send it its magic link. Keeps going when a send fails —
   a minted invitation removes its email from the pending list, so
   aborting midway would leave minted-but-unsent invitations invisible to
   a re-run. Prints a summary and returns {:sent [emails] :failed
   [emails]}; retry a failure with

     (send-invitation-email! (generate-invitation! email))

   which re-sends the same magic link (`generate-invitation!` is
   idempotent)."
  []
  (let [{:keys [sent failed] :as summary}
        (reduce (fn [acc {:keys [email]}]
                  (try
                    (send-invitation-email! (generate-invitation! email))
                    (update acc :sent conj email)
                    (catch Exception e
                      (println (str "FAILED " email " — " (ex-message e)))
                      (update acc :failed conj email))))
                {:sent [] :failed []}
                (pending-waitlist!))]
    (println (str "Invited " (count sent) " of " (+ (count sent) (count failed))
                  (when (seq failed)
                    (str "; failed: " (cstr/join ", " failed)))))
    summary))
