(ns aps.parts.api.auth-test
  (:require
   [aps.parts.api.account :as account]
   [aps.parts.api.auth :as auth]
   [aps.parts.auth :as auth-utils]
   [aps.parts.entity.user :as user]
   [aps.parts.helpers.test-factory :as factory]
   [aps.parts.helpers.utils :refer [with-test-db]]
   [buddy.sign.jwt :as jwt]
   [clojure.test :refer [deftest is testing use-fixtures]])
  (:import
   (java.time Instant)))

(use-fixtures :once with-test-db)

(deftest test-create-access-token
  (testing "create-access-token generates a valid JWT"
    (let [user-id     1
          token       (auth-utils/create-access-token user-id)
          secret      auth-utils/secret
          decoded     (jwt/unsign token secret)
          now-seconds (.getEpochSecond (Instant/now))]
      (is (= (str user-id) (:sub decoded)))
      (is (= "access" (:type decoded)))
      (is (> (:exp decoded) now-seconds))
      (is (< (:exp decoded) (+ now-seconds 901))))))

(deftest test-hash-password
  (testing "hash-password creates a valid hash"
    (let [password "secret123"
          hash     (auth-utils/hash-password password)]
      (is (not= password hash))
      (is (auth-utils/check-password password hash)))))

(deftest test-check-password
  (testing "check-password validates correct password"
    (let [password "correct-password"
          hash     (auth-utils/hash-password password)]
      (is (auth-utils/check-password password hash))))

  (testing "check-password rejects incorrect password"
    (let [password "correct-password"
          hash     (auth-utils/hash-password password)]
      (is (not (auth-utils/check-password "wrong-password" hash))))))

(deftest test-authenticate
  (testing "authenticate succeeds with correct credentials"
    (let [user-data                (factory/build-test-user)
          {:keys [email password]} user-data]
      (user/create! user-data)
      (let [tokens (auth-utils/authenticate {:email email :password password})]
        (is (map? tokens))
        (is (:access_token tokens))
        (is (:refresh_token tokens))
        (is (= "Bearer" (:token_type tokens)))
        (let [access-decoded  (jwt/unsign (:access_token tokens) auth-utils/secret)
              refresh-decoded (jwt/unsign (:refresh_token tokens) auth-utils/secret)]
          (is (= "access" (:type access-decoded)))
          (is (= "refresh" (:type refresh-decoded)))))))

  (testing "authenticate fails with incorrect password"
    (let [user-data       (factory/build-test-user)
          {:keys [email]} user-data]
      (user/create! user-data)
      (is (nil? (auth-utils/authenticate {:email email :password "wrongpassword"})))))

  (testing "authenticate fails with non-existent user"
    (is (nil? (auth-utils/authenticate {:email "nonexistent@example.com" :password "anypassword"})))))

(deftest test-login
  (testing "login succeeds with correct credentials"
    (let [user-data                (factory/build-test-user)
          {:keys [email password]} user-data
          mock-request             {:body-params user-data}]
      (account/register-account mock-request)
      (let [response (auth/login {:body-params {:email email :password password}})]
        (is (= 200 (:status response)))
        (is (:access_token (:body response)))
        (is (:refresh_token (:body response)))
        (is (= "Bearer" (:token_type (:body response))))
        (is (jwt/unsign (:access_token (:body response)) auth-utils/secret))
        (is (jwt/unsign (:refresh_token (:body response)) auth-utils/secret)))))

  (testing "login fails with incorrect password"
    (let [user-data       (factory/build-test-user)
          {:keys [email]} user-data
          mock-request    {:body-params user-data}]
      (account/register-account mock-request)
      (let [response (auth/login {:body-params {:email email :password "wrongpassword"}})]
        (is (= 401 (:status response)))
        (is (= {:error "Invalid credentials"} (:body response))))))

  (testing "login fails with non-existent user"
    (let [response (auth/login {:body-params {:email "nonexistent@example.com" :password "anypassword"}})]
      (is (= 401 (:status response)))
      (is (= {:error "Invalid credentials"} (:body response))))))

(deftest test-refresh
  (testing "refresh token endpoint generates new tokens with valid refresh token"
    (let [user-data                (factory/build-test-user)
          {:keys [email password]} user-data]
      (user/create! user-data)
      (let [tokens        (auth-utils/authenticate {:email email :password password})
            refresh-token (:refresh_token tokens)
            response      (auth/refresh {:body-params {:refresh_token refresh-token}})]
        (is (= 200 (:status response)))
        (is (:access_token (:body response)))
        (is (:refresh_token (:body response)))
        (is (not= refresh-token (:refresh_token (:body response))))
        (is (= "Bearer" (:token_type (:body response)))))))

  (testing "refresh token endpoint fails with invalid token"
    (let [response (auth/refresh {:body-params {:refresh_token "invalid-token"}})]
      (is (= 401 (:status response)))
      (is (= {:error "Invalid refresh token"} (:body response))))))

(deftest test-logout
  (testing "logout invalidates refresh token when provided"
    (let [user-data                (factory/build-test-user)
          {:keys [email password]} user-data]
      (user/create! user-data)
      (let [tokens        (auth-utils/authenticate {:email email :password password})
            refresh-token (:refresh_token tokens)
            response      (auth/logout {:body-params {:refresh_token refresh-token}})]
        (is (= 200 (:status response)))
        (is (= {:message "Logged out successfully"} (:body response)))

        ;; Verify the token no longer works
        (is (= 401 (:status (auth/refresh {:body {:refresh_token refresh-token}})))))))

  (testing "logout succeeds even without refresh token"
    (let [response (auth/logout {})]
      (is (= 200 (:status response)))
      (is (= {:message "Logged out successfully"} (:body response))))))

(deftest test-get-user-id-from-token
  (testing "get-user-id-from-token extracts user-id from request"
    (let [request {:identity {:sub 1}}
          user-id (auth-utils/get-user-id-from-token request)]
      (is (= 1 user-id))))

  (testing "get-user-id-from-token returns nil for unauthenticated request"
    (let [request {}
          user-id (auth-utils/get-user-id-from-token request)]
      (is (nil? user-id)))))
