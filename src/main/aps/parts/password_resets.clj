(ns aps.parts.password-resets
  "Self-serve password reset — the magic-link token schema behind
   /reset-password and /reset/:token (TASK-109).

   A reset is a single-use bearer credential like an invitation
   (`aps.parts.invitations`), with two deliberate differences: it is minted
   by the account holder rather than the operator, and it expires after one
   hour rather than 180 days — redeeming it grants access to an existing
   account holding clinical data, so a lost link must die quickly.
   Lifecycle: issued -> used (`used_at`) | expired (`expires_at`)."
  (:require
   [aps.parts.common.utils :refer [normalize-email]]
   [aps.parts.config :as conf]
   [aps.parts.db :as db]
   [com.brunobonacci.mulog :as mulog]))

(defn reset-url
  "The full magic-link URL for `token`, built from the configured public
   base URL (see `config/base-url`)."
  [token]
  (str (conf/base-url) "/reset/" token))

(def ^:private active-clause
  "HoneySQL `where` fragment for a reset that is still live — neither used
   nor expired. HoneySQL flattens nested `:and`, so it composes inside a
   larger `[:and ...]`."
  [:and
   [:is :used_at nil]
   [:> :expires_at [:now]]])

(defn find-active
  "The active (unused, unexpired) reset row for `token`, or nil. Used by
   the redemption handler to validate a magic link."
  [token]
  (db/query-one
   (db/sql-format {:select [:*]
                   :from   [:password_resets]
                   :where  [:and [:= :token token] active-clause]})))

(defn claim!
  "Atomically mark the reset for `token` used — but only if it is still
   active. Returns the claimed row, or nil if the token was unknown,
   already used, or expired. The conditional UPDATE is the real guard
   against a double redemption; runs on the caller's transaction `tx`."
  [token tx]
  (first
   (db/update! :password_resets
               {:used_at [:now]}
               [:and [:= :token token] active-clause]
               tx)))

(defn create-reset!
  "Ensure a live reset token exists for the account registered under
   `email`; returns {:email :token :url}, or nil when no account exists —
   the caller must respond identically either way (account existence is
   sensitive).

   Idempotent while a link is live: a repeat request returns the *same*
   token, so the caller re-sends the link already sitting in the user's
   inbox. That keeps a lost-email retry harmless and means a stranger
   re-submitting the form cannot invalidate the account holder's pending
   link. Two truly concurrent first requests can each mint a token — both
   go only to the account holder, are single-use, and die within the hour,
   so no stricter guarantee is needed."
  [email]
  (when-let [user (db/query-one
                   (db/sql-format {:select [:id :email]
                                   :from   [:users]
                                   :where  [:= :email (normalize-email email)]}))]
    (let [token (or (:token (db/query-one
                             (db/sql-format
                              {:select [:token]
                               :from   [:password_resets]
                               :where  [:and [:= :user_id (:id user)] active-clause]})))
                    (let [fresh (str (random-uuid))]
                      (db/insert! :password_resets {:user_id (:id user) :token fresh})
                      (mulog/log ::reset-created :email (:email user))
                      fresh))]
      {:email (:email user)
       :token token
       :url   (reset-url token)})))

(defn delete-expired!
  "Sweep reset rows a week past expiry; returns the number removed.
   Correctness never depends on this (reads filter on `active-clause`) —
   it only stops a public form growing the table forever. The grace week
   keeps recent rows visible for operator debugging of 'no email arrived'
   reports."
  [ds]
  (db/affected-row-count
   (db/delete! :password_resets
               [:< :expires_at [:- [:now] [:cast "7 days" :interval]]]
               ds)))
