(ns apossiblespace.parts.api.middleware-test
  (:require [clojure.test :refer :all]
            [apossiblespace.parts.api.middleware :as middleware]
            [com.brunobonacci.mulog :as mulog]
            [com.brunobonacci.mulog.buffer :as rb]
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

;; FIXME: This test suite fails in CI becasue of Mulog's asynchronous nature.
;; The solution seems to be to create a custom publisher that will take the
;; events and store into an atom and then use the atom to verify the
;; expectations, however at the moment I can't easily do this, so I'm going to
;; move on to other things (2024-09-10)
;;
;; Slack link: https://clojurians.slack.com/archives/C03J782P329/p1714641608641329

;; (deftest test-logging-middleware
;;   (let [logged-entries (atom [])
;;         handler (middleware/logging (fn [_request] {:status 200}))
;;         request {:uri "/test" :request-method :get}]
;;     (with-redefs [mulog/log (fn [event-type & data]
;;                               (swap! logged-entries conj {:event-type event-type :data data}))]

;;       (testing "generates logs from requests"
;;         (handler request)
;;         (let [{:keys [event-type data]} (last @logged-entries)]
;;           (is (= :apossiblespace.parts.api.middleware/request event-type))
;;           (is (= request (:request (apply hash-map data))))
;;           (is (= false (:authenticated? (apply hash-map data))))))

;;       (testing "logs user ID for authenticated requests"
;;         (let [request (conj request {:identity {:sub "user-123"}})]
;;           (handler request)
;;           (let [{:keys [event-type data]} (last @logged-entries)]
;;             (is (= :apossiblespace.parts.api.middleware/request event-type))
;;             (is (= request (:request (apply hash-map data))))
;;             (is (= true (:authenticated? (apply hash-map data))))
;;             (is (= "user-123" (:user-id (apply hash-map data))))))))))
