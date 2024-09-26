(ns tools.ifs.parts.auth-test
  (:require  [clojure.test :refer [deftest is testing use-fixtures]]
             [tools.ifs.parts.auth :as auth]
             [tools.ifs.parts.entity.user :as user]
             [buddy.sign.jwt :as jwt]
             [tools.ifs.helpers.test-helpers :refer [with-test-db]]
             [tools.ifs.helpers.test-factory :as factory])
  (:import [java.time Instant]))

(use-fixtures :once with-test-db)

(deftest test-create-token
  (testing "create-token generates a valid JWT"
    (let [user-id 1
          token (auth/create-token user-id)
          secret auth/secret
          decoded (jwt/unsign token secret)
          now-seconds (.getEpochSecond (Instant/now))]
      (is (= user-id (:sub decoded)))
      (is (> (:exp decoded) now-seconds))
      (is (< (:exp decoded) (+ now-seconds 3601))))))

(deftest test-hash-password
  (testing "hash-password creates a valid hash"
    (let [password "secret123"
          hash (auth/hash-password password)]
      (is (not= password hash))
      (is (auth/check-password password hash)))))

(deftest test-check-password
  (testing "check-password validates correct password"
    (let [password "correct-password"
          hash (auth/hash-password password)]
      (is (auth/check-password password hash))))

  (testing "check-password rejects incorrect password"
    (let [password "correct-password"
          hash (auth/hash-password password)]
      (is (not (auth/check-password "wrong-password" hash))))))

(deftest test-authenticate
  (testing "authenticate succeeds with correct credentials"
    (let [user-data (factory/create-test-user)
          {:keys [email password]} user-data]
      (user/create! user-data)
      (let [token (auth/authenticate {:email email :password password})]
        (is (string? token))
        (is (jwt/unsign token auth/secret)))))

  (testing "authenticate fails with incorrect password"
    (let [user-data (factory/create-test-user)
          {:keys [email]} user-data]
      (user/create! user-data)
      (is (nil? (auth/authenticate {:email email :password "wrongpassword"})))))

  (testing "authenticate fails with non-existent user"
    (is (nil? (auth/authenticate {:email "nonexistent@example.com" :password "anypassword"})))))

(deftest test-get-user-id-from-token
  (testing "get-user-id-from-token extracts user-id from request"
    (let [request {:identity {:user-id 1}}
          user-id (auth/get-user-id-from-token request)]
      (is (= 1 user-id))))

  (testing "get-user-id-from-token returns nil for unauthenticated request"
    (let [request {}
          user-id (auth/get-user-id-from-token request)]
      (is (nil? user-id)))))
