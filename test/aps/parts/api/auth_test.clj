(ns aps.parts.api.auth-test
  (:require
   [aps.parts.api.auth :as auth]
   [aps.parts.entity.user :as user]
   [aps.parts.helpers.test-factory :as factory]
   [aps.parts.helpers.utils :refer [with-test-db]]
   [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :once with-test-db)

(deftest login-test
  (testing "login with correct credentials establishes the auth session"
    (let [user-data                (factory/build-test-user)
          {:keys [email password]} user-data
          created                  (user/create! user-data)
          response                 (auth/login {:body-params {:email email :password password}})]
      (is (= 200 (:status response)))
      (is (= (:id created) (:id (:body response))) "the body is the authenticated user")
      (is (not (contains? (:body response) :password_hash)))
      (is (= {:sub (str (:id created))} (get-in response [:session :identity]))
          "the response carries the auth session identity")))

  (testing "login with an incorrect password is rejected"
    (let [user-data       (factory/build-test-user)
          {:keys [email]} user-data]
      (user/create! user-data)
      (let [response (auth/login {:body-params {:email email :password "wrongpassword"}})]
        (is (= 401 (:status response)))
        (is (= {:error "Invalid credentials"} (:body response)))
        (is (not (contains? response :session))
            "a failed login establishes no session"))))

  (testing "login for a non-existent user is rejected"
    (let [response (auth/login {:body-params {:email "nobody@example.com" :password "whatever"}})]
      (is (= 401 (:status response)))
      (is (= {:error "Invalid credentials"} (:body response))))))

(deftest logout-test
  (testing "logout clears the auth session"
    (let [response (auth/logout {:session {:identity {:sub "user-1"}}})]
      (is (= 200 (:status response)))
      (is (= {:message "Logged out successfully"} (:body response)))
      (is (contains? response :session))
      (is (nil? (:session response))
          "the response sets :session nil, which drops the cookie"))))
