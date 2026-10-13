(ns aps.parts.handlers.email-change
  "Server-rendered pages for the link that confirms an email change:
   GET and POST `/email-change/:token` (TASK-154.02).

   The link works without signing in, because mail is often read on a
   phone. GET changes nothing, because mail scanners open every link in
   an email: it shows a Confirm button. POST makes the change. The error
   page is one message for every unusable link."
  (:require
   [aps.parts.billing :as billing]
   [aps.parts.common.constants :as c]
   [aps.parts.email-changes :as email-changes]
   [aps.parts.email-layout :as layout]
   [aps.parts.mail :as mail]
   [aps.parts.views.layouts :as layouts]
   [aps.parts.views.partials :as partials]
   [com.brunobonacci.mulog :as mulog]
   [ring.util.response :as response]))

(defn- unavailable-response []
  (-> (response/response
       (layouts/content-page "Link not available" (partials/email-change-unavailable-content)))
      (response/status 404)))

(defn- old-address-message
  "The notice to the OLD address that the account moved to `new-email`."
  [old-email new-email]
  {:to      old-email
   :subject "Your Parts email address was changed"
   :body    (layout/alternative
             (layout/content
              (str "Hello,

The email address of your Parts account was changed to " new-email ".
From now on, sign in with the new address.

If you didn’t change it, please reply to this email or write to
[" c/support-email "](mailto:" c/support-email ") straight away.

The Parts team")
              (layout/transactional-footer old-email)))})

(defn show
  "GET /email-change/:token — the Confirm page for a usable link, or the
   error page (404)."
  [request]
  (let [token (get-in request [:path-params :token])]
    (if-let [change (email-changes/find-active token)]
      (response/response
       (layouts/content-page "Confirm your new email address"
                             (partials/email-change-confirm-content
                              {:token token :new-email (:new_email change)})))
      (unavailable-response))))

(defn confirm
  "POST /email-change/:token — move the account to the new address (see
   `email-changes/confirm!`), then tell the old address and update the
   Stripe customer."
  [request]
  (let [token (get-in request [:path-params :token])]
    (try
      (let [{:keys [user-id old-email new-email]} (email-changes/confirm! token)]
        (mulog/log ::email-changed :user-id user-id)
        (mail/send-async! :email-changed mail/send-system!
                          (old-address-message old-email new-email) user-id)
        ;; Best effort, and the change is done: do not make the page wait.
        (future (billing/sync-stripe-email! user-id new-email))
        (response/response
         (layouts/content-page "Email address changed"
                               (partials/email-change-done-content {:new-email new-email}))))
      (catch clojure.lang.ExceptionInfo e
        (case (:type (ex-data e))
          :link-unavailable (unavailable-response)
          :email-taken      (-> (response/response
                                 (layouts/content-page "Email address in use"
                                                       (partials/email-change-taken-content)))
                                (response/status 409))
          (throw e))))))
