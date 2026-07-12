(ns aps.parts.api.account
  (:require
   [aps.parts.auth :as auth]
   [aps.parts.billing :as billing]
   [aps.parts.common.demo :as demo]
   [aps.parts.db :as db]
   [aps.parts.entity.map :as parts-map]
   [aps.parts.entity.part :as part]
   [aps.parts.entity.policy-acceptance :as policy-acceptance]
   [aps.parts.entity.relationship :as relationship]
   [aps.parts.entity.session :as session]
   [aps.parts.entity.user :as user]
   [com.brunobonacci.mulog :as mulog]
   [ring.util.response :as response]))

(defn get-account
  "Retrieve own account info, including the account's good-standing summary
   under `:standing` (see `billing/account-standing`) for the Account page."
  [request]
  (let [user-id     (auth/current-user-id request)
        user-record (user/fetch user-id)]
    (-> (response/response (assoc user-record
                                  :standing (billing/account-standing user-record)))
        (response/status 200))))

(defn update-account
  "Update own account info"
  [request]
  (let [user-id      (auth/current-user-id request)
        body         (:body-params request)
        updated-user (user/update! user-id body)]
    (mulog/log ::update-account-success :user-id user-id)
    (-> (response/response updated-user)
        (response/status 200))))

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

(defn provision-account!
  "Creates a user, their default map, seeds it with demo content, and records
   the user's onboarding policy acceptances. All writes share `tx` so they
   commit or roll back as one unit. Returns {:account ... :map-id ...}.

   `params` carries the two acceptance booleans (`:accepted-legal?`,
   `:accepted-medical?`) alongside the user fields; they are validated, then
   stripped before the user is created.

   Public so the invite-redemption handler (`handlers/invite`) can reuse
   the exact same provisioning path as `/api/account/register`."
  [params tx]
  (validate-acceptance! params)
  (let [account (user/create! (dissoc params :accepted-legal? :accepted-medical?) tx)
        title   "Example Map"
        the-map (parts-map/create! {:title title :owner_id (:id account)} (:id account) tx)]
    ;; Session 1 must exist before any content so the seeded demo Parts land
    ;; inside its range — ADR-0014's "derivation is total" invariant.
    (session/create! (:id the-map) (:id account) tx)
    (populate-initial-map! (:id the-map) (:id account) tx)
    (policy-acceptance/record-onboarding! (:id account) tx)
    {:account account :map-id (:id the-map)}))

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
                                 :accepted-legal? :accepted-medical?])
                   (assoc :role "therapist"))]
    (try
      (let [{:keys [account map-id]} (db/with-transaction
                                       #(provision-account! params %))]
        (mulog/log ::register
                   :email (:email account)
                   :map-id map-id
                   :status :success)
        (-> (response/response (merge account {:map_id map-id}))
            (response/status 201)
            (auth/establish-session request (:id account))))
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
