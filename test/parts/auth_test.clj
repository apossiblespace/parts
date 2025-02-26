(ns parts.auth-test
  (:require
   [buddy.sign.jwt :as jwt]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [parts.auth :as auth]
   [parts.entity.user :as user]
   [parts.helpers.test-factory :as factory]
   [parts.helpers.utils :refer [with-test-db]])
  (:import
   (java.time Instant)))

(use-fixtures :once with-test-db)

(deftest test-create-access-token
  (testing "create-access-token generates a valid JWT"
    (let [user-id 1
          token (auth/create-access-token user-id)
          secret auth/secret
          decoded (jwt/unsign token secret)
          now-seconds (.getEpochSecond (Instant/now))]
      (is (= (str user-id) (:sub decoded)))
      (is (= "access" (:type decoded)))
      (is (> (:exp decoded) now-seconds))
      (is (< (:exp decoded) (+ now-seconds 901))))))

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
      (let [tokens (auth/authenticate {:email email :password password})]
        (is (map? tokens))
        (is (:access_token tokens))
        (is (:refresh_token tokens))
        (is (= "Bearer" (:token_type tokens)))
        (let [access-decoded (jwt/unsign (:access_token tokens) auth/secret)
              refresh-decoded (jwt/unsign (:refresh_token tokens) auth/secret)]
          (is (= "access" (:type access-decoded)))
          (is (= "refresh" (:type refresh-decoded)))))))

  (testing "authenticate fails with incorrect password"
    (let [user-data (factory/create-test-user)
          {:keys [email]} user-data]
      (user/create! user-data)
      (is (nil? (auth/authenticate {:email email :password "wrongpassword"})))))

  (testing "authenticate fails with non-existent user"
    (is (nil? (auth/authenticate {:email "nonexistent@example.com" :password "anypassword"})))))

(deftest test-get-user-id-from-token
  (testing "get-user-id-from-token extracts user-id from request"
    (let [request {:identity {:sub 1}}
          user-id (auth/get-user-id-from-token request)]
      (is (= 1 user-id))))

  (testing "get-user-id-from-token returns nil for unauthenticated request"
    (let [request {}
          user-id (auth/get-user-id-from-token request)]
      (is (nil? user-id)))))

(deftest test-refresh-auth-tokens
  (testing "refresh-auth-tokens generates new tokens with valid refresh token"
    (let [user-data (factory/create-test-user)
          {:keys [email password]} user-data]
      (user/create! user-data)
      (let [tokens (auth/authenticate {:email email :password password})
            refresh-token (:refresh_token tokens)
            new-tokens (auth/refresh-auth-tokens refresh-token)]
        (is (map? new-tokens))
        (is (:access_token new-tokens))
        (is (:refresh_token new-tokens))
        (is (not= refresh-token (:refresh_token new-tokens)))
        (is (= "Bearer" (:token_type new-tokens)))))))

(deftest test-invalidate-refresh-token
  (testing "invalidate-refresh-token invalidates a valid token"
    (let [user-data (factory/create-test-user)
          {:keys [email password]} user-data]
      (user/create! user-data)
      (let [tokens (auth/authenticate {:email email :password password})
            refresh-token (:refresh_token tokens)]
        (is (auth/invalidate-refresh-token refresh-token))
        (is (nil? (auth/refresh-auth-tokens refresh-token)))))))
