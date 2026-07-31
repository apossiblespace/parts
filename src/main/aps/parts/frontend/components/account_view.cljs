(ns aps.parts.frontend.components.account-view
  "Pure view logic for the Account page's Billing card. Deliberately free
   of uix/React so it can be unit-tested directly under the cljs suite —
   the `account` component is a thin shell over `billing-view`."
  (:require
   [aps.parts.common.constants :as c]
   [clojure.string :as str]))

(def ^:private month-names
  ["January" "February" "March" "April" "May" "June" "July"
   "August" "September" "October" "November" "December"])

(defn- fmt-iso-date
  "Render an ISO `YYYY-MM-DD` string as e.g. `8 July 2026` by splitting the
   string — never via `js/Date`, so a date-only value can't drift a day
   across timezones. Returns nil for anything that doesn't parse."
  [iso]
  (when iso
    (let [[y m d] (str/split iso #"-")
          mi      (when m (dec (js/parseInt m 10)))]
      (when (and y mi d (<= 0 mi 11))
        (str (js/parseInt d 10) " " (nth month-names mi) " " y)))))

(defn- in-days
  "\" in 29 days (29 August 2026)\", \" in 1 day (…)\", \" today (…)\", or
   \" on 29 August 2026\" when the day count is unknown."
  [days through]
  (cond
    (nil? days)  (str " on " through)
    (zero? days) (str " today (" through ")")
    :else        (str " in " days (if (= 1 days) " day" " days")
                      " (" through ")")))

(defn- status-line
  "The Billing section's one status sentence, dot and description
   combined: {:tone :success|:info|:warning|:error, :headline \"…\",
   :body \"…\"} — the component renders the coloured dot, the bold
   headline, then the body. Nil when there is nothing to report (no
   subscription, nothing paid): the pricing cards say that plainly
   enough.

   The interesting state is a paid window with no live subscription
   behind it (cancelled, or extended by hand): that must read as
   \"will expire — you will not be charged again\", never as \"active\"
   above Subscribe buttons. The resubscribe invitation is appended only
   when the cards are actually shown (`:resubscribe`, not `:none`)."
  [action plan {:keys [status days_remaining paid_through_date]}]
  (let [through (fmt-iso-date paid_through_date)
        cards?  (= :resubscribe action)]
    (case action
      :manage
      ;; Renewal framing, not expiry: the charge lands on the paid-through
      ;; date. The amount comes from the shared plan constants so it can't
      ;; drift from the pricing cards.
      (let [price (some #(when (= (:plan %) (keyword plan)) (:price %))
                        c/subscription-plans)]
        {:tone     :success
         :headline "Subscription active."
         :body     (str " You will be next charged "
                        (when price (str price " "))
                        "on " through ".")})

      :activating
      {:tone :info :headline "Finalising your subscription" :body "…"}

      ;; Cancelled at period end: the subscription still exists (and can
      ;; be reversed in the portal), but no further charge is coming.
      :cancelling
      {:tone     :warning
       :headline "Subscription will expire"
       :body     (str (in-days days_remaining through)
                      ". You will not be charged again. Changed your mind?"
                      " You can renew your subscription below.")}

      ;; :resubscribe and :none share the standing-driven wording; only
      ;; :resubscribe appends the invitation to use the cards below.
      (case status
        :paid
        {:tone     :warning
         :headline "Subscription will expire"
         :body     (str (in-days days_remaining through)
                        ". You will not be charged again."
                        (when cards?
                          (str " If you wish to continue using Parts after "
                               through ", please resubscribe below.")))}

        :overdue
        {:tone     :error
         :headline "Subscription expired"
         :body     (str " on " through ". You will not be charged again."
                        (when cards?
                          " If you wish to continue using Parts, please resubscribe below."))}

        (when cards?
          {:tone     :warning
           :headline "Subscription cancelled"
           :body     " — you will not be charged again. You're welcome to resubscribe below."})))))

(defn billing-view
  "View-model for the Billing card, from the server's `:billing` facts and
   `:standing` summary. `:action` is what the card offers:

   - `:loading` — the account record hasn't loaded yet (the login response
     carries no `:billing`; the mount-time check-auth refresh fills it in)
   - `:manage` — a live subscription exists: the Customer Portal button
   - `:cancelling` — cancelled at period end: still live and reversible
     in the portal (so the Manage button stays), but the line says the
     window ends and no charge is coming
   - `:activating` — just back from a completed Checkout
     (`checkout-pending?`) but the webhook hasn't landed yet: no buttons.
     Subscribe would contradict the payment that just went through, and
     Manage would mint a portal session the backend still refuses. The
     page polls until the server reports the subscription live.
   - `:resubscribe` — no live subscription but there is history to
     explain (cancelled, or a paid/lapsed window): the status line says
     what happens next and the cards' CTA reads Resubscribe
   - `:subscribe` — self-serve is enabled and there's nothing to explain
   - `:none` — self-serve is off

   `:status-line` (see `status-line`) is the single dot-plus-sentence
   summary; `:cta` is the pricing cards' button label."
  [{:keys [billing standing checkout-pending?]}]
  (if (nil? billing)
    {:action :loading :status-line nil}
    (let [history? (or (:subscription_cancelled billing)
                       (contains? #{:paid :overdue} (:status standing)))
          action   (cond
                     ;; Pending cancellation outranks plain active: the page
                     ;; must not promise a renewal that will never come.
                     (:subscription_cancelling billing) :cancelling

                     ;; A live status implies a linked customer — the server
                     ;; only ever writes the two together.
                     (:subscription_active billing) :manage

                     (and checkout-pending?
                          (:self_serve_enabled billing)) :activating

                     (and history?
                          (:self_serve_enabled billing)) :resubscribe

                     (:self_serve_enabled billing) :subscribe
                     :else                         :none)]
      {:action      action
       :status-line (status-line action (:subscription_plan billing) standing)
       :cta         (if (= :resubscribe action) "Resubscribe" "Subscribe")})))
