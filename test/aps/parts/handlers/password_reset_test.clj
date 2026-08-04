(ns aps.parts.handlers.password-reset-test
  (:require
   [aps.parts.auth :as auth]
   [aps.parts.auth.session-store :as session-store]
   [aps.parts.config :as conf]
   [aps.parts.db :as db]
   [aps.parts.handlers.password-reset :as reset]
   [aps.parts.helpers.utils :refer [create-test-user! with-test-db]]
   [aps.parts.mail :as mail]
   [aps.parts.middleware :as middleware]
   [aps.parts.password-resets :as resets]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.middleware.session.store :as store]))

(use-fixtures :once with-test-db)

(def ^:private GET-form (middleware/wrap-html-response reset/request-form))
(def ^:private POST-request (middleware/wrap-html-response reset/request-submit))
(def ^:private GET-token (middleware/wrap-html-response reset/show))
(def ^:private POST-token (middleware/wrap-html-response reset/redeem))

(defn- run-sync!
  "Synchronous stand-in for reset/run-async! with the same
   swallow-and-continue semantics, so tests see deterministic effects."
  [f]
  (try (f) (catch Exception _)))

(defn- with-captured-mail
  "Run `f` with mail/send! stubbed and async dispatch made synchronous;
   returns [result sent-messages]."
  [f]
  (let [sent (atom [])]
    (with-redefs [reset/run-async! run-sync!
                  mail/send!       (fn [msg] (swap! sent conj msg) msg)]
      [(f) @sent])))

(defn- auth-session-count [user-id]
  (:count (db/query-one
           (db/sql-format {:select [[[:count :*] :count]]
                           :from   [:auth_sessions]
                           :where  [:= :user_id (db/->uuid user-id)]}))))

(defn- fake-auth-session!
  "Write a real auth session for `user-id` through the session store, so
   the revocation assertion exercises the row shape production creates."
  [user-id]
  (store/write-session (session-store/db-store db/datasource 3600) nil
                       {:identity {:sub (str user-id)}}))

(deftest request-form-test
  (testing "GET /reset-password renders the request form"
    (let [response (GET-form {})]
      (is (= 200 (:status response)))
      (is (str/includes? (:body response) "reset link")))))

(deftest request-submit-privacy-test
  (testing "the response is byte-identical whether or not the email has an account"
    (create-test-user! {:email "reset-req@example.com"})
    (let [[known sent-known]
          (with-captured-mail
            #(POST-request {:form-params {"email" "reset-req@example.com"}}))
          [unknown sent-unknown]
          (with-captured-mail
            #(POST-request {:form-params {"email" "stranger@example.com"}}))]
      (is (= 200 (:status known) (:status unknown)))
      (is (= (:body known) (:body unknown))
          "account existence must not be inferable from the response")
      (is (= 1 (count sent-known)) "the account holder is emailed a link")
      (is (str/includes? (:body (first sent-known)) "/reset/")
          "the email carries the magic link")
      (is (= "reset-req@example.com" (:to (first sent-known))))
      (is (empty? sent-unknown) "no email goes to an address without an account"))))

(deftest request-submit-system-sender-test
  (testing "the reset email travels the system-sender path (fallback
            behaviour is mail_test's concern)"
    (create-test-user! {:email "reset-sender@example.com"})
    (let [[_ [sent]]
          (with-redefs [conf/mail-system-from (constantly "Parts <help@ifs.tools>")]
            (with-captured-mail
              #(POST-request {:form-params {"email" "reset-sender@example.com"}})))]
      (is (= "Parts <help@ifs.tools>" (:from sent))))))

(deftest request-submit-email-cap-test
  (testing "reset emails per account are capped; the response stays uniform
            and the existing link stays valid (idempotent token)"
    (create-test-user! {:email "reset-flood@example.com"})
    (let [[responses sent]
          (with-captured-mail
            #(vec (repeatedly 4 (fn []
                                  (POST-request
                                   {:form-params {"email" "reset-flood@example.com"}})))))]
      (is (= 3 (count sent)) "the burst allows three sends, then caps")
      (is (apply = (map :body responses))
          "capped requests render the same page as sent ones")
      (is (apply = (map :body sent))
          "every email delivered carries the same still-valid link"))))

(deftest request-submit-send-failure-test
  (testing "a failed send is not revealed to the requester"
    (create-test-user! {:email "reset-fail@example.com"})
    (let [[ok _]   (with-captured-mail
                     #(POST-request {:form-params {"email" "reset-fail@example.com"}}))
          ok-body  (:body ok)
          response (with-redefs [reset/run-async! run-sync!
                                 mail/send!
                                 (fn [_] (throw (ex-info "relay down" {:type :smtp-error})))]
                     (POST-request {:form-params {"email" "reset-fail@example.com"}}))]
      (is (= 200 (:status response)))
      (is (= ok-body (:body response))))))

(deftest show-test
  (testing "GET with a valid token renders the new-password form"
    (create-test-user! {:email "reset-show@example.com"})
    (let [{:keys [token]} (resets/create-reset! "reset-show@example.com")
          response        (GET-token {:path-params {:token token}})]
      (is (= 200 (:status response)))
      (is (str/includes? (:body response) "new password"))))

  (testing "GET with an unknown token renders the calm 404 page"
    (let [response (GET-token {:path-params {:token "no-such-token"}})]
      (is (= 404 (:status response)))
      (is (str/includes? (:body response) "no longer valid")))))

(deftest redeem-happy-path-test
  (testing "POST sets the new password, revokes sessions, signs the user in"
    (let [{:keys [id]}    (create-test-user! {:email                 "reset-ok@example.com"
                                              :password              "old-password-1"
                                              :password_confirmation "old-password-1"})
          _               (fake-auth-session! id)
          {:keys [token]} (resets/create-reset! "reset-ok@example.com")
          response        (POST-token {:path-params {:token token}
                                       :form-params {"password"              "brand-new-pass-9"
                                                     "password_confirmation" "brand-new-pass-9"}})]
      (is (= 303 (:status response)))
      (is (= "/app" (get-in response [:headers "Location"])))
      (is (= {:sub (str id)} (get-in response [:session :identity]))
          "the user is signed in via a fresh auth session")
      (is (some? (auth/authenticate {:email    "reset-ok@example.com"
                                     :password "brand-new-pass-9"}))
          "the new password logs in")
      (is (nil? (auth/authenticate {:email    "reset-ok@example.com"
                                    :password "old-password-1"}))
          "the old password no longer works")
      (is (zero? (auth-session-count id)) "prior sessions are revoked")
      (is (nil? (resets/find-active token)) "the token is spent"))))

(defn- attempt-redeem!
  "Create a user for `email` (password old-password-1), mint a reset, and
   POST `form` against it. Returns [response token] for the assertions."
  [email form]
  (create-test-user! {:email                 email
                      :password              "old-password-1"
                      :password_confirmation "old-password-1"})
  (let [{:keys [token]} (resets/create-reset! email)]
    [(POST-token {:path-params {:token token} :form-params form}) token]))

(defn- password-unchanged? [email]
  (some? (auth/authenticate {:email email :password "old-password-1"})))

(deftest redeem-validation-failure-test
  (testing "POST with mismatched passwords re-renders; token stays usable"
    (let [[response token] (attempt-redeem! "reset-mismatch@example.com"
                                            {"password"              "brand-new-pass-9"
                                             "password_confirmation" "different"})]
      (is (= 400 (:status response)))
      (is (str/includes? (:body response) "new password") "the form is re-rendered")
      (is (some? (resets/find-active token)) "the token is still usable")
      (is (password-unchanged? "reset-mismatch@example.com"))))

  (testing "POST with a too-short password re-renders; token stays usable"
    (let [[response token] (attempt-redeem! "reset-short@example.com"
                                            {"password"              "short"
                                             "password_confirmation" "short"})]
      (is (= 400 (:status response)))
      (is (some? (resets/find-active token)) "the token is still usable")))

  (testing "POST with the password fields missing entirely re-renders; token stays usable"
    (let [[response token] (attempt-redeem! "reset-nofields@example.com" {})]
      (is (= 400 (:status response)) "a stripped form must not 500")
      (is (some? (resets/find-active token)) "the token is still usable")
      (is (password-unchanged? "reset-nofields@example.com")))))

(deftest redeem-spent-token-test
  (testing "POST with an already-used token renders the 404 page"
    (create-test-user! {:email "reset-twice@example.com" :password "old-password-1" :password_confirmation "old-password-1"})
    (let [{:keys [token]} (resets/create-reset! "reset-twice@example.com")
          form            {"password"              "brand-new-pass-9"
                           "password_confirmation" "brand-new-pass-9"}
          _               (POST-token {:path-params {:token token} :form-params form})
          response        (POST-token {:path-params {:token token} :form-params form})]
      (is (= 404 (:status response)))
      (is (str/includes? (:body response) "no longer valid")))))
