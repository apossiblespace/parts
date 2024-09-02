(ns apossiblespace.auth-test
  (:require  [clojure.test :refer [deftest is testing use-fixtures]]
             [apossiblespace.parts.auth :as auth]
             [apossiblespace.parts.db :as db]
             [buddy.sign.jwt :as jwt]
             [apossiblespace.test-helpers :refer [with-test-db]]
             [apossiblespace.test-factory :as factory]
             [next.jdbc.result-set :as rs])
  (:import [java.time Instant]))

(use-fixtures :each with-test-db)

(deftest test-create-token
  (testing "create-token generates a valid JWT"
    (let [user-id 1
          token (auth/create-token user-id)
          secret auth/secret
          decoded (jwt/unsign token secret)
          now-seconds (.getEpochSecond (Instant/now))]
      (is (= user-id (:user-id decoded)))
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
      (db/insert! :users (auth/prepare-user-record user-data))
      (let [token (auth/authenticate {:email email :password password})]
        (is (string? token))
        (is (jwt/unsign token auth/secret)))))

  (testing "authenticate fails with incorrect password"
    (let [user-data (factory/create-test-user)
          {:keys [email]} user-data]
      (db/insert! :users (auth/prepare-user-record user-data))
      (is (nil? (auth/authenticate {:email email :password "wrongpassword"})))))

  (testing "authenticate fails with non-existent user"
    (is (nil? (auth/authenticate {:email "nonexistent@example.com" :password "anypassword"})))))

(deftest test-register
  (testing "register creates a new user successfully"
    (let [user-data (factory/create-test-user)
          {:keys [email password username display_name role]} user-data
          result (auth/register user-data)]
      (is (= {:success "User registered successfully"} result))
      (let [user (db/query-one (db/sql-format {:select [:*]
                                               :from [:users]
                                               :where [:= :email email]}))]
        (is (= email (:email user)))
        (is (= username (:username user)))
        (is (= display_name (:display_name user)))
        (is (= role (:role user)))
        (is (auth/check-password password (:password_hash user))))))

  (testing "register fails with duplicate email"
    (let [user-data (factory/create-test-user)]
      (auth/register user-data)
      (let [result (auth/register (assoc user-data :username "anotheruser"))]
        (is (= {:error "User with this email or username already exists"} result)))))

  (testing "register fails with duplicate username"
    (let [user-data (factory/create-test-user)
          {:keys [email]} user-data]
      (auth/register user-data)
      (let [result (auth/register (assoc user-data :email (str "dup" email)))]
        (is (= {:error "User with this email or username already exists"} result))))))

(deftest test-login
  (testing "login succeeds with correct credentials"
    (let [user-data (factory/create-test-user)
          {:keys [email password]} user-data]
      (auth/register user-data)
      (let [response (auth/login {:body {:email email :password password}})]
        (is (= 200 (:status response)))
        (is (:token (:body response)))
        (is (jwt/unsign (:token (:body response)) auth/secret)))))

  (testing "login fails with incorrect password"
    (let [user-data (factory/create-test-user)
          {:keys [email]} user-data]
      (auth/register user-data)
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

(deftest test-jwt-auth-middleware
  (testing "jwt-auth middleware allows authenticated requests"
    (let [handler (auth/jwt-auth (fn [_] {:status 200 :body "Success"}))
          request {:identity {:user-id 1}}
          response (handler request)]
      (is (= 200 (:status response)))
      (is (= "Success" (:body response)))))

  (testing "jwt-auth middleware blocks unauthenticated requests"
    (let [handler (auth/jwt-auth (fn [_] {:status 200 :body "Success"}))
          request {}
          response (handler request)]
      (is (= 401 (:status response)))
      (is (= {:error "Unauthorized"} (:body response))))))

(deftest test-get-user-id-from-token
  (testing "get-user-id-from-token extracts user-id from request"
    (let [request {:identity {:user-id 1}}
          user-id (auth/get-user-id-from-token request)]
      (is (= 1 user-id))))

  (testing "get-user-id-from-token returns nil for unauthenticated request"
    (let [request {}
          user-id (auth/get-user-id-from-token request)]
      (is (nil? user-id)))))
