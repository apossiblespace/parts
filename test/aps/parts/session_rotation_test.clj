(ns aps.parts.session-rotation-test
  "Each flow that signs a User in must give the browser a new session id.
   Without it, an attacker can put a known session cookie in a browser.
   When the User signs in there, the attacker is signed in too. The tests
   drive the full middleware stack with real cookies."
  (:require
   [aps.parts.api.account :as account]
   [aps.parts.auth.session-store :as session-store]
   [aps.parts.db :as db]
   [aps.parts.helpers.test-factory :as factory]
   [aps.parts.helpers.utils :refer [create-test-user! with-test-db]]
   [aps.parts.password-notice :as password-notice]
   [aps.parts.password-resets :as resets]
   [aps.parts.server :as server]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [muuntaja.core :as muuntaja]
   [ring.middleware.session.store :as store]
   [ring.mock.request :as mock]))

(defn- without-emails [f]
  (with-redefs-fn {#'account/send-welcome! (constantly nil)
                   #'password-notice/send! (constantly nil)}
    f))

(use-fixtures :once with-test-db)
(use-fixtures :each without-emails)

(defn- browse
  "Sends `request` to the app with the session cookie `cookie`. Returns
   `[response cookie]`, where `cookie` is the new one when the response
   sets it."
  [app request cookie]
  (let [response   (app (cond-> request cookie (mock/header "cookie" cookie)))
        set-cookie (get-in response [:headers "Set-Cookie"])]
    [response (or (some #(re-find #"parts-session=[^;]+" %)
                        (if (string? set-cookie) [set-cookie] set-cookie))
                  cookie)]))

(defn- csrf-token
  "Returns the anti-forgery token in `body`, from the app shell's meta tag
   or from a form's hidden field."
  [body]
  (or (some->> (re-find #"<meta[^>]*name=\"csrf-token\"[^>]*>" body)
               (re-find #"content=\"([^\"]+)\"")
               second)
      (some->> (re-find #"<input[^>]*name=\"__anti-forgery-token\"[^>]*>" body)
               (re-find #"value=\"([^\"]+)\"")
               second)))

(defn- anonymous-session
  "Loads the app shell like a new visitor. Returns `[cookie token]`."
  [app]
  (let [[response cookie] (browse app (mock/request :get "/app/login") nil)]
    [cookie (csrf-token (:body response))]))

(defn- api
  "An API request with a Transit body, as the SPA sends it."
  [method path token body]
  (-> (mock/request method path)
      (mock/body (slurp (muuntaja/encode "application/transit+json" body)))
      (mock/content-type "application/transit+json")
      (mock/header "accept" "application/transit+json")
      (mock/header "x-csrf-token" token)))

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
