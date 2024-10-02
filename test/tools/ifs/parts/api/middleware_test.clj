(ns tools.ifs.parts.api.middleware-test
  (:require [clojure.test :refer [deftest is testing]]
            [tools.ifs.parts.api.middleware :as middleware]
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
      (is (= {:error "Validation failed"} (:body response)))))

  (testing "handles not found errors"
    (let [app (create-app (fn [_] (throw (ex-info "User not found" {:type :not-found}))))
          request (mock/request :get "/test")
          response (app request)]
      (is (= 404 (:status response)))
      (is (= {:error "User not found"} (:body response)))))

  (doseq [[sqlite-error expected-message] middleware/sqlite-errors]
    (testing (str "handles SQLite constraint violation:" sqlite-error)
      (let [app (create-app (fn [_] (throw (SQLiteException. sqlite-error SQLiteErrorCode/SQLITE_CONSTRAINT))))
            request (mock/request :get "/test")
            response (app request)]
        (is (= 409 (:status response)))
        (is (= {:error expected-message} (:body response)))))))

(deftest test-jwt-auth-middleware
  (testing "jwt-auth middleware allows authenticated requests"
    (let [handler (middleware/jwt-auth (fn [_] {:status 200 :body "Success"}))
          request {:identity {:user-id 1}}
          response (handler request)]
      (is (= 200 (:status response)))
      (is (= "Success" (:body response)))))

  (testing "jwt-auth middleware blocks unauthenticated requests"
    (let [handler (middleware/jwt-auth (fn [_] {:status 200 :body "Success"}))
          request {}
          response (handler request)]
      (is (= 401 (:status response)))
      (is (= {:error "Unauthorized"} (:body response))))))

(deftest test-wrap-html-response
  (testing "sets content-type to HTML and converts body to string"
    (let [handler (fn [_] {:status 200 :body [:div "Hello, World!"]})
          wrapped-handler (middleware/wrap-html-response handler)
          request {}
          response (wrapped-handler request)]
      (is (= 200 (:status response)))
      (is (= "text/html" (get-in response [:headers "Content-Type"])))
      (is (= "[:div \"Hello, World!\"]" (:body response)))))
  (testing "a response that has a content-type set is left as-is"
    (let [handler (fn [_] {:status 200 :body [:div "Hello, JSON!"] :headers {"Content-Type" "application/json"}})
          wrapped-handler (middleware/wrap-html-response handler)
          request {}
          response (wrapped-handler request)]
      (is (= 200 (:status response)))
      (is (= "application/json" (get-in response [:headers "Content-Type"])))
      (is (= [:div "Hello, JSON!"] (:body response))))))

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
