(ns tools.ifs.parts.api.auth-test
  (:require
   [buddy.sign.jwt :as jwt]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [tools.ifs.helpers.test-factory :as factory]
   [tools.ifs.helpers.test-helpers :refer [with-test-db]]
   [tools.ifs.parts.api.account :as account]
   [tools.ifs.parts.api.auth :as auth]
   [tools.ifs.parts.auth :as auth-utils]
   [tools.ifs.parts.entity.user :as user])
  (:import
   (java.time Instant)))

(use-fixtures :once with-test-db)

(deftest test-create-token
  (testing "create-token generates a valid JWT"
    (let [user-id 1
          token (auth-utils/create-token user-id)
          secret auth-utils/secret
          decoded (jwt/unsign token secret)
          now-seconds (.getEpochSecond (Instant/now))]
      (is (= user-id (:sub decoded)))
      (is (> (:exp decoded) now-seconds))
      (is (< (:exp decoded) (+ now-seconds 3601))))))

(deftest test-hash-password
  (testing "hash-password creates a valid hash"
    (let [password "secret123"
          hash (auth-utils/hash-password password)]
      (is (not= password hash))
      (is (auth-utils/check-password password hash)))))

(deftest test-check-password
  (testing "check-password validates correct password"
    (let [password "correct-password"
          hash (auth-utils/hash-password password)]
      (is (auth-utils/check-password password hash))))

  (testing "check-password rejects incorrect password"
    (let [password "correct-password"
          hash (auth-utils/hash-password password)]
      (is (not (auth-utils/check-password "wrong-password" hash))))))

(deftest test-authenticate
  (testing "authenticate succeeds with correct credentials"
    (let [user-data (factory/create-test-user)
          {:keys [email password]} user-data]
      (user/create! user-data)
      (let [token (auth-utils/authenticate {:email email :password password})]
        (is (string? token))
        (is (jwt/unsign token auth-utils/secret)))))

  (testing "authenticate fails with incorrect password"
    (let [user-data (factory/create-test-user)
          {:keys [email]} user-data]
      (user/create! user-data)
      (is (nil? (auth-utils/authenticate {:email email :password "wrongpassword"})))))

  (testing "authenticate fails with non-existent user"
    (is (nil? (auth-utils/authenticate {:email "nonexistent@example.com" :password "anypassword"})))))

(deftest test-login
  (testing "login succeeds with correct credentials"
    (let [user-data (factory/create-test-user)
          {:keys [email password]} user-data
          mock-request {:body user-data}]
      (account/register-account mock-request)
      (let [response (auth/login {:body {:email email :password password}})]
        (is (= 200 (:status response)))
        (is (:token (:body response)))
        (is (jwt/unsign (:token (:body response)) auth-utils/secret)))))

  (testing "login fails with incorrect password"
    (let [user-data (factory/create-test-user)
          {:keys [email]} user-data
          mock-request {:body user-data}]
      (account/register-account mock-request)
      (let [response (auth/login {:body {:email email :password "wrongpassword"}})]
        (is (= 401 (:status response)))
        (is (= {:error "Invalid credentials"} (:body response))))))

  (testing "login fails with non-existent user"
    (let [response (auth/login {:body {:email "nonexistent@example.com" :password "anypassword"}})]
      (is (= 401 (:status response)))
      (is (= {:error "Invalid credentials"} (:body response))))))

(deftest test-logout
  (testing "logout always returns success message"
    (let [response (auth/logout {})]
      (is (= 200 (:status response)))
      (is (= {:message "Logged out successfully"} (:body response))))))

(deftest test-get-user-id-from-token
  (testing "get-user-id-from-token extracts user-id from request"
    (let [request {:identity {:user-id 1}}
          user-id (auth-utils/get-user-id-from-token request)]
      (is (= 1 user-id))))

  (testing "get-user-id-from-token returns nil for unauthenticated request"
    (let [request {}
          user-id (auth-utils/get-user-id-from-token request)]
      (is (nil? user-id)))))
