(ns aps.parts.password-notice
  "The email that tells a User their password was changed, so that a
   takeover is visible to the owner of the account. The password reset and
   `PATCH /api/account` both send it. It is a transactional system
   notification, so it ignores the Product-update opt-out."
  (:require
   [aps.parts.common.constants :as c]
   [aps.parts.email-layout :as layout]
   [aps.parts.mail :as mail]
   [com.brunobonacci.mulog :as mulog])
  (:import
   (java.time Instant ZoneOffset)
   (java.time.format DateTimeFormatter)
   (java.util Locale)))

;; Parts does not know the User's time zone, so the time is in UTC.
(def ^:private time-format
  (-> (DateTimeFormatter/ofPattern "d MMMM yyyy 'at' HH:mm 'UTC'" Locale/UK)
      (.withZone ZoneOffset/UTC)))

(defn message
  "Returns the postal message map of the notice to `email` for a password
   change at the instant `at`. Pure content: `mail/send-system!` adds the
   sender identity."
  [email ^Instant at]
  {:to      email
   :subject "Your Parts password was changed"
   :body    (layout/alternative
             (layout/content
              (str "Hello,

The password for your Parts account was changed on " (.format time-format at) ".

If you made this change, you don’t need to do anything.

If you didn’t change your password, please email us at
[" c/support-email "](mailto:" c/support-email ") straight away, so we can
secure your account.

The Parts team")
              (layout/transactional-footer email)))})

(defn send!
  "Sends the notice to `email`, the address of the User `user-id`, for a
   password change now. It sends on another thread, so the request does
   not wait for the mail relay. A failed send is logged and does not reach
   the caller. Returns the future."
  [user-id email]
  (let [at (Instant/now)]
    (future
      (try
        (mail/send-system! (message email at))
        (mulog/log ::sent :user-id user-id)
        (catch Throwable e
          (mulog/log ::failed :user-id user-id :error (ex-message e)))))))
