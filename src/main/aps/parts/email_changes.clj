(ns aps.parts.email-changes
  "Self-service email change (TASK-154.02): the link schema behind
   `POST /api/account/email` and `/email-change/:token`.

   A change is a single-use link sent to the NEW address. The password
   was checked when the User asked; opening the link proves they control
   the new address. It expires after one hour. Until it is used, the
   account keeps its old address. Lifecycle: issued -> used (`used_at`) |
   expired (`expires_at`) | replaced (a newer request).

   A used change keeps the previous address (`old_email`) for 30 days, so
   if someone else changed it, the owner who writes from the old address
   can be recognised. Then the sweep deletes the row.

   Whether an address has an account is never told to the requester:
   `request!` answers the same for a taken address, and only the owner
   of that address is told."
  (:require
   [aps.parts.common.models.user :as model]
   [aps.parts.common.utils :refer [normalize-email]]
   [aps.parts.config :as conf]
   [aps.parts.db :as db]
   [clojure.spec.alpha :as s]))

(def ^:private active-clause
  [:and
   [:is :used_at nil]
   [:> :expires_at [:now]]])

(defn confirm-url
  "The full link URL for `token` (see `config/base-url`)."
  [token]
  (str (conf/base-url) "/email-change/" token))

(defn- rows [ds query]
  (db/query ds (db/sql-format query)))

(defn- email-of [ds user-id]
  (:email (first (rows ds {:select [:email] :from [:users] :where [:= :id (db/->uuid user-id)]}))))

(defn- email-taken? [ds email]
  (seq (rows ds {:select [:id] :from [:users] :where [:= :email email]})))

(defn request!
  "Starts a change of the account `user-id` to `new-email`. Throws a
   :validation error when the address is not an email address or is the
   account's own. For an address another account uses, it changes
   nothing and returns {:new-email :taken? true}: the caller must answer
   as if a link was sent. Otherwise it replaces any change the User asked
   for before, so only the newest link works, and returns {:new-email
   :token :url}."
  [user-id new-email]
  (let [new-email (normalize-email new-email)]
    (when-not (s/valid? ::model/email new-email)
      (throw (ex-info "Enter a valid email address." {:type :validation})))
    (db/with-transaction
      (fn [tx]
        (let [current (email-of tx user-id)]
          (when (= current new-email)
            (throw (ex-info "That is already the email address of your account." {:type :validation})))
          (if (email-taken? tx new-email)
            {:new-email new-email :taken? true}
            (do
              (db/update! :email_changes {:used_at [:now]}
                          [:and [:= :user_id (db/->uuid user-id)] active-clause] tx)
              (let [token (str (random-uuid))]
                (db/insert! :email_changes {:user_id (db/->uuid user-id) :new_email new-email :token token} tx)
                {:new-email new-email :token token :url (confirm-url token)}))))))))

(defn find-active
  "The active (unused, unexpired) change for `token`, or nil."
  [token]
  (db/query-one (db/sql-format {:select [:*]
                                :from   [:email_changes]
                                :where  [:and [:= :token token] active-clause]})))

(defn- move-email-keyed-rows!
  "Moves the account's rows in the tables keyed by email (an invitation,
   a waiting-list signup) from `old-email` to `new-email`, so erasure,
   which finds them by the account's address, still finds them. When the
   new address has a row of its own, the old one is deleted."
  [tx old-email new-email]
  (doseq [table [:invitations :waitlist_signups]]
    (db/update! table {:email new-email}
                [:and [:= :email old-email]
                 [:not [:exists {:select [1] :from [table] :where [:= :email new-email]}]]]
                tx)
    (db/delete! table [:= :email old-email] tx)))

(defn- set-email!
  "Sets the account's address. Another account taking it at the same
   moment (the UNIQUE constraint) is the :email-taken refusal, not a
   database error."
  [tx user-id email]
  (try
    (db/update! :users {:email email} [:= :id user-id] tx)
    (catch java.sql.SQLException e
      (if (= "23505" (.getSQLState e))
        (throw (ex-info "Another account took the address" {:type :email-taken}))
        (throw e)))))

(defn confirm!
  "Uses the link `token`: in one transaction, claims it (once only),
   moves the account to the new address, keeps the old one with the
   change, moves the account's email-keyed rows, and cancels its open
   password-reset links, which were sent to the old address. Returns
   {:user-id :old-email :new-email}. Throws ex-info `{:type
   :link-unavailable}` for an unknown, used or expired link, and
   `{:type :email-taken}` when another account took the address after
   the request."
  [token]
  (db/with-transaction
    (fn [tx]
      (let [claimed (first (db/update! :email_changes {:used_at [:now]}
                                       [:and [:= :token token] active-clause] tx))]
        (when-not claimed
          (throw (ex-info "Email change link unknown, used, or expired" {:type :link-unavailable})))
        (let [{:keys [user_id new_email]} claimed
              old-email                   (email-of tx user_id)]
          (when (email-taken? tx new_email)
            (throw (ex-info "Another account took the address" {:type :email-taken})))
          (set-email! tx user_id new_email)
          (db/update! :email_changes {:old_email old-email} [:= :id (:id claimed)] tx)
          (move-email-keyed-rows! tx old-email new_email)
          (db/update! :password_resets {:used_at [:now]}
                      [:and [:= :user_id user_id] [:is :used_at nil]] tx)
          {:user-id user_id :old-email old-email :new-email new_email})))))

(defn delete-expired!
  "Sweep rows that are done with; returns the number removed. A link that
   was never used (or was replaced) goes a week after it expired; a
   confirmed change, with its previous address, goes 30 days after it was
   used. Reads never depend on it (they filter on `active-clause`)."
  [ds]
  (db/affected-row-count
   (db/delete! :email_changes
               [:or
                [:and [:= :old_email nil]
                 [:< :expires_at [:- [:now] [:cast "7 days" :interval]]]]
                [:and [:<> :old_email nil]
                 [:< :used_at [:- [:now] [:cast "30 days" :interval]]]]]
               ds)))
