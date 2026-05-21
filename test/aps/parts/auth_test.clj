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
