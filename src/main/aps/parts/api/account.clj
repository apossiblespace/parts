(ns aps.parts.api.account
  (:require
   [aps.parts.auth :as auth]
   [aps.parts.common.demo :as demo]
   [aps.parts.db :as db]
   [aps.parts.entity.map :as parts-map]
   [aps.parts.entity.part :as part]
   [aps.parts.entity.relationship :as relationship]
   [aps.parts.entity.user :as user]
   [com.brunobonacci.mulog :as mulog]
   [ring.util.response :as response]))

(defn get-account
  "Retrieve own account info"
  [request]
  (let [user-id     (auth/current-user-id request)
        user-record (user/fetch user-id)]
    (-> (response/response user-record)
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

(defn provision-account!
  "Creates a user, their default map, and seeds it with demo content.
   All writes share `tx` so they commit or roll back as one unit.
   Returns {:account ... :map-id ...}.

   Public so the invite-redemption handler (`handlers/invite`) can reuse
   the exact same provisioning path as `/api/account/register`."
  [params tx]
  (let [account (user/create! params tx)
        title   "Example Map"
        the-map (parts-map/create! {:title title :owner_id (:id account)} (:id account) tx)]
    (populate-initial-map! (:id the-map) (:id account) tx)
    {:account account :map-id (:id the-map)}))

(defn register-account
  "Register a new user (role hardcoded to 'therapist'), provision their starter
   map atomically, and establish the auth session for auto-login."
  [request]
  (let [params (-> (:body-params request) (assoc :role "therapist"))]
    (try
      (let [{:keys [account map-id]} (db/with-transaction
                                       #(provision-account! params %))]
        (mulog/log ::register
                   :email (:email account)
                   :username (:username account)
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
                   :username (:username params)
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
      (if (= (:username user) confirm)
        (do
          (user/delete! user-id)
          ;; Drop the caller's auth session — their account no longer exists.
          (auth/clear-session (response/status 204)))
        (throw (ex-info "Confirmation needed" {:type :validation})))
      (do
        (mulog/log ::update-account-not-found :user-id user-id)
        (throw (ex-info "User not found" {:type :not-found}))))))
