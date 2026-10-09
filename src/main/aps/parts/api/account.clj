(ns aps.parts.api.account
  (:require
   [aps.parts.auth :as auth]
   [aps.parts.auth.session-store :as session-store]
   [aps.parts.billing :as billing]
   [aps.parts.common.constants :as c]
   [aps.parts.common.demo :as demo]
   [aps.parts.config :as config]
   [aps.parts.db :as db]
   [aps.parts.email-layout :as layout]
   [aps.parts.entity.map :as parts-map]
   [aps.parts.entity.part :as part]
   [aps.parts.entity.policy-acceptance :as policy-acceptance]
   [aps.parts.entity.relationship :as relationship]
   [aps.parts.entity.session :as session]
   [aps.parts.entity.user :as user]
   [aps.parts.mail :as mail]
   [aps.parts.password-notice :as password-notice]
   [aps.parts.stripe :as stripe]
   [com.brunobonacci.mulog :as mulog]
   [ring.util.response :as response]))

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

(defn get-account
  "Retrieve own account info, including the account's good-standing summary
   under `:standing` (see `billing/account-standing`) and the self-serve
   billing facts under `:billing` (see `billing-info`) for the Account page."
  [request]
  (let [user-id     (auth/current-user-id request)
        user-record (user/fetch user-id)]
    (-> (response/response (assoc user-record
                                  :standing (billing/account-standing user-record)
                                  :billing  (billing-info user-id)))
        (response/status 200))))

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
    (let [updated-user (db/with-transaction
                         (fn [tx]
                           (let [user (when (or (seq attrs) (not updates?))
                                        (user/update! user-id attrs tx))]
                             (when password? (session-store/revoke-for-user! tx user-id))
                             (if updates?
                               (user/set-product-updates! user-id (:product_updates body) tx)
                               user))))]
      (mulog/log ::update-account-success :user-id user-id)
      (when password? (password-notice/send! user-id (:email updated-user)))
      ;; The transaction deleted this session too. A new session id keeps the
      ;; User signed in, and a copy of the old cookie no longer works.
      (cond-> (-> (response/response updated-user)
                  (response/status 200))
        password? (-> (auth/establish-session request user-id)
                      (update :session vary-meta assoc :recreate true))))))

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
        account (if (true? (:product-updates-opt-out? params))
                  (user/set-product-updates! (:id account) false tx)
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
[video walkthrough of Parts](" (config/walkthrough-url) "). It shows the basic
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
  "Sends the welcome email to `account` on another thread, so the signup
   does not wait for the mail relay. A failed send is logged and does not
   reach the signup. Returns the future."
  [account]
  (future
    (try
      (mail/send-personal! (welcome-message account))
      (mulog/log ::welcome-email-sent :user-id (:id account))
      (catch Throwable e
        (mulog/log ::welcome-email-failed :user-id (:id account) :error (ex-message e))))))

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
        (let [response (-> (response/response (merge account {:map_id map-id}))
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

(defn delete-account
  "Delete own account"
  [request]
  (let [user-id (auth/current-user-id request)
        user    (user/fetch user-id)
        confirm (get-in request [:query-params "confirm"])]
    (if user
      (if (= (:email user) confirm)
        (do
          (user/delete! user-id)
          ;; Drop the caller's auth session — their account no longer exists.
          (auth/clear-session (response/status 204)))
        (throw (ex-info "Confirmation needed" {:type :validation})))
      (do
        (mulog/log ::update-account-not-found :user-id user-id)
        (throw (ex-info "User not found" {:type :not-found}))))))
