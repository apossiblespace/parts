(ns parts.routes-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [muuntaja.core :as m]
   [parts.api.auth :as auth]
   [parts.helpers.utils :refer [with-test-db register-test-user]]
   [parts.helpers.test-factory :as factory]
   [parts.entity.user :as user]
   [parts.routes :as routes]
   [reitit.ring :as ring]
   [ring.mock.request :as mock]
   [cognitect.transit :as transit])
  (:import
   [java.io ByteArrayInputStream ByteArrayOutputStream]))

(use-fixtures :once with-test-db)

(defn- parse-transit [body]
  (let [in (ByteArrayInputStream. (.getBytes body))
        reader (transit/reader in :json)]
    (transit/read reader)))

(defn- create-app
  "Creates a test app with all middleware configured as in production"
  []
  (ring/ring-handler
   (ring/router routes/routes)))

(deftest test-login-handler
  (testing "login handler with middleware stack"
    (let [app (create-app)
          user-data (factory/create-test-user)
          {:keys [email password]} user-data]

      ;; First create the user
      (user/create! user-data)

      ;; Create login request with transit format
      (let [request (-> (mock/request :post "/api/auth/login")
                        (mock/json-body {:email email :password password})
                        (mock/header "Content-Type" "application/transit+json")
                        (mock/header "Accept" "application/transit+json"))
            response (app request)
            body (parse-transit (:body response))]

        ;; Test response status, headers and content
        (is (= 200 (:status response)))
        (is (= "application/transit+json; charset=utf-8"
               (get-in response [:headers "Content-Type"])))
        (is (:access_token body))
        (is (:refresh_token body))
        (is (= "Bearer" (:token_type body)))))))

(deftest test-unauthorized-access
  (testing "protected endpoints require authentication"
    (let [app (create-app)
          request (-> (mock/request :get "/api/account")
                      (mock/header "Accept" "application/transit+json"))
          response (app request)
          body (parse-transit (:body response))]

      (is (= 401 (:status response)))
      (is (= "application/transit+json; charset=utf-8"
             (get-in response [:headers "Content-Type"])))
      (is (= "Unauthorized" (:error body))))))

(deftest test-static-resource-content-type
  (testing "SVG files are served with correct content type"
    (let [app (create-app)
          request (mock/request :get "/images/parts-logo-horizontal.svg")
          response (app request)]

      (is (= 200 (:status response)))
      (is (= "image/svg+xml"
             (get-in response [:headers "Content-Type"]))))))
