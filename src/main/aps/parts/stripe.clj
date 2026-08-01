(ns aps.parts.stripe
  "Minimal Stripe client for self-serve subscriptions (TASK-046).

   Deliberately not a library: the whole integration is two POSTs against
   Stripe's hosted surfaces — Checkout Sessions and the Customer Portal —
   plus webhook signature verification, all against a famously stable,
   version-pinned REST API. `aps.parts.api.billing` owns what the events
   *mean*; this namespace only moves bytes.

   The payload builders are pure functions with closed allowlists. That is
   a code invariant, not a style choice: nothing that reaches Stripe may
   ever carry client names, Map titles, or any clinical content — only the
   therapist's own billing identity (task-009 AC#9). The regression test
   in `aps.parts.stripe-test` pins the exact key set."
  (:require
   [clojure.string :as cstr]
   [jsonista.core :as json]
   [org.httpkit.client :as http])
  (:import
   (java.net URLEncoder)
   (java.security MessageDigest)
   (java.time Instant LocalDate ZoneOffset)
   (java.time.format DateTimeFormatter)
   (java.util Locale)
   (javax.crypto Mac)
   (javax.crypto.spec SecretKeySpec)))

(def api-version
  "The pinned Stripe API version, sent on every request. Stripe serves old
   versions indefinitely, so upgrading is always an explicit change here —
   made after reading the changelog — never something a Stripe release can
   force."
  "2026-06-24.dahlia")

(def ^:private api-base "https://api.stripe.com")

(def live-subscription-statuses
  "Subscription statuses that mean the account has an ongoing subscription
   relationship: covered (`active`, `trialing`), in dunning (`past_due`,
   `unpaid` — Stripe is still trying to collect), or our own synthetic
   `canceling` (active with cancel_at_period_end set — still live, still
   reversible in the portal, so a second checkout must stay refused).
   Everything else (`canceled`, `incomplete`, `incomplete_expired`,
   `paused`) means there is no live subscription and subscribing again is
   the right offer."
  #{"active" "trialing" "past_due" "unpaid" "canceling"})

;; --- Form encoding ---------------------------------------------------------
;;
;; Stripe requests are application/x-www-form-urlencoded with bracket syntax
;; for structure: {:line_items [{:price "p"}]} → line_items[0][price]=p.

(defn- url-encode [s]
  (URLEncoder/encode (str s) "UTF-8"))

(defn- flatten-params
  "Flatten a nested params map into [key value] pairs using Stripe's
   bracket syntax. Maps nest as [k], vectors as [i]."
  [prefix params]
  (cond
    (map? params)
    (mapcat (fn [[k v]]
              (flatten-params (if prefix
                                (str prefix "[" (name k) "]")
                                (name k))
                              v))
            params)

    (sequential? params)
    (mapcat (fn [i v] (flatten-params (str prefix "[" i "]") v))
            (range) params)

    :else [[prefix params]]))

(defn form-encode
  "Encode a nested params map as a Stripe-style form body."
  [params]
  (->> (flatten-params nil params)
       (map (fn [[k v]] (str (url-encode k) "=" (url-encode v))))
       (cstr/join "&")))

;; --- Payload builders ------------------------------------------------------

(defn checkout-session-params
  "Params for a subscription-mode Checkout Session. Pure; the key set is a
   closed allowlist pinned by a regression test (see namespace docstring).

   An account already linked to a Stripe customer passes `:customer` so
   Stripe reuses it; otherwise `:customer_email` pre-fills the payment page.
   No `payment_method_types`: Stripe then offers whatever payment methods
   the Dashboard enables (dynamic payment methods).

   `:trial-end` (epoch seconds, optional) delays the first charge to that
   moment — how a resubscriber's remaining paid window is honoured rather
   than double-billed. Stripe requires it at least 48 hours out; the
   caller decides whether to send it. Checkout renders it as a free
   trial (\"N days free, £0.00 due today\"), so a note above the pay
   button reframes it: the days are already paid for, not a gift."
  [{:keys [user-id email customer plan price-id base-url trial-end]}]
  (cond-> {:mode                   "subscription"
           :line_items             [{:price price-id :quantity 1}]
           :client_reference_id    (str user-id)
           :metadata               {:plan (name plan)}
           ;; Requires an active registration in Stripe Tax settings
           :automatic_tax          {:enabled true}
           :integration_identifier "parts-selfserve-mkwzqhtr"
           :success_url            (str base-url "/app/account?checkout=success")
           :cancel_url             (str base-url "/app/account")}
    customer       (assoc :customer customer)
    (not customer) (assoc :customer_email email)
    trial-end      (assoc :subscription_data {:trial_end trial-end}
                          :custom_text
                          {:submit {:message (str "Your remaining paid time is applied — "
                                                  "your first payment will be on "
                                                  (.format (LocalDate/ofInstant
                                                            (Instant/ofEpochSecond trial-end)
                                                            ZoneOffset/UTC)
                                                           (DateTimeFormatter/ofPattern
                                                            "d MMMM uuuu" Locale/UK))
                                                  ".")}})))

(defn portal-session-params
  "Params for a Customer Portal session — the hosted page where a
   subscriber updates their payment method or cancels."
  [{:keys [customer base-url]}]
  {:customer   customer
   :return_url (str base-url "/app/account")})

;; --- HTTP ------------------------------------------------------------------

(defn- request!
  "Make a Stripe API request; return the decoded response body. Throws
   ex-info on transport failure or an error status. Request params are
   never logged or attached to exceptions — they carry the therapist's
   email."
  [{:keys [secret-key]} method path params]
  (let [{:keys [status body error]}
        @(http/request (cond-> {:method  method
                                :url     (str api-base path)
                                :headers {"Authorization"  (str "Bearer " secret-key)
                                          "Stripe-Version" api-version}
                                :timeout 30000}
                         params (-> (assoc :body (form-encode params))
                                    (assoc-in [:headers "Content-Type"]
                                              "application/x-www-form-urlencoded"))))]
    (cond
      error
      (throw (ex-info "Stripe request failed" {:type :stripe-transport :path path} error))

      (>= status 400)
      (throw (ex-info "Stripe API error"
                      {:type   :stripe-api
                       :status status
                       :path   path
                       :error  (some-> body
                                       (json/read-value json/keyword-keys-object-mapper)
                                       :error
                                       (select-keys [:type :code :message]))}))

      :else
      (json/read-value body json/keyword-keys-object-mapper))))

(defn get-subscription!
  "Fetch a subscription by id. The webhook reads two things off it: the
   `:status`, and the paid period's end — which post-Basil API versions
   carry per item under [:items :data n :current_period_end], not at the
   subscription's top level."
  [stripe-config subscription-id]
  (request! stripe-config :get (str "/v1/subscriptions/" subscription-id) nil))

(defn delete-customer!
  "Delete a Customer. Stripe immediately cancels the customer's active
   subscriptions and removes their identity from the account's customer
   list, while retaining the invoices it must keep as financial records.
   The erasure path's one Stripe call — see
   `aps.parts.billing/release-stripe-customer!`."
  [stripe-config customer-id]
  (request! stripe-config :delete (str "/v1/customers/" customer-id) nil))

(defn- not-found?
  "True when `e` is this namespace's error for a 404. Private: callers
   express already-gone tolerance through `delete-customer-if-present!`."
  [e]
  (and (= :stripe-api (:type (ex-data e)))
       (= 404 (:status (ex-data e)))))

(defn delete-customer-if-present!
  "Delete a Customer, tolerating one that is already gone: true when
   deleted, false when Stripe reports it missing. Anything else throws —
   the caller's retry mechanism (webhook redelivery, hourly purge run)
   depends on real failures propagating."
  [stripe-config customer-id]
  (try
    (delete-customer! stripe-config customer-id)
    true
    (catch clojure.lang.ExceptionInfo e
      (if (not-found? e)
        false
        (throw e)))))

(defn create-checkout-session!
  "Create a Checkout Session; returns the session (`:url` is the hosted
   payment page to redirect the browser to)."
  [stripe-config params]
  (request! stripe-config :post "/v1/checkout/sessions" params))

(defn create-portal-session!
  "Create a Customer Portal session; returns the session (`:url` is the
   hosted management page to redirect the browser to)."
  [stripe-config params]
  (request! stripe-config :post "/v1/billing_portal/sessions" params))

;; --- Webhook signature verification ----------------------------------------
;;
;; Stripe signs each delivery: the Stripe-Signature header carries a unix
;; timestamp and one or more HMAC-SHA256 signatures of "<timestamp>.<raw
;; body>" under the endpoint's signing secret. Verification must use the
;; exact raw bytes received (any re-serialisation breaks it), compare in
;; constant time, and reject stale timestamps to block replays.
;; https://docs.stripe.com/webhooks#verify-events

(def ^:private default-tolerance-seconds
  "How far a delivery's timestamp may drift from our clock — Stripe's
   libraries use the same 5 minutes."
  300)

(defn- hmac-sha256-hex [^String secret ^String payload]
  (let [mac (doto (Mac/getInstance "HmacSHA256")
              (.init (SecretKeySpec. (.getBytes secret "UTF-8") "HmacSHA256")))]
    (apply str (map #(format "%02x" %) (.doFinal mac (.getBytes payload "UTF-8"))))))

(defn- parse-signature-header
  "Parse a Stripe-Signature header into {:timestamp long, :signatures [hex]}.
   Returns nil when the header is missing or unreadable. Only v1 entries
   count; v0 is Stripe's legacy scheme."
  [header]
  (when (string? header)
    (let [entries    (for [part  (cstr/split header #",")
                           :let  [[k v] (cstr/split part #"=" 2)]
                           :when (and k v)]
                       [(cstr/trim k) v])
          timestamp  (some (fn [[k v]] (when (= "t" k) (parse-long v))) entries)
          signatures (into [] (keep (fn [[k v]] (when (= "v1" k) v))) entries)]
      (when (and timestamp (seq signatures))
        {:timestamp timestamp :signatures signatures}))))

(defn- constant-time-eq? [^String a ^String b]
  (MessageDigest/isEqual (.getBytes a "UTF-8") (.getBytes b "UTF-8")))

(defn valid-signature?
  "True when `sig-header` proves Stripe signed exactly `payload` (the raw
   request body) with `secret` recently. False — never an exception — for
   anything malformed, tampered, or stale.

   `opts` exists for tests: `:now` (epoch seconds, defaults to the clock)
   and `:tolerance` (seconds, default 300)."
  ([payload sig-header secret]
   (valid-signature? payload sig-header secret {}))
  ([payload sig-header secret {:keys [now tolerance]}]
   (let [now       (or now (quot (System/currentTimeMillis) 1000))
         tolerance (or tolerance default-tolerance-seconds)]
     (if-let [{:keys [timestamp signatures]} (parse-signature-header sig-header)]
       (let [expected (hmac-sha256-hex secret (str timestamp "." payload))]
         (boolean
          (and (<= (abs (- now timestamp)) tolerance)
               (some #(constant-time-eq? expected %) signatures))))
       false))))

(defn parse-event
  "Decode a webhook event payload (call only after `valid-signature?`)."
  [payload]
  (json/read-value payload json/keyword-keys-object-mapper))
