(ns aps.parts.handlers.invite
  "Server-rendered Founding Circle invite redemption — GET/POST
   /invite/:token.

   Top-level on purpose: a Circle member redeeming an invite must not
   depend on the SPA bundle loading first. Gated by token validity, not by
   the launch flag — there is no `wrap-launch-gated` on this path."
  (:require
   [aps.parts.api.account :as account]
   [aps.parts.auth :as auth]
   [aps.parts.db :as db]
   [aps.parts.invitations :as invitations]
   [aps.parts.views.layouts :as layouts]
   [aps.parts.views.partials :as partials]
   [com.brunobonacci.mulog :as mulog]
   [hiccup2.core :refer [html]]
   [ring.util.response :as response])
  (:import
   (org.postgresql.util PSQLException)))

(def ^:private page-styles ["/css/style.css"])

(defn- signup-page
  "Full HTML document for the invite signup form. `opts` is passed straight
   to `partials/invite-signup-content` (:token, :email, :error, :values)."
  [opts]
  (html (layouts/fullscreen {:title "Join the Founding Circle" :styles page-styles}
                            (partials/invite-signup-content opts))))

(defn- unavailable-page
  "Full HTML document for the calm 'invite unavailable' error page."
  []
  (html (layouts/fullscreen {:title "Invite unavailable" :styles page-styles}
                            (partials/invite-unavailable-content))))

(defn show
  "GET /invite/:token — the signup page for a valid token, or a calm error
   page (404) for an unknown / redeemed / revoked one. The 404 is identical
   for all three failure modes — it does not reveal which one occurred."
  [request]
  (let [token      (get-in request [:path-params :token])
        invitation (invitations/find-active token)]
    (if invitation
      (response/response (signup-page {:token token :email (:email invitation)}))
      (-> (response/response (unavailable-page))
          (response/status 404)))))

(defn- conflict-message
  "User-facing message for a DB constraint violation during provisioning.
   23505 (unique violation) is almost always a username another invitee
   already took; anything else gets a generic message."
  [^PSQLException e]
  (if (= "23505" (.getSQLState e)) ; 23505 = unique_violation
    "That username is already taken — please choose another."
    "We couldn’t create your account — please check your details and try again."))

(defn redeem
  "POST /invite/:token — claim the token and provision the account in one
   transaction, establish the auth session, and redirect into the app.

   The transaction is the atomicity boundary: `claim!`'s conditional
   UPDATE and `provision-account!` commit or roll back together. So a
   failed signup leaves the token usable, and a token already spent by a
   concurrent request creates nothing."
  [request]
  (let [token      (get-in request [:path-params :token])
        form       (:form-params request)
        invitation (invitations/find-active token)]
    (if-not invitation
      (-> (response/response (unavailable-page))
          (response/status 404))
      (try
        (let [{:keys [account]}
              (db/with-transaction
                (fn [tx]
                  (let [claimed (invitations/claim! token tx)]
                    (when-not claimed
                      (throw (ex-info "Invitation already redeemed"
                                      {:type :invitation-spent})))
                    (account/provision-account!
                     {:email                 (:email claimed)
                      :username              (get form "username")
                      :display_name          (get form "display_name")
                      :password              (get form "password")
                      :password_confirmation (get form "password_confirmation")
                      :role                  "therapist"
                      :is_founding_circle    (:is_founding_circle claimed)}
                     tx))))]
          (mulog/log ::invitation-redeemed :email (:email invitation))
          ;; 303 See Other — POST-redirect-GET. Establish the auth session
          ;; (ADR-0007) so the new member lands in /app already signed in.
          (-> (response/redirect "/app")
              (response/status 303)
              (assoc :session (assoc (:session request)
                                     :identity (auth/session-identity (:id account))))))

        (catch clojure.lang.ExceptionInfo e
          (case (:type (ex-data e))
            :invitation-spent
            (-> (response/response (unavailable-page))
                (response/status 404))

            :validation
            (-> (response/response
                 (signup-page {:token  token
                               :email  (:email invitation)
                               :error  (.getMessage e)
                               :values form}))
                (response/status 400))

            (throw e)))

        (catch PSQLException e
          (mulog/log ::invitation-redeem-sql-error
                     :email (:email invitation) :sql-state (.getSQLState e))
          (-> (response/response
               (signup-page {:token  token
                             :email  (:email invitation)
                             :error  (conflict-message e)
                             :values form}))
              (response/status 400)))))))
