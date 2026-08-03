(ns aps.parts.entity.user
  (:require
   [aps.parts.auth :as auth]
   [aps.parts.billing :as billing]
   [aps.parts.common.models.user :as model]
   [aps.parts.common.utils :refer [normalize-email]]
   [aps.parts.db :as db]
   [aps.parts.db.erasure :as erasure]
   [clojure.spec.alpha :as s]
   [com.brunobonacci.mulog :as mulog]))

(def allowed-update-fields #{:email :display_name :password})
(def creatable-fields
  "Fields a `create!` caller may set. Anything else in the attrs map — a
   privilege/billing column like `:is_founding_circle` or `:paid_through_date`,
   or any other column — is dropped before insert, so a caller that spreads an
   untrusted request body cannot mass-assign. `:is_founding_circle` is allowed
   here because the invite path legitimately sets it from the trusted
   invitation row; the registration boundary (`api/account`) is what keeps it
   out of a request body."
  #{:email :display_name :password :password_confirmation :role :is_founding_circle})
(def sensitive-fields #{:password_hash})
(def valid-roles #{"client" "therapist"})

(defn- normalize-attrs
  "Canonicalize fields that have a stable storage form (e.g. emails are
   case-insensitive; we store the lowercased+trimmed form). Runs before
   validation so the spec sees what will actually be persisted."
  [attrs]
  (cond-> attrs
    (:email attrs) (update :email normalize-email)))

(defn- validate-password-confirmation
  "Check that a supplied password matches its confirmation. `:password_confirmation`
  is a transient input field — never a column — so this runs on the raw attrs,
  before `sanitize-attrs` drops it. (Doing it inside `validate-attrs` would
  compare against an already-stripped confirmation and reject every update.)"
  [attrs]
  (let [{:keys [password password_confirmation]} attrs]
    (when (and password (not= password password_confirmation))
      (throw (ex-info "Password and confirmation do not match" {:type :validation}))))
  attrs)

(defn- validate-attrs
  "Perform validations to ensure the user attributes are ready to be persisted"
  [attrs]
  (when (empty? attrs)
    (throw (ex-info "Nothing to update" {:type :validation})))
  (when-let [role (:role attrs)]
    (when-not (contains? valid-roles role)
      (throw (ex-info "Invalid role" {:type :validation}))))
  (when-let [password (:password attrs)]
    (when-not (s/valid? ::model/password password)
      (throw (ex-info (str "Password must be between "
                           model/password-min-length
                           " and "
                           model/password-max-length
                           " characters")
                      {:type :validation}))))
  attrs)

(defn- sanitize-attrs
  "Ensure we are not trying to save attributes that cannot be updated.
  Nil values are dropped too: every updatable column is NOT NULL, so a
  present-but-nil key (a JSON null, a missing form field) can only ever
  be noise — kept, it would reach the database as an impossible update."
  [attrs]
  (into {} (filter (comp some? val)) (select-keys attrs allowed-update-fields)))

(defn- set-password-hash
  "Prepare a user record to be persisted by replacing the password attribute
  with a password hash. Always strips the password keys — users has no
  password column."
  [attrs]
  (let [password (:password attrs)]
    (cond-> (dissoc attrs :password :password_confirmation)
      password (assoc :password_hash (auth/hash-password password)))))

(defn- remove-sensitive-data
  "Ensure we are not echoing back sensitive informatin (eg password hash)"
  [attrs]
  (apply dissoc attrs sensitive-fields))

(defn fetch
  "Retrieve a user record from the database, including their map_id"
  [id]
  (if-let [user (db/query-one
                 (db/sql-format
                  {:select    [[:u.id :id]
                               [:u.email :email]
                               [:u.display_name :display_name]
                               [:u.role :role]
                               [:u.paid_through_date :paid_through_date]
                               [:m.id :map_id]]
                   :from      [[:users :u]]
                   :left-join [[:maps :m] [:= :m.owner_id :u.id]]
                   :where     [:= :u.id (db/->uuid id)]}))]
    (remove-sensitive-data user)
    (throw (ex-info "User not found" {:type :not-found :id id}))))

(defn update!
  "Update a user record with provided attributes.
   Accepts an optional datasource-or-transaction to participate in a surrounding tx."
  ([id attrs] (update! id attrs db/datasource))
  ([id attrs tx]
   (when (not id) (throw (ex-info "Missing User ID" {:type :validation})))
   (let [sanitized-attrs (-> attrs
                             validate-password-confirmation
                             sanitize-attrs
                             normalize-attrs
                             validate-attrs
                             set-password-hash)]
     (remove-sensitive-data
      (first (db/update! :users sanitized-attrs [:= :id (db/->uuid id)] tx))))))

(defn create!
  "Create a new user record with the provided attributes.
   Accepts an optional datasource-or-transaction to participate in a surrounding tx."
  ([attrs] (create! attrs db/datasource))
  ([attrs tx]
   (let [validated-attrs (-> attrs
                             validate-password-confirmation
                             (select-keys creatable-fields)
                             normalize-attrs
                             validate-attrs
                             set-password-hash)]
     (remove-sensitive-data
      (db/insert! :users validated-attrs tx)))))

(defn delete!
  "Hard-delete a user and all associated data, including past maps, parts,
   and relationships. Audit-log entries referencing this user are
   pseudonymized to the tombstone UUID rather than deleted, preserving the
   operational trail.

   This is the right-to-erasure path; the deletion-purge job funnels
   expired soft-deletes through here too, so the release-Stripe-then-purge
   ordering has one owner. For the user-initiated 30-day soft-delete flow,
   see `aps.parts.db.erasure/request-deletion!`."
  [id]
  (let [uuid-id (db/->uuid id)
        user    (db/query-one (db/sql-format
                               {:select [:id] :from [:users] :where [:= :id uuid-id]}))]
    (if user
      (do
        (billing/release-stripe-customer! uuid-id)
        (erasure/purge-account! db/datasource uuid-id)
        (mulog/log ::delete-user-complete :user-id id :success true)
        {:id id :deleted true})
      (do
        (mulog/log ::delete-user-not-found :user-id id)
        {:id id :deleted false}))))
