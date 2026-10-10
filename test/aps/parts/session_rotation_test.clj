(ns aps.parts.session-rotation-test
  "Each flow that signs a User in must give the browser a new session id.
   Without it, an attacker can put a known session cookie in a browser.
   When the User signs in there, the attacker is signed in too. The tests
   drive the full middleware stack with real cookies."
  (:require
   [aps.parts.api.account :as account]
   [aps.parts.auth.session-store :as session-store]
   [aps.parts.db :as db]
   [aps.parts.helpers.browser :refer [anonymous-session api browse csrf-token]]
   [aps.parts.helpers.test-factory :as factory]
   [aps.parts.helpers.utils :refer [create-test-user! with-test-db]]
   [aps.parts.password-notice :as password-notice]
   [aps.parts.password-resets :as resets]
   [aps.parts.server :as server]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.middleware.session.store :as store]
   [ring.mock.request :as mock]))

(defn- without-emails [f]
  (with-redefs-fn {#'account/send-welcome! (constantly nil)
                   #'password-notice/send! (constantly nil)}
    f))

(use-fixtures :once with-test-db)
(use-fixtures :each without-emails)

(defn- signed-in? [app cookie]
  (= 200 (:status (first (browse app (mock/request :get "/api/account") cookie)))))

(defn- assert-rotated
  "Checks the session after a sign-in that started from `before`."
  [app before after token]
  (is (some? after))
  (is (not= before after) "the sign-in sets a new session id")
  (is (signed-in? app after) "the new session is signed in")
  (is (not (signed-in? app before)) "the old session id is not signed in")
  (is (= 200 (:status (first (browse app (api :patch "/api/account" token {:display_name "Renamed"})
                                     after))))
      "the anti-forgery token still works after the rotation"))

(deftest test-login-rotates-the-session-id
  (let [app              (server/app)
        pw               "correct horse battery"
        user             (create-test-user! {:password pw :password_confirmation pw})
        [before token]   (anonymous-session app)
        [response after] (browse app (api :post "/api/auth/login" token
                                          {:email (:email user) :password pw})
                                 before)]
    (is (= 200 (:status response)))
    (assert-rotated app before after token)))

(deftest test-registration-rotates-the-session-id
  (let [app              (server/app)
        [before token]   (anonymous-session app)
        [response after] (browse app (api :post "/api/account/register" token
                                          (merge (factory/build-test-user)
                                                 {:accepted-legal? true :accepted-medical? true}))
                                 before)]
    (is (= 201 (:status response)))
    (assert-rotated app before after token)))

(deftest test-password-reset-rotates-the-session-id
  (let [app              (server/app)
        user             (create-test-user! {:password "old-password-1" :password_confirmation "old-password-1"})
        earlier          (str "parts-session="
                              (store/write-session (session-store/db-store db/datasource 3600) nil
                                                   {:identity {:sub (str (:id user))}}))
        {reset :token}   (resets/create-reset! (:email user))
        [form before]    (browse app (mock/request :get (str "/reset/" reset)) nil)
        token            (csrf-token (:body form))
        [response after] (browse app (mock/body (mock/request :post (str "/reset/" reset))
                                                {"__anti-forgery-token"  token
                                                 "password"              "brand-new-pass-9"
                                                 "password_confirmation" "brand-new-pass-9"})
                                 before)]
    (is (= 303 (:status response)))
    (assert-rotated app before after token)
    (testing "every earlier session of the User is logged out"
      (is (not (signed-in? app earlier))))))
