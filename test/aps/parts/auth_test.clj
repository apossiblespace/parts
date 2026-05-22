(ns aps.parts.auth-test
  (:require
   [aps.parts.auth :as auth]
   [aps.parts.entity.user :as user]
   [aps.parts.helpers.test-factory :as factory]
   [aps.parts.helpers.utils :refer [with-test-db]]
   [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :once with-test-db)

(deftest hash-password-test
  (testing "hash-password produces a verifiable, non-plaintext hash"
    (let [password "secret123"
          hash     (auth/hash-password password)]
      (is (not= password hash))
      (is (auth/check-password password hash)))))

(deftest check-password-test
  (testing "check-password accepts the correct password and rejects others"
    (let [hash (auth/hash-password "correct-password")]
      (is (auth/check-password "correct-password" hash))
      (is (not (auth/check-password "wrong-password" hash))))))

(deftest authenticate-test
  (testing "authenticate returns the user (without password_hash) on correct credentials"
    (let [user-data                (factory/build-test-user)
          {:keys [email password]} user-data
          created                  (user/create! user-data)
          result                   (auth/authenticate {:email email :password password})]
      (is (= (:id created) (:id result)))
      (is (= email (:email result)))
      (is (not (contains? result :password_hash)))))

  (testing "authenticate fails with an incorrect password"
    (let [user-data       (factory/build-test-user)
          {:keys [email]} user-data]
      (user/create! user-data)
      (is (nil? (auth/authenticate {:email email :password "wrongpassword"})))))

  (testing "authenticate fails for a non-existent user"
    (is (nil? (auth/authenticate {:email "nonexistent@example.com" :password "anypassword"})))))

(deftest session-identity-test
  (testing "session-identity wraps the user id as a stringified :sub claim"
    (is (= {:sub "abc-123"} (auth/session-identity "abc-123")))
    (is (= {:sub "7"} (auth/session-identity 7)))))

(deftest establish-session-test
  (testing "attaches the identity to the response's session"
    (let [response (auth/establish-session {:status 200} {} "user-7")]
      (is (= {:identity {:sub "user-7"}} (:session response)))))

  (testing "merges into the request's existing session — existing entries survive"
    (let [request  {:session {:existing "kept"}}
          response (auth/establish-session {:status 200} request "user-7")]
      (is (= "kept" (get-in response [:session :existing]))
          "a bare {:identity ...} would drop ring's anti-forgery token")
      (is (= {:sub "user-7"} (get-in response [:session :identity]))))))

(deftest clear-session-test
  (testing "drops the session and expires the cookie immediately"
    (let [response (auth/clear-session {:status 200})]
      (is (nil? (:session response)))
      (is (= {:max-age 0} (:session-cookie-attrs response))))))

(deftest current-user-id-test
  (testing "reads the subject from the request identity"
    (is (= "user-9" (auth/current-user-id {:identity {:sub "user-9"}}))))

  (testing "returns nil for an unauthenticated request"
    (is (nil? (auth/current-user-id {})))))
