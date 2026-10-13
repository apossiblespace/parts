(ns aps.parts.api.account
  (:require
   [aps.parts.auth :as auth]
   [aps.parts.auth.session-store :as session-store]
   [aps.parts.billing :as billing]
   [aps.parts.common.constants :as c]
   [aps.parts.common.demo :as demo]
   [aps.parts.common.utils :refer [normalize-email]]
   [aps.parts.config :as config]
   [aps.parts.db :as db]
   [aps.parts.email-layout :as layout]
   [aps.parts.entity.map :as parts-map]
   [aps.parts.entity.part :as part]
   [aps.parts.entity.policy-acceptance :as policy-acceptance]
   [aps.parts.entity.relationship :as relationship]
   [aps.parts.entity.session :as session]
   [aps.parts.entity.user :as user]
   [aps.parts.export :as export]
   [aps.parts.mail :as mail]
   [aps.parts.password-notice :as password-notice]
   [aps.parts.stripe :as stripe]
   [com.brunobonacci.mulog :as mulog]
   [ring.util.response :as response])
  (:import
   (java.io ByteArrayInputStream)))

(defn- billing-info
  "Self-serve billing facts for the Account page, driving its
   subscribe-vs-manage choice. Only booleans leave the server; the raw
   customer id and status string have no business in the client.

   Everything is false when Stripe isn't configured — a subscriber on a
   host whose Stripe env was removed must not be offered a manage button
   that can only fail (and the concierge-only host skips the extra query
   entirely). A live status implies a linked customer — the two are only
   ever written together — so no separate portal flag is needed."
  [user-id]
  (let [enabled? (some? (config/stripe-config))
        row      (when enabled? (billing/billing-facts user-id))]
    {:self_serve_enabled      enabled?
     :subscription_active     (contains? stripe/live-subscription-statuses
                                         (:stripe_subscription_status row))
     ;; Distinct from merely not-active: a cancelled subscriber keeps
     ;; their paid window (decision 7), and the card must say "cancelled,
     ;; works until X, no further charges" rather than "active" over
     ;; subscribe buttons.
     :subscription_cancelled  (= "canceled" (:stripe_subscription_status row))
     ;; Pending cancellation: still live and reversible in the portal,
     ;; but the page must say the window ends, not promise a renewal.
     :subscription_cancelling (= "canceling" (:stripe_subscription_status row))
     ;; Which plan, so the page can name the renewal amount. A plan word,
     ;; not a price — amounts stay in the shared constants.
     :subscription_plan       (:stripe_plan row)}))

(defn- account-body
  "The account as the browser sees it: the columns `user/fetch` selects,
   the good-standing summary under `:standing` (see
   `billing/account-standing`) and the self-serve billing facts under
   `:billing` (see `billing-info`). Every account response uses it, so no
   other column (Stripe ids, deletion timestamps, …) leaves the server."
  [user-id]
  (let [user-record (user/fetch user-id)]
    (assoc user-record
           :standing (billing/account-standing user-record)
           :billing  (billing-info user-id))))

(defn get-account
  "Retrieve own account info (see `account-body`) for the Account page."
  [request]
  (-> (response/response (account-body (auth/current-user-id request)))
      (response/status 200)))

(defn update-account
  "Update own account info. Changing the password additionally requires the
   caller's current password, so a captured session alone cannot take over
   the account. A password change also logs out every other session and
   emails the password-changed notice. `:current_password` is a transient
   input. It is not in `user/allowed-update-fields`, so it never reaches
   the database.

   The email cannot change here: Parts has no way to confirm a new address
   yet, so an email change is a concierge request."
  [request]
  (let [user-id   (auth/current-user-id request)
        body      (:body-params request)
        updates?  (contains? body :product_updates)
        attrs     (dissoc body :current_password :product_updates)
        password? (some? (:password attrs))]
    (when (contains? body :email)
      (throw (ex-info (str "To change your email address, please email " c/support-email ".")
                      {:type :validation})))
    (when (and (contains? body :password)
               (not (auth/current-password-valid? user-id (:current_password body))))
      (throw (ex-info "Current password is incorrect" {:type :validation})))
    (when (and updates? (not (boolean? (:product_updates body))))
      (throw (ex-info "product_updates must be true or false" {:type :validation})))
    ;; `:product_updates` is not a column. It sets or clears the opt-out
    ;; timestamp, which `user/update!` cannot set to NULL.
    (db/with-transaction
      (fn [tx]
        (when (or (seq attrs) (not updates?))
          (user/update! user-id attrs tx))
        (when password? (session-store/revoke-for-user! tx user-id))
        (when updates?
          (user/set-product-updates! user-id (:product_updates body) tx))))
    (let [updated-user (account-body user-id)]
      (mulog/log ::update-account-success :user-id user-id)
      (when password? (password-notice/send! user-id (:email updated-user)))
      ;; The transaction deleted this session too. `establish-session` keeps
      ;; the User signed in under a new session id.
      (cond-> (-> (response/response updated-user)
                  (response/status 200))
        password? (auth/establish-session request user-id)))))

(defn- populate-initial-map!
  "Populates a new map with demo parts and relationships.
   Runs all inserts on the provided tx so they share atomicity with the caller."
  [map-id actor-id tx]
  (let [created-parts (mapv #(part/create! % actor-id tx)
                            (demo/demo-part-attrs map-id))]
    (doseq [rel-data (demo/demo-relationship-attrs created-parts)]
      (relationship/create! rel-data actor-id tx))))

(defn- validate-acceptance!
  "Onboarding must capture explicit, opt-in acceptance before an account can
   exist: both the medical-data acknowledgement and agreement to the legal
   documents. The server enforces this — the form's `required` checkboxes are
   only UX — so account-exists implies accepted (ADR-0009)."
  [{:keys [accepted-legal? accepted-medical?]}]
  (when-not (and accepted-legal? accepted-medical?)
    (throw (ex-info "Please accept the medical-data notice and the legal documents to continue."
                    {:type :validation}))))

(defn- provision-account!
  "Creates a user, their default map, seeds it with demo content, and records
   the user's onboarding policy acceptances. All writes share `tx` so they
   commit or roll back as one unit. Returns {:account ... :map-id ...}.

   `params` carries the two acceptance booleans (`:accepted-legal?`,
   `:accepted-medical?`) alongside the user fields; they are validated, then
   stripped before the user is created. A true `:product-updates-opt-out?`
   creates the account opted out of Product updates."
  [params tx]
  (validate-acceptance! params)
  (let [account (user/create! (dissoc params :accepted-legal? :accepted-medical?
                                      :product-updates-opt-out?)
                              tx)
        ;; The sign-up box is the User's choice, so a new account never
        ;; sees the in-app question. Without the box value, nothing is
        ;; chosen and the app asks later.
        account (if (contains? params :product-updates-opt-out?)
                  (user/set-product-updates! (:id account)
                                             (not (true? (:product-updates-opt-out? params)))
                                             tx)
                  account)
        title   "Example Map"
        the-map (parts-map/create! {:title title :owner_id (:id account)} (:id account) tx)]
    ;; Session 1 must exist before any content so the seeded demo Parts land
    ;; inside its range — ADR-0014's "derivation is total" invariant.
    (session/create! (:id the-map) (:id account) tx)
    (populate-initial-map! (:id the-map) (:id account) tx)
    (policy-acceptance/record-onboarding! (:id account) tx)
    {:account account :map-id (:id the-map)}))

(defn- welcome-message
  "The postal message map of the welcome email to a new account. Pure
   content: `mail/send-personal!` adds the sender identity."
  [{:keys [email]}]
  {:to      email
   :subject "Welcome to Parts"
   :body    (layout/alternative
             (layout/content
              (str "Hello,

My name is Gosha, one of the creators of Parts, the IFS parts mapping tool.

On behalf of the team, welcome, and thank you for signing up!

Before you dive in, you may want to watch our
[video walkthrough of Parts](https://www.youtube.com/watch?v=" (config/walkthrough-video-id) "). It shows the basic
features and how to start building maps for your clients.

Your account already has an Example Map with a first Session, so you can try
things out straight away. When you’re ready, [open Parts](" (config/base-url) "/app)
and create a map for each of your clients.

---

**Please note:** Parts is currently in beta, and is free to use during this time.
You can purchase an optional subscription to help support development (see
the Account page), but it is not required to use all the features at the
moment.

I will email you two weeks before we start requiring payment to continue using
Parts.

---

If you have questions, thoughts, ideas, feature requests, bug reports, or
anything else, just hit reply. Replies come straight to my personal inbox, so
I will see your message and get back to you quickly.

Thank you for joining us!

Gosha")
              (layout/transactional-footer email)))})

(defn- send-welcome!
  "Sends the welcome email to `account` without making the signup wait
   (see `mail/send-async!`). Returns the future."
  [account]
  (mail/send-async! :welcome mail/send-personal! (welcome-message account) (:id account)))

(defn register-account
  "Register a new user (role hardcoded to 'therapist'), provision their starter
   map atomically, and establish the auth session for auto-login."
  [request]
  ;; Allowlist the request body to the fields a registrant may supply, then
  ;; force the server-controlled ones. Without this, body keys like
  ;; :is_founding_circle / :paid_through_date would flow through to the user
  ;; insert (mass-assignment). The two acceptance booleans are kept for
  ;; `validate-acceptance!`, which strips them before the user is created.
  (let [params (-> (:body-params request)
                   (select-keys [:email :display_name :password :password_confirmation
                                 :accepted-legal? :accepted-medical?
                                 :product-updates-opt-out?])
                   (assoc :role "therapist"))]
    (try
      (let [{:keys [account map-id]} (db/with-transaction
                                       #(provision-account! params %))]
        (mulog/log ::signup
                   :user-id (:id account)
                   :email (:email account)
                   :display-name (:display_name account)
                   :map-id map-id)
        (let [response (-> (response/response (account-body (:id account)))
                           (response/status 201)
                           (auth/establish-session request (:id account)))]
          (send-welcome! account)
          response))
      (catch Exception e
        ;; NOTE: log only safe fields. `params` contains :password and is NOT
        ;; redacted by mulog (the redaction in observe.cljc only covers the o/*
        ;; logging façade). Never pass raw params to mulog/log.
        (mulog/log ::register
                   :email (:email params)
                   :status :failure
                   :error-type (:type (ex-data e))
                   :error-message (.getMessage e))
        (throw e)))))

(defn export-zip
  "GET /api/account/export.zip — all of the caller's data as one ZIP:
   the account details and every Map's export (see
   `export/account-zip`)."
  [request]
  (let [user-id (auth/current-user-id request)
        zip     (export/account-zip db/datasource user-id)]
    (mulog/log ::export-account :user-id user-id)
    (-> (response/response (ByteArrayInputStream. zip))
        (response/status 200)
        (response/header "Content-Length" (str (alength ^bytes zip)))
        (response/header "Content-Type" "application/zip")
        ;; It holds every client's clinical notes: never cache it.
        (response/header "Cache-Control" "no-store")
        (response/header "Content-Disposition"
                         (str "attachment; filename=\"parts-data-" (java.time.LocalDate/now) ".zip\"")))))

(defn- deletion-message
  "The postal message map of the notice that the account of `email` was
   deleted. It mentions the subscription only when one was cancelled
   (`cancelled-subscription?`): naming a subscription the User never had
   would only worry them. Pure content: `mail/send-system!` adds the
   sender identity."
  [email cancelled-subscription?]
  {:to      email
   :subject "Your Parts account has been deleted"
   :body    (layout/alternative
             (layout/content
              (str "Hello,

Your Parts account and all its Maps have been deleted, as you asked.
"
                   (when cancelled-subscription?
                     "Your subscription has been cancelled, and you will not be charged again.
")
                   "
If you didn’t delete your account, please email us at
[" c/support-email "](mailto:" c/support-email ") straight away.

The Parts team")
              (layout/transactional-footer email)))})

(defn- send-deletion-notice!
  "Sends the deletion notice to `email` without making the request wait
   (see `mail/send-async!`). The account is already gone, so the log
   names no user. Returns the future."
  [email cancelled-subscription?]
  (mail/send-async! :account-deleted mail/send-system!
                    (deletion-message email cancelled-subscription?) nil))

(defn delete-account
  "POST /api/account/delete — erase the caller's account now (CONTEXT.md,
   \"Account deletion\"). The body must carry the account's email address
   and the current password: a captured session alone cannot delete an
   account. Both travel in the body, so they never reach a URL or a log.
   Then the Stripe customer is released and everything is purged
   (`user/delete!`), the session is dropped, and a notice goes to the
   address that was deleted, so a deletion by someone else is seen."
  [request]
  (let [user-id (auth/current-user-id request)
        body    (:body-params request)
        email   (:email (user/fetch user-id))]
    (when-not (= (normalize-email email) (some-> (:email body) str normalize-email))
      (throw (ex-info "Type the email address of your account to confirm." {:type :validation})))
    (when-not (auth/current-password-valid? user-id (:current_password body))
      (throw (ex-info "That password is incorrect. Your account was not deleted." {:type :validation})))
    (let [;; Read before the erasure releases the Stripe customer.
          subscribed? (:subscription_active (billing-info user-id))]
      (user/delete! user-id)
      (mulog/log ::delete-account :user-id user-id)
      (send-deletion-notice! email subscribed?))
    ;; Drop the caller's auth session — their account no longer exists.
    (auth/clear-session (response/status 204))))
