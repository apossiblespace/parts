(ns aps.parts.errors-test
  (:require
   [aps.parts.errors :as errors]
   [clojure.test :refer [deftest is testing]]
   [reitit.ring :as ring]
   [ring.mock.request :as mock])
  (:import
   (org.postgresql.util PSQLException PSQLState)))

(defn- create-app [handler]
  (ring/ring-handler
   (ring/router
    [["/test" {:handler handler}]]
    {:data {:middleware [errors/exception]}})))

(deftest exception-middleware-test
  (testing "passes through successful responses"
    (let [app      (create-app (fn [_] {:status 200 :body "OK"}))
          request  (mock/request :get "/test")
          response (app request)]
      (is (= 200 (:status response)))
      (is (= "OK" (:body response)))))

  (testing "handles validation errors"
    (let [app      (create-app (fn [_] (throw (ex-info "Validation failed" {:type :validation}))))
          request  (mock/request :get "/test")
          response (app request)]
      (is (= 400 (:status response)))
      (is (= {:error "Validation failed"} (:body response)))))

  (testing "handles not found errors"
    (let [app      (create-app (fn [_] (throw (ex-info "User not found" {:type :not-found}))))
          request  (mock/request :get "/test")
          response (app request)]
      (is (= 404 (:status response)))
      (is (= {:error "User not found"} (:body response)))))

  (testing "handles PostgreSQL unique violation (23505)"
    (let [exception (PSQLException. "duplicate key value" PSQLState/UNIQUE_VIOLATION)
          app       (create-app (fn [_] (throw exception)))
          request   (mock/request :get "/test")
          response  (app request)]
      (is (= 409 (:status response)))
      (is (= {:error "A resource with this unique identifier already exists"} (:body response)))))

  (testing "handles PostgreSQL check constraint violation (23514)"
    (let [exception (PSQLException. "check constraint failed" PSQLState/CHECK_VIOLATION)
          app       (create-app (fn [_] (throw exception)))
          request   (mock/request :get "/test")
          response  (app request)]
      (is (= 409 (:status response)))
      (is (= {:error "The provided data does not meet the required constraints"} (:body response)))))

  (testing "handles PostgreSQL not null violation (23502)"
    (let [exception (PSQLException. "null value" PSQLState/NOT_NULL_VIOLATION)
          app       (create-app (fn [_] (throw exception)))
          request   (mock/request :get "/test")
          response  (app request)]
      (is (= 409 (:status response)))
      (is (= {:error "A required field was missing"} (:body response)))))

  (testing "handles PostgreSQL foreign key violation (23503)"
    (let [exception (PSQLException. "foreign key violation" PSQLState/FOREIGN_KEY_VIOLATION)
          app       (create-app (fn [_] (throw exception)))
          request   (mock/request :get "/test")
          response  (app request)]
      (is (= 409 (:status response)))
      (is (= {:error "The referenced resource does not exist"} (:body response))))))
