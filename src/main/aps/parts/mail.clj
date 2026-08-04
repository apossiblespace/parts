(ns aps.parts.mail
  "Transactional mail — the one place the application sends email from
   (TASK-014, ADR-0016). Wraps the configured SMTP relay (Scaleway TEM in
   production) behind `send!`, which takes a postal-style message map, so
   consumers never touch the provider, the transport, or postal directly.

   Deliberately not used by `aps.parts.alerts`: the alert sink stays closed
   and self-contained so alerting keeps working even if this layer is
   mid-refactor. Both speak to the same relay via `config/smtp-config`.

   Bounce and suppression handling is provider-side only for now (Scaleway
   blocklists plus the TEM console) — see ADR-0016's revisit trigger before
   adding webhooks or status polling here."
  (:require
   [aps.parts.config :as conf]
   [com.brunobonacci.mulog :as mulog]
   [postal.core :as postal]))

(defn- postal-connection
  "The postal connection map for the relay credentials. The transport flag
   follows the submission port: 587 is STARTTLS (`:tls`), 465 (and anything
   else) is implicit SSL from connect (`:ssl`). Connect/IO timeouts are set
   because JavaMail's defaults are infinite — a hung relay must fail the
   send, not pin the sending thread until restart."
  [{:keys [host port user pass]}]
  (assoc {:host              host
          :port              port
          :user              user
          :pass              pass
          :connectiontimeout 10000
          :timeout           30000}
         (if (= 587 port) :tls :ssl) true))

(defn send!
  "Send `message` — a postal-style map (`:to`, `:subject`, `:body`, and
   optionally `:from`, `:reply-to`, `:cc`…). `:from` defaults to the
   configured `:mail/from`. Returns the message as sent.

   Throws `:config-error` when the relay or sender is unconfigured and
   `:smtp-error` when the relay refuses the message — a send that fails must
   fail loudly; a silent drop strands a user waiting for mail that never
   comes."
  [message]
  (let [smtp   (or (conf/smtp-config)
                   (throw (ex-info "SMTP relay is not configured (PARTS__SMTP__*)"
                                   {:type :config-error})))
        from   (or (:from message)
                   (conf/mail-from)
                   (throw (ex-info "Mail sender is not configured (PARTS__MAIL__FROM)"
                                   {:type :config-error})))
        msg    (assoc message :from from)
        result (postal/send-message (postal-connection smtp) msg)]
    (when-not (= :SUCCESS (:error result))
      (throw (ex-info "Email send failed"
                      {:type :smtp-error :result result :to (:to message)})))
    (mulog/log ::email-sent :to (:to message) :subject (:subject message))
    msg))

;; Sender identity policy lives here, not in the message builders —
;; builders stay pure content (:to/:subject/:body).

(defn send-personal!
  "Send `message` in the operator's personal voice — invites, thank-yous,
   anything a human signs. From stays the default sender (`:mail/from`);
   Reply-To is the operator's personal address (`:mail/reply-to`) when
   configured, keeping the concierge reply promise."
  [message]
  (let [reply-to (conf/mail-reply-to)]
    (send! (cond-> message reply-to (assoc :reply-to reply-to)))))

(defn send-system!
  "Send `message` as an impersonal system notification — password resets
   and other machine-sent mail. From is `:mail/system-from` (e.g.
   Parts <help@ifs.tools>) when configured, else the default sender; no
   Reply-To — system mail doesn't invite replies."
  [message]
  (let [from (conf/mail-system-from)]
    (send! (cond-> message from (assoc :from from)))))
