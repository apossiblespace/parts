(ns aps.parts.frontend.copy.billing
  "Every user-facing string about billing, the free trial and a lapsed
   account (ADR-0021), in one place: the Account page's Billing section,
   the Maps list banner and the Map's read-only strip. Edit the wording
   here; the views decide which string shows and add the markup (links,
   bold, buttons). British English. A string that takes a value is a
   function.")

;;; What ended (a lapsed account)

(def trial-subject "Your free trial")
(def subscription-subject "Your subscription")
(def paid-time-subject "Your paid time")

(defn ended
  "\"Your free trial has ended.\""
  [subject]
  (str subject " has ended."))

(defn ended-change-refused
  "After the server refused a change because the account has lapsed."
  [subject]
  (str subject " has ended, so your last change wasn’t saved."))

(defn ended-on
  "\"Your subscription ended on 1 June 2026.\""
  [subject date]
  (str subject " ended on " date "."))

(def map-read-only "This Map is read-only.")
(def lapsed-body "You can still open, export and download your Maps. Subscribe to keep editing.")

;;; Free trial

(defn trial-ends
  "`when-phrase` is from `trial-when`: \"Your free trial ends in 3 days, on
   18 November.\""
  [when-phrase]
  (str "Your free trial ends " when-phrase "."))

(defn trial-when
  "How far off the trial's end is, for `trial-ends`."
  [days day-month]
  (case days
    0 "today"
    1 (str "tomorrow, " day-month)
    (str "in " days " days, on " day-month)))

(def trial-ending-body "Subscribe to keep editing after that. Your Maps stay yours either way.")
(def trial-label "Free trial")

(defn trial-days-left [days]
  (case days
    0 "Last day"
    1 "1 day left"
    (str days " days left")))

(defn trial-ends-on [date]
  (str "Ends on " date ". Nothing is charged unless you subscribe."))

;;; Payment failing

(def payment-failed "Your last payment didn’t go through.")
(def payment-failed-body "Update your card to keep your subscription.")
(def update-card "Update card")

;;; Subscription facts

(def subscribe "Subscribe")
(def resubscribe "Resubscribe")
(def manage-subscription "Manage subscription")
(def manage-hint "Change plan, update your card, or cancel.")
(def plan-label "Plan")
(def next-payment-label "Next payment")
(def ends-on-label "Ends on")
(def subscription-fallback "Subscription")
(def checking "Checking…")
(def thanks "Thank you for subscribing!")
(def finalising "Finalising your subscription…")
(def finalising-slow "This is taking longer than usual. If it does not finish in a few minutes, please ")
(def contact-us "contact us")

(def badge-active "Active")
(def badge-ending "Ending")
(def badge-cancelled "Cancelled")
(def badge-ended "Ended")
(def badge-payment-failed "Payment failed")

(defn next-payment
  "\"£15 on 29 August 2026\"."
  [amount date]
  (str amount " on " date))

(def not-charged-again "You won’t be charged again.")

(defn paid-until [date]
  (str "Paid until " date ". " not-charged-again))

;;; Beta and group practices

(def beta-banner
  "A subscription is not required to use Parts while in beta. Subscribing now supports continued development.")

(def group-note "Individual plans are valid for a single practitioner. For a group practice, please ")
(def group-subject "Group practice billing")
