(ns aps.parts.handlers.invite-test
  (:require
   [aps.parts.db :as db]
   [aps.parts.handlers.invite :as invite]
   [aps.parts.helpers.utils :refer [create-test-user! with-test-db]]
   [aps.parts.invitations :as inv]
   [aps.parts.middleware :as middleware]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :once with-test-db)

(def ^:private GET (middleware/wrap-html-response invite/show))
(def ^:private POST (middleware/wrap-html-response invite/redeem))

(defn- user-by-email [email]
  (db/query-one (db/sql-format {:select [:*] :from [:users] :where [:= :email email]})))

(defn- invitation-by-email [email]
  (db/query-one (db/sql-format {:select [:*] :from [:invitations] :where [:= :email email]})))

(defn- valid-form []
  {"display_name"          "Test Practitioner"
   "password"              "supersecret"
   "password_confirmation" "supersecret"
   "accept_medical"        "on"
   "accept_legal"          "on"})

(defn- revoke! [email]
  (binding [*out* (java.io.StringWriter.)]
    (inv/revoke-invitation! email)))

(deftest show-test
  (testing "GET with a valid token renders the signup form, email pre-filled"
    (let [{:keys [token]} (inv/generate-invitation! "show-valid@example.com")
          response        (GET {:path-params {:token token}})]
      (is (= 200 (:status response)))
      (is (str/includes? (:body response) "show-valid@example.com"))
      (is (str/includes? (:body response) "Create my account"))))

  (testing "GET with an unknown token renders the calm 404 error page"
    (let [response (GET {:path-params {:token "no-such-token"}})]
      (is (= 404 (:status response)))
      (is (str/includes? (:body response) "no longer valid"))))

  (testing "GET with a revoked token renders the same 404 error page"
    (let [{:keys [token]} (inv/generate-invitation! "show-revoked@example.com")
          _               (revoke! "show-revoked@example.com")
          response        (GET {:path-params {:token token}})]
      (is (= 404 (:status response)))
      (is (str/includes? (:body response) "no longer valid")))))

(deftest redeem-happy-path-test
  (testing "POST with a valid token and form creates the account and redirects"
    (let [email           "redeem-ok@example.com"
          {:keys [token]} (inv/generate-invitation! email)
          response        (POST {:path-params {:token token}
                                 :form-params (valid-form)})
          user            (user-by-email email)]
      (is (= 303 (:status response)))
      (is (= "/app" (get-in response [:headers "Location"])) "redirects into the app")
      (is (some? user) "the user account was created")
      (is (= {:sub (str (:id user))} (get-in response [:session :identity]))
          "the new member is signed in via the auth session")
      (is (true? (:is_founding_circle user)) "the user is marked Founding Circle")
      (is (nil? (:paid_through_date user)) "paid_through_date is left unset")
      (is (some? (:redeemed_at (invitation-by-email email)))
          "the invitation is marked redeemed"))))

(deftest redeem-validation-failure-test
  (testing "POST with mismatched passwords re-renders the form, token stays usable"
    (let [email           "redeem-bad@example.com"
          {:keys [token]} (inv/generate-invitation! email)
          response        (POST {:path-params {:token token}
                                 :form-params (assoc (valid-form)
                                                     "password_confirmation" "different")})]
      (is (= 400 (:status response)))
      (is (str/includes? (:body response) "Create my account"))
      (is (nil? (user-by-email email)) "no account was created")
      (is (nil? (:redeemed_at (invitation-by-email email)))
          "the invitation is still usable"))))

(deftest redeem-spent-token-test
  (testing "POST with an already-redeemed token renders the 404 error page"
    (let [email           "redeem-twice@example.com"
          {:keys [token]} (inv/generate-invitation! email)
          _               (POST {:path-params {:token token}
                                 :form-params (valid-form)})
          response        (POST {:path-params {:token token}
                                 :form-params (valid-form)})]
      (is (= 404 (:status response)))
      (is (str/includes? (:body response) "no longer valid")))))

(deftest redeem-existing-email-test
  (testing "POST for an email that already has an account re-renders with a friendly error"
    (let [email           "redeem-existing@example.com"
          _               (create-test-user! {:email email})
          {:keys [token]} (inv/generate-invitation! email)
          response        (POST {:path-params {:token token}
                                 :form-params (valid-form)})]
      (is (= 400 (:status response)))
      (is (str/includes? (:body response) "already exists"))
      (is (nil? (:redeemed_at (invitation-by-email email)))
          "the invitation is still usable after the conflict"))))

(deftest redeem-requires-acceptance-test
  (testing "POST without the acceptance checkboxes re-renders the form, creates no account, leaves the token usable"
    (let [email           "redeem-noaccept@example.com"
          {:keys [token]} (inv/generate-invitation! email)
          response        (POST {:path-params {:token token}
                                 :form-params (dissoc (valid-form)
                                                      "accept_medical" "accept_legal")})]
      (is (= 400 (:status response)))
      (is (str/includes? (:body response) "Create my account") "the form is re-rendered")
      (is (nil? (user-by-email email)) "no account was created")
      (is (nil? (:redeemed_at (invitation-by-email email)))
          "the invitation is still usable"))))
