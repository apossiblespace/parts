(ns aps.parts.handlers.password-reset
  "Server-rendered self-serve password reset — GET/POST /reset-password
   (request a link) and GET/POST /reset/:token (set a new password).

   Top-level on purpose, like /invite: recovering access must not depend
   on the SPA bundle loading first. Two privacy rules shape the handlers:
   the request endpoint responds identically whether or not the email has
   an account, and the token error page is one message for every failure
   mode (TASK-109)."
  (:require
   [aps.parts.auth :as auth]
   [aps.parts.auth.session-store :as session-store]
   [aps.parts.config :as conf]
   [aps.parts.db :as db]
   [aps.parts.entity.user :as user]
   [aps.parts.mail :as mail]
   [aps.parts.password-resets :as resets]
   [aps.parts.ratelimit :as ratelimit]
   [aps.parts.views.layouts :as layouts]
   [aps.parts.views.partials :as partials]
   [com.brunobonacci.mulog :as mulog]
   [ring.util.response :as response]))

(defn- unavailable-response
  "The calm 'reset link unavailable' page, 404 for every failure mode."
  []
  (-> (response/response
       (layouts/content-page "Reset link unavailable"
                             (partials/password-reset-unavailable-content)))
      (response/status 404)))

;; -- requesting a link ------------------------------------------------------

(def ^:private reset-subject
  "Reset your Parts password")

(defn- reset-message
  "The postal message map for a reset link — pure content; the sender
   identity is stamped by `mail/send-system!` (a machine notification,
   unlike the operator-signed invite)."
  [{:keys [email url]}]
  {:to      email
   :subject reset-subject
   :body    (str "Hello,

Someone asked to reset the password for the Parts account registered to this
email address. If that was you, use this link to choose a new password:

" url "

The link can be used once, and expires an hour after it was first requested —
if it has already expired, just request a new one. If you didn’t request
this, you can safely ignore this email — your password is unchanged.

The Parts team
" (conf/base-url))})

(defn request-form
  "GET /reset-password — the request-a-link form."
  [_request]
  (response/response
   (layouts/content-page "Reset your password" (partials/password-reset-request-content))))

(defn run-async!
  "Run thunk `f` on another thread, swallowing (but logging) anything it
   throws — Throwable, not Exception: an Error inside an undereferenced
   future would otherwise vanish without a trace. The request endpoint
   must answer in constant time whether or not an account exists — the
   account lookup, token mint, and blocking SMTP round trip all happen
   off the request thread so response timing (and a slow relay pinning
   server threads) cannot leak the difference. A named var so tests can
   rebind it to run synchronously."
  [f]
  (future
    (try
      (f)
      (catch Throwable t
        (mulog/log ::reset-email-failed :error (ex-message t))))))

(def ^:private email-budget
  "Reset-email cap per account. Keyed on the account's stored email — a
   bounded set — never on the raw submitted value."
  {:capacity 3 :refill-per-ms (/ 3.0 (* 60 60 1000))})

(defn request-submit
  "POST /reset-password — mint and email a reset link when the address has
   an account; always render the same 'check your email' page, immediately.
   Nothing about the response (status, body, timing, or an error) may
   reveal whether the account exists or whether the send worked — failures
   are logged for the operator instead (`::reset-email-failed`). Over-cap
   requests silently skip the send for the same reason; the link already
   delivered stays valid (`create-reset!` is idempotent), so a flooder
   cannot lock the account holder out of recovery."
  [request]
  (let [email (get-in request [:form-params "email"])]
    (run-async!
     (fn []
       (when-let [reset (resets/create-reset! email)]
         (if (ratelimit/allow? :password-reset-email (:email reset) email-budget)
           (do (mail/send-system! (reset-message reset))
               (mulog/log ::reset-email-sent :email (:email reset)))
           (mulog/log ::reset-email-capped :email (:email reset))))))
    (response/response
     (layouts/content-page "Check your email" (partials/password-reset-sent-content)))))

;; -- redeeming a link -------------------------------------------------------

(defn show
  "GET /reset/:token — the new-password form for a valid token, or the
   calm error page (404) for an unknown / used / expired one."
  [request]
  (let [token (get-in request [:path-params :token])]
    (if (resets/find-active token)
      (response/response
       (layouts/content-page "Choose a new password"
                             (partials/password-reset-form-content {:token token})))
      (unavailable-response))))

(defn redeem
  "POST /reset/:token — claim the token, set the new password, and revoke
   every existing auth session in one transaction, then sign the user in.

   The transaction is the atomicity boundary: the claim, the password
   update, and the log-out-everywhere revocation commit or roll back
   together — a validation failure leaves the token usable, and the
   password can never change without old sessions dying with it. The
   requester's own pre-auth session row has a NULL user_id, so the in-tx
   revocation cannot touch it; a fresh signed-in session is established
   on the redirect. Missing form fields become empty strings so the user
   sees the password-length message, not a generic validation error."
  [request]
  (let [token (get-in request [:path-params :token])
        form  (:form-params request)]
    (try
      (let [user-id
            (db/with-transaction
              (fn [tx]
                (let [claimed (resets/claim! token tx)]
                  (when-not claimed
                    (throw (ex-info "Reset token unknown, used, or expired"
                                    {:type :reset-unavailable})))
                  (user/update! (:user_id claimed)
                                {:password              (get form "password" "")
                                 :password_confirmation (get form "password_confirmation" "")}
                                tx)
                  (session-store/revoke-for-user! tx (:user_id claimed))
                  (:user_id claimed))))]
        (mulog/log ::password-reset-completed :user-id (str user-id))
        ;; 303 See Other — POST-redirect-GET, signed in via a fresh auth
        ;; session (ADR-0007), same as invite redemption.
        (-> (response/redirect "/app")
            (response/status 303)
            (auth/establish-session request user-id)))

      (catch clojure.lang.ExceptionInfo e
        (case (:type (ex-data e))
          :reset-unavailable
          (unavailable-response)

          :validation
          (-> (response/response
               (layouts/content-page "Choose a new password"
                                     (partials/password-reset-form-content
                                      {:token token :error (ex-message e)})))
              (response/status 400))

          (throw e))))))
