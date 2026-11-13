(ns aps.parts.trial-reminders
  "The emails that remind a User that their Free trial ends. See ADR-0021.

   There are two kinds of reminder:
   - `:ending` goes to an account in its Free trial from 7 days before the
     trial ends.
   - `:ended` goes on the first read-only day after the trial, and for up
     to 7 days after it. Only accounts created on or after the payment
     start date get it. Beta accounts get the announcement instead.

   The window of days lets a reminder go out late after a deploy or an
   outage. The `trial_reminders` table records each send, so each kind
   goes to an account only once. They are Service notices, so they ignore
   the Product-update opt-out. `aps.parts.jobs.trial-reminders` runs
   `send-due!` every hour."
  (:require
   [aps.parts.billing :as billing]
   [aps.parts.config :as conf]
   [aps.parts.db :as db]
   [aps.parts.email-layout :as layout]
   [aps.parts.mail :as mail]
   [com.brunobonacci.mulog :as mulog])
  (:import
   (java.time LocalDate ZoneOffset)
   (java.time.format DateTimeFormatter)
   (java.util Locale)))

(def ^:private window-days 7)

(defn- created-on
  [created-at]
  (LocalDate/ofInstant (db/->instant created-at) ZoneOffset/UTC))

(defn due-reminder
  "Returns the reminder that the account `row` is due on `today`, as
   `{:kind :ends}`, or nil. `:ends` is the last day of the Free trial.
   `row` has the keys of `billing/billing-facts`, and `start` is the
   payment start date. The send log is not read here."
  [row ^LocalDate start ^LocalDate today]
  (when start
    (let [{:keys [reason trial_ends_on]} (billing/entitlement row start today)
          ends                           (LocalDate/parse trial_ends_on)
          kind                           (cond
                                           (and (= :trial reason)
                                                (not (.isBefore today (.minusDays ends window-days))))
                                           :ending

                                           (and (= :lapsed reason)
                                                (not (.isBefore (created-on (:created_at row)) start))
                                                (not (.isAfter today (.plusDays ends window-days))))
                                           :ended)]
      (when kind
        {:kind kind :ends ends}))))

(def ^:private date-format
  (DateTimeFormatter/ofPattern "d MMMM yyyy" Locale/UK))

(defn- account-link
  []
  (str "[Account page](" (conf/base-url) "/app/account)"))

(defn- body
  [kind ^LocalDate ends]
  (let [date (.format date-format ends)]
    (case kind
      :ending
      (str "Hello,

Your free trial of Parts ends on " date ". After that day, your account
becomes read-only until you subscribe.

To keep editing your Maps, subscribe on your " (account-link) ".

Your Maps stay yours either way. A read-only account can still open, view
and export every Map, and download all its data. Nothing is deleted.

The Parts team")
      :ended
      (str "Hello,

Your free trial of Parts ended on " date ", so your account is now
read-only.

You can still open, view and export your Maps, and download all your data.
Nothing is deleted.

To edit your Maps again, subscribe on your " (account-link) ".

The Parts team"))))

(defn message
  "Returns the postal message map of the reminder `kind` to `email`, for a
   Free trial that ends on the date `ends`."
  [kind email ^LocalDate ends]
  {:to      email
   :subject (case kind
              :ending (str "Your Parts free trial ends on " (.format date-format ends))
              :ended  "Your Parts free trial has ended")
   :body    (layout/alternative
             (layout/content (body kind ends) (layout/transactional-footer email)))})

(defn- sent-kinds
  "Returns the set of `[user-id kind]` pairs in the send log."
  []
  (into #{}
        (map (juxt :user_id (comp keyword :kind)))
        (db/query (db/sql-format {:select [:user_id :kind] :from [:trial_reminders]}))))

(defn- claim!
  "Records that `kind` goes to `user-id`. Returns true, or false when the
   log already has the pair."
  [user-id kind]
  (seq (db/query (db/sql-format {:insert-into :trial_reminders
                                 :values      [{:user_id user-id :kind (name kind)}]
                                 :on-conflict [:user_id :kind]
                                 :do-nothing  true
                                 :returning   [:user_id]}))))

(defn- release!
  [user-id kind]
  (db/delete! :trial_reminders [:and [:= :user_id user-id] [:= :kind (name kind)]]))

(defn- send-one!
  "Sends the reminder `kind` to the account `row`. Returns true when it
   was sent. A failed send is removed from the log, so that the next run
   tries it again."
  [row {:keys [kind ends]}]
  (let [user-id (:id row)]
    (when (claim! user-id kind)
      (try
        (mail/send-personal! (message kind (:email row) ends))
        (mulog/log ::sent :kind kind :user-id user-id)
        true
        (catch Exception e
          (release! user-id kind)
          (mulog/log ::send-failed :kind kind :user-id user-id
                     :error-class (.getName (class e)))
          false)))))

(defn send-due!
  "Sends every reminder that is due on `today` and not in the send log.
   Returns the number sent. Without a payment start date it sends nothing."
  ([] (send-due! (conf/payment-start-date) (LocalDate/now ZoneOffset/UTC)))
  ([start today]
   (if-not start
     0
     (let [sent (sent-kinds)]
       (reduce (fn [n row]
                 (let [due (due-reminder row start today)]
                   (if (and due
                            (not (sent [(:id row) (:kind due)]))
                            (send-one! row due))
                     (inc n)
                     n)))
               0
               (billing/every-billing-facts))))))
