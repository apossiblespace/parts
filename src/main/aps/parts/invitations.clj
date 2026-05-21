(ns aps.parts.invitations
  "Founding Circle invitations — schema access plus the operator REPL
   tooling for the concierge launch.

   An invitation is an operator-minted, single-use bearer credential: the
   `token` in a magic link (`/invite/<token>`) authorises creating one
   account. Lifecycle: issued -> redeemed | revoked (soft, via
   `revoked_at`). No expiry. See CONTEXT.md (Invitation, Founding Circle).

   Operator workflow (production REPL, Path beta — links are BCC'd from the
   operator's own mail client):

     (pending-waitlist!)          ; who still needs inviting
     (generate-invitation! email) ; mint / fetch a magic link
     (print-invitation-links!)    ; email,magic_link lines to copy out
     (revoke-invitation! email)   ; kill a link"
  (:require
   [aps.parts.config :as conf]
   [aps.parts.db :as db]
   [com.brunobonacci.mulog :as mulog]))

(defn- new-token
  "A fresh, unguessable invitation token. A UUIDv4 (122 bits of entropy) is
   URL-safe and matches the project's other server-minted credentials."
  []
  (str (random-uuid)))

(defn magic-link
  "The full magic-link URL for `token`, built from the configured public
   base URL (see `config/base-url`)."
  [token]
  (str (conf/base-url) "/invite/" token))

(defn- ->invite
  "The shape returned to the operator: email, token, and the magic link."
  [row]
  {:email      (:email row)
   :token      (:token row)
   :magic-link (magic-link (:token row))})

;; -- schema access (used by the redemption handler) -----------------------

(def ^:private active-clause
  "HoneySQL `where` fragment for an invitation that is still live — neither
   redeemed nor revoked. HoneySQL flattens nested `:and`, so it composes
   inside a larger `[:and ...]`."
  [:and [:is :redeemed_at nil] [:is :revoked_at nil]])

(defn find-active
  "The active (un-redeemed, un-revoked) invitation row for `token`, or nil.
   Used by the redemption handler to validate a magic link."
  [token]
  (db/query-one
   (db/sql-format {:select [:*]
                   :from   [:invitations]
                   :where  [:and [:= :token token] active-clause]})))

(defn claim!
  "Atomically mark the invitation for `token` redeemed — but only if it is
   still active. Returns the claimed row, or nil if the token was unknown,
   already redeemed, or revoked. The conditional UPDATE is the real guard
   against a double redemption; runs on the caller's transaction `tx`."
  [token tx]
  (first
   (db/update! :invitations
               {:redeemed_at [:now]}
               [:and [:= :token token] active-clause]
               tx)))

;; -- operator REPL tooling ------------------------------------------------

(defn generate-invitation!
  "Ensure a live invitation exists for `email`; return
   {:email :token :magic-link}, or nil if the email has already redeemed an
   invitation.

   Idempotent: an email with an active invitation gets the *same* token
   back, so re-running `print-invitation-links!` is stable. A revoked
   invitation is re-issued with a fresh token. Off-waitlist emails are
   accepted — an invitation need not correspond to a waitlist signup."
  [email]
  (let [existing (db/query-one
                  (db/sql-format {:select [:*]
                                  :from   [:invitations]
                                  :where  [:= :email email]}))]
    (cond
      (nil? existing)
      (let [row (db/insert! :invitations {:email email :token (new-token)})]
        (mulog/log ::invitation-created :email email)
        (->invite row))

      (:redeemed_at existing)
      (do (println (str "Skipped " email " — invitation already redeemed."))
          nil)

      (:revoked_at existing)
      (let [row (first (db/update! :invitations
                                   {:token      (new-token)
                                    :revoked_at nil
                                    :invited_at [:now]}
                                   [:= :email email]))]
        (mulog/log ::invitation-reissued :email email)
        (->invite row))

      :else
      (->invite existing))))

(defn revoke-invitation!
  "Soft-revoke the active invitation for `email` (sets `revoked_at`); the
   magic link stops working. Returns the revoked row, or nil if there was
   no active invitation to revoke."
  [email]
  (if-let [row (first (db/update! :invitations
                                  {:revoked_at [:now]}
                                  [:and [:= :email email] active-clause]))]
    (do (mulog/log ::invitation-revoked :email email)
        (println (str "Revoked invitation for " email))
        row)
    (do (println (str "No active invitation to revoke for " email))
        nil)))

(defn pending-waitlist!
  "Print and return the waitlist emails that have no invitation row at all
   — who still needs inviting. Redeemed and revoked emails already have a
   row, so they are deliberately excluded."
  []
  (let [rows (db/query
              (db/sql-format
               {:select   [:w.email]
                :from     [[:waitlist_signups :w]]
                :where    [:not [:exists {:select [1]
                                          :from   [[:invitations :i]]
                                          :where  [:= :i.email :w.email]}]]
                :order-by [:w.created_at]}))]
    (doseq [{:keys [email]} rows] (println email))
    rows))

(defn print-invitation-links!
  "Print `email,magic_link` lines for every waitlist signup that has an
   active, un-redeemed invitation — the list to BCC out. Read-only: it
   never mints an invitation. Returns the number of lines printed."
  []
  (let [rows (db/query
              (db/sql-format
               {:select   [:w.email :i.token]
                :from     [[:waitlist_signups :w]]
                :join     [[:invitations :i] [:= :i.email :w.email]]
                :where    [:and [:is :i.redeemed_at nil]
                           [:is :i.revoked_at nil]]
                :order-by [:w.created_at]}))]
    (doseq [{:keys [email token]} rows]
      (println (str email "," (magic-link token))))
    (count rows)))
