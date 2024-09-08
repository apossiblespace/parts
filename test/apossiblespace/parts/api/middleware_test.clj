(ns apossiblespace.parts.api.middleware-test
  (:require [clojure.test :refer :all]
            [apossiblespace.parts.api.middleware :as middleware]
            [ring.mock.request :as mock]
            [reitit.ring :as ring])
  (:import (org.sqlite SQLiteException SQLiteErrorCode)))

(defn create-app [handler]
  (ring/ring-handler
   (ring/router
    [["/test" {:handler handler}]]
    {:data {:middleware [middleware/exception]}})))

(deftest exception-middleware-test
  (testing "passes through successful responses"
    (let [app (create-app (fn [_] {:status 200 :body "OK"}))
          request (mock/request :get "/test")
          response (app request)]
      (is (= 200 (:status response)))
      (is (= "OK" (:body response)))))

  (testing "handles validation errors"
    (let [app (create-app (fn [_] (throw (ex-info "Validation failed" {:type :validation}))))
          request (mock/request :get "/test")
          response (app request)]
      (is (= 400 (:status response)))
      (is (= {:error "Invalid data"} (:body response)))))

  (testing "handles SQLite constraint violation"
    (let [app (create-app (fn [_] (throw (SQLiteException. "UNIQUE constraint failed" SQLiteErrorCode/SQLITE_CONSTRAINT))))
          request (mock/request :get "/test")
          response (app request)]
      (is (= 409 (:status response)))
      (is (= {:error "A resource with this unique identifier already exists"} (:body response))))))
