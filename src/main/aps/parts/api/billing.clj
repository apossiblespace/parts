(ns aps.parts.api.billing
  "Self-serve subscription endpoints (TASK-046).

   Three handlers: two authenticated session-creators that redirect the
   browser to Stripe's hosted pages, and the webhook that turns Stripe
   events into billing-column moves — all through `aps.parts.billing`,
   which owns the storage; this namespace holds only the policy (which
   event means which move). Standing stays *recorded*, not *enforced*: a
   lapse never locks anyone out here.

   Everything stays off until `config/stripe-config` is fully set — a
   host with no Stripe env simply has no billing.

   The webhook route is mounted at the top level, outside the /api transit
   middleware: Stripe signs the exact raw bytes it sends, so the body must
   reach this namespace unparsed. `webhook-through-assembled-app-test`
   pins that invariant against middleware drift."
  (:require
   [aps.parts.auth :as auth]
   [aps.parts.billing :as billing]
   [aps.parts.common.constants :as c]
   [aps.parts.config :as config]
   [aps.parts.mail :as mail]
   [aps.parts.stripe :as stripe]
   [com.brunobonacci.mulog :as mulog]
   [ring.util.response :as response])
  (:import
   (java.time Instant LocalDate ZoneOffset)))

;; --- Shared helpers --------------------------------------------------------

(defn- stripe-config!
  "The Stripe settings, or a 404 — an unconfigured host has no self-serve
   billing to speak of."
  []
  (or (config/stripe-config)
      (throw (ex-info "Self-serve billing is not enabled" {:type :not-found}))))

(def ^:private plans
  "The self-serve plan keys, from the shared definition the Account page
   renders its buttons from — one source for both runtimes."
  (into #{} (map :plan) c/subscription-plans))

(defn- effective-status
  "The status we record: Stripe's own, except a charging subscription
   (`active`, or `trialing` — how a resubscriber's remaining window is
   carried) with a scheduled cancellation becomes our synthetic
   \"canceling\" — still live (the guard must refuse a second checkout,
   the portal can reverse it), but the page must say the window ends
   rather than promise a renewal charge. Stripe schedules cancellation
   two ways depending on API version and surface: the
   `cancel_at_period_end` boolean, or a `cancel_at` timestamp (what the
   portal sets on current versions) — either one means no renewal is
   coming."
  [{:keys [status cancel_at_period_end cancel_at]}]
  (if (and (contains? #{"active" "trialing"} status)
           (or cancel_at_period_end cancel_at))
    "canceling"
    status))

(def ^:private min-trial-lead-seconds
  "Stripe refuses a Checkout `trial_end` less than 48 hours out. The
   margin covers the time between our check and Stripe receiving the
   request — a boundary rejection would 502 the checkout and page the
   operator, strictly worse than the fallback of charging immediately."
  (+ (* 48 3600) 300))

(defn- resume-trial-end
  "When a previously-subscribed account still has paid-for time, the
   epoch second the new subscription's first charge should land: the
   start (UTC) of the day *after* the paid-through date — the date is
   inclusive (`billing/account-standing` counts its final day as
   covered), so charging at midnight on the date itself would take back
   the last paid day. Without this, Checkout charges immediately and
   the overlap swallows the remaining days.

   Nil — meaning charge now — in three cases: the account has no Stripe
   link (a comped window on a never-subscribed account is goodwill, not
   credit — subscribing from one should start funding development, not
   defer payment for months); there is no future window; or the window
   ends inside `min-trial-lead-seconds`, where up to two days of
   overlap is the best Stripe allows. A *linked* account's window
   counts in full, even the parts extended by hand: an operator
   extension through X deliberately means the next charge lands after
   X. Two-arity for tests, like `billing/account-standing`."
  ([row] (resume-trial-end row (quot (System/currentTimeMillis) 1000)))
  ([{:keys [stripe_customer_id ^LocalDate paid_through_date]} now]
   (when (and stripe_customer_id paid_through_date)
     (let [epoch (.toEpochSecond (.atStartOfDay (.plusDays paid_through_date 1)
                                                ZoneOffset/UTC))]
       (when (> epoch (+ now min-trial-lead-seconds))
         epoch)))))

(defn- latest-epoch->date
  "The latest of a collection of epoch-second values, as a UTC date — the
   one zone every billing date is derived in. Latest, not first: a
   plan-switch invoice leads with a proration line whose period ends at
   the switch date, and post-Basil subscriptions carry a period end per
   item. nil when nothing numeric is present."
  [epochs]
  (when-let [latest (some->> epochs (filter number?) seq (apply max))]
    (LocalDate/ofInstant (Instant/ofEpochSecond (long latest)) ZoneOffset/UTC)))

;; --- Checkout and Portal sessions ------------------------------------------

(defn create-checkout-session
  "Create a Stripe Checkout Session for the caller and return its URL for
   the SPA to redirect to. Body: {:plan \"monthly\" | \"yearly\"}.

   Refused while the account already has a live subscription — a second
   one would bill twice and orphan the first's renewals (its invoices
   would race for the single stripe_customer_id link)."
  [request]
  (let [stripe-config (stripe-config!)
        plan          (keyword (get-in request [:body-params :plan]))]
    (when-not (contains? plans plan)
      (throw (ex-info "Unknown subscription plan" {:type :validation})))
    (let [user-id (auth/current-user-id request)
          row     (billing/billing-facts user-id)]
      (when (contains? stripe/live-subscription-statuses
                       (:stripe_subscription_status row))
        (throw (ex-info "You already have an active subscription"
                        {:type :validation})))
      (let [session (stripe/create-checkout-session!
                     stripe-config
                     (stripe/checkout-session-params
                      {:user-id   user-id
                       :email     (:email row)
                       :customer  (:stripe_customer_id row)
                       :plan      plan
                       :price-id  (get-in stripe-config [:prices plan])
                       :base-url  (config/base-url)
                       :trial-end (resume-trial-end row)}))]
        (mulog/log ::checkout-session-created :user-id user-id :plan plan)
        (response/response {:url (:url session)})))))

(defn create-portal-session
  "Create a Customer Portal session — where a subscriber updates their
   payment method or cancels — and return its URL."
  [request]
  (let [stripe-config (stripe-config!)
        user-id       (auth/current-user-id request)
        customer      (:stripe_customer_id (billing/billing-facts user-id))]
    (when-not customer
      (throw (ex-info "No subscription to manage" {:type :validation})))
    (let [session (stripe/create-portal-session!
                   stripe-config
                   (stripe/portal-session-params {:customer customer
                                                  :base-url (config/base-url)}))]
      (mulog/log ::portal-session-created :user-id user-id)
      (response/response {:url (:url session)}))))

;; --- Webhook ----------------------------------------------------------------
;;
;; Checkout completion covers the purchased period by fetching the
;; subscription's *actual* period end from Stripe — not by adding a
;; plan-length to today — so a first `invoice.paid` that raced ahead of
;; the linkage (event order is not guaranteed) is self-corrected, and a
;; misconfigured price interval can't over- or under-grant.
;;
;; Every application is idempotent — the link converges, extension is
;; monotonic (GREATEST in SQL), status is a plain overwrite — so Stripe's
;; at-least-once delivery needs no dedup ledger.

(defn thank-you-message
  "The postal message for a first-time subscriber — Parts' voice, not the
   receipt (Stripe's customer emails carry the VAT invoice). Pure and
   public for its test; carries no :from (the sender identity belongs to
   the mail layer) and a Reply-To only when one is configured, mirroring
   `ops/invite-message`."
  [email plan]
  (cond-> {:to      email
           :subject "Thank you for subscribing to Parts"
           :body    (str "Hello,\n"
                         "\n"
                         "Thank you for subscribing to Parts and for supporting the development! It means\n"
                         "a great deal to us.\n"
                         "\n"
                         "Your " (name plan) " subscription is active, and it simply carries\n"
                         "on when Parts launches. You can update your payment details,\n"
                         "switch plans, or cancel at any time from your account page:\n"
                         "\n"
                         (config/base-url) "/app/account\n"
                         "\n"
                         "Stripe emails your receipt for each payment separately.\n"
                         "\n"
                         "If you have any questions at all, or any feedback about Parts,\n"
                         "just reply to this email.\n"
                         "\n"
                         "Warmly,\n"
                         "Gosha and Tingyi, creators of Parts")}
    (config/mail-reply-to) (assoc :reply-to (config/mail-reply-to))))

(defn- send-thank-you!
  "Send the first-subscription thank-you, insulated from the webhook's
   failure path: mail trouble is logged, never thrown — it must not 500 a
   payment event Stripe would then redeliver."
  [email plan]
  (try
    (mail/send! (thank-you-message email plan))
    (catch Exception e
      (mulog/log ::thank-you-email-failed :to email :error (.getMessage e)))))

(defn- release-orphaned-customer!
  "A recognisably-ours session completed for an account that no longer
   exists — deleted between opening Checkout and paying. Delete the
   just-created customer at once: its subscription must not outlive the
   account it was for."
  [stripe-config customer client-reference]
  (stripe/delete-customer-if-present! stripe-config customer)
  (mulog/log ::orphaned-checkout-released
             :customer customer
             :client-reference client-reference))

(defn- handle-checkout-completed!
  "Link the paying account to its Stripe customer, record the subscription
   status, and extend paid-through to the subscription's real period end —
   one atomic row move. Fetching the subscription from Stripe can fail;
   the throw 500s the webhook and Stripe's redelivery retries the whole
   application.

   Sessions without a recognised plan in metadata (not created by
   `create-checkout-session`) are ignored. A session whose account is
   already linked to a *different* customer still gets its paid time (the
   charge was real) but never overwrites the link — renewals must keep
   flowing to the first subscription; the conflict is logged for the
   operator to refund and cancel the duplicate. A session whose account
   no longer exists goes to `release-orphaned-customer!`."
  [stripe-config {:keys [customer subscription client_reference_id metadata]}]
  (let [plan    (some-> (:plan metadata) keyword plans)
        user-id (when (string? client_reference_id) (parse-uuid client_reference_id))
        ours?   (and plan user-id)
        row     (when ours? (billing/billing-facts user-id))]
    (if row
      (let [sub        (stripe/get-subscription! stripe-config subscription)
            period-end (latest-epoch->date
                        (keep :current_period_end (get-in sub [:items :data])))]
        (if (and (:stripe_customer_id row)
                 (not= customer (:stripe_customer_id row)))
          (do (when period-end
                (billing/extend-paid-through! user-id period-end))
              (mulog/log ::customer-link-conflict
                         :user-id user-id
                         :incoming-customer customer))
          (do (billing/record-checkout! user-id customer (effective-status sub) (name plan) period-end)
              ;; Only the account's first linkage is thanked: a webhook
              ;; redelivery, or a resubscribe under the existing link,
              ;; must not re-thank.
              (when-not (:stripe_customer_id row)
                (send-thank-you! (:email row) plan))))
        (mulog/log ::checkout-completed
                   :user-id user-id :plan plan
                   :subscription-status (:status sub)
                   :paid-through (str period-end)))
      (if (and ours? customer)
        (release-orphaned-customer! stripe-config customer client_reference_id)
        (mulog/log ::checkout-session-ignored
                   :client-reference client_reference_id
                   :customer customer
                   :recognized-plan? (boolean plan))))))

(defn- handle-invoice-paid!
  "Move a linked account's paid-through date to the end of the invoice's
   paid period, never backwards. An invoice matching no linked account is
   an anomaly — there is no other billing lane — so it's flagged for the
   operator (`::invoice-unmatched` is alertable), but still acknowledged:
   a non-2xx would put an unmatchable delivery on Stripe's retry
   treadmill forever."
  [{:keys [customer lines]}]
  (let [period-end (latest-epoch->date (map #(get-in % [:period :end]) (:data lines)))]
    (if-let [updated (when (and customer period-end)
                       (billing/extend-paid-through-for-customer! customer period-end))]
      (mulog/log ::invoice-applied
                 :customer customer
                 :paid-through (str (:paid_through_date updated)))
      (mulog/log ::invoice-unmatched :customer customer))))

(defn- price->plan
  "Which of our plans a Stripe price id belongs to — \"monthly\" /
   \"yearly\" — or nil for a price we don't recognise. How plan switches
   made in the Customer Portal reach `stripe_plan`."
  [stripe-config price-id]
  (some (fn [[plan id]] (when (= id price-id) (name plan)))
        (:prices stripe-config)))

(defn- handle-subscription-change!
  "Keep `stripe_subscription_status` (and the plan, on switches) current
   for a linked account. The deleted event carries status \"canceled\",
   so cancellation flows through the same overwrite — and the Account
   page re-offers subscribing. Never touches paid_through_date:
   cancelling doesn't retract time already paid for."
  [stripe-config {:keys [customer items] :as subscription}]
  (let [status (effective-status subscription)
        plan   (price->plan stripe-config (get-in items [:data 0 :price :id]))]
    (if (and customer (billing/record-subscription-status! customer status plan))
      (mulog/log ::subscription-status-updated
                 :customer customer :status status :plan plan)
      (mulog/log ::subscription-event-ignored :customer customer))))

(defn- handle-event! [stripe-config {:keys [type data]}]
  (case type
    "checkout.session.completed"
    (handle-checkout-completed! stripe-config (:object data))

    "invoice.paid"
    (handle-invoice-paid! (:object data))

    ("customer.subscription.created"
     "customer.subscription.updated"
     "customer.subscription.deleted")
    (handle-subscription-change! stripe-config (:object data))

    (mulog/log ::event-unhandled :event-type type)))

(defn- raw-body
  "The request body exactly as received — the bytes Stripe signed."
  [request]
  (if-let [body (:body request)]
    (slurp body)
    ""))

(defn- plain [status body]
  {:status status :headers {"Content-Type" "text/plain"} :body body})

(defn webhook
  "Stripe webhook endpoint. Verifies the signature against the raw body,
   applies the event, and always acknowledges what it chose to ignore —
   Stripe retries anything non-2xx for days. A processing error returns
   500 on purpose: that redelivery is the recovery mechanism."
  [request]
  (if-let [stripe-config (config/stripe-config)]
    (let [payload (raw-body request)]
      (if (stripe/valid-signature? payload
                                   (get-in request [:headers "stripe-signature"])
                                   (:webhook-secret stripe-config))
        (let [event (stripe/parse-event payload)]
          (try
            (handle-event! stripe-config event)
            (plain 200 "ok")
            (catch Exception e
              (let [data (ex-data e)]
                (mulog/log ::webhook-error
                           :event-id (:id event)
                           :event-type (:type event)
                           :error (.getMessage e)
                           :error-type (:type data)
                           :error-status (:status data)
                           :error-path (:path data)))
              (plain 500 "processing error"))))
        (do (mulog/log ::webhook-rejected)
            (plain 400 "invalid signature"))))
    (plain 404 "not found")))
