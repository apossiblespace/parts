(ns aps.parts.errors-test
  (:require
   [aps.parts.errors :as errors]
   [clojure.string :as str]
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

  (testing "handles Stripe failures with the fixed wording, never the raw message"
    (let [app      (create-app (fn [_] (throw (ex-info "Stripe API error"
                                                       {:type :stripe-api :status 403}))))
          response (app (mock/request :get "/test"))]
      (is (= 502 (:status response)))
      (is (= {:error "The payment provider rejected the request"} (:body response))))
    (let [app      (create-app (fn [_] (throw (ex-info "Stripe request failed"
                                                       {:type :stripe-transport}))))
          response (app (mock/request :get "/test"))]
      (is (= 502 (:status response)))
      (is (= {:error "The payment provider could not be reached"} (:body response)))))

  (testing "handles PostgreSQL unique violation (23505)"
    (let [exception (PSQLException. "duplicate key value" PSQLState/UNIQUE_VIOLATION)
          app       (create-app (fn [_] (throw exception)))
          request   (mock/request :get "/test")
          response  (app request)]
      (is (= 409 (:status response)))
      ;; Opaque on purpose: naming the cause would let client-chosen ids
      ;; probe for existence.
      (is (= {:error "The change conflicts with existing data"} (:body response)))))

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

(deftest redact-change-test
  (testing "keeps structural identifiers, drops clinical :data"
    (is (= {:entity :part :type :update :id "p1"}
           (errors/redact-change
            {:entity :part
             :type   :update
             :id     "p1"
             :data   {:label "Anxious part" :notes "client said X" :body_location "chest"}}))))
  (testing "is nil-safe"
    (is (nil? (errors/redact-change nil)))))

(deftest safe-error-fields-test
  (testing "non-postgres exception → class name only"
    (is (= {:error-class "clojure.lang.ExceptionInfo"}
           (dissoc (errors/safe-error-fields (ex-info "boom" {})) :stack))))
  (testing "PSQLException → class + sql-state, never the value-bearing message"
    (let [fields (errors/safe-error-fields
                  (PSQLException. "Detail: Key (notes)=(SECRET) already exists"
                                  PSQLState/CHECK_VIOLATION))]
      (is (= {:error-class "PSQLException" :sql-state "23514"} (dissoc fields :stack))
          "only schema metadata — no message, no offending value")
      (is (not (str/includes? (pr-str fields) "SECRET")))))
  (testing "is nil-safe"
    (is (nil? (errors/safe-error-fields nil)))))

(defn- frame [class-name]
  (StackTraceElement. class-name "invoke" "x.clj" 1))

(deftest safe-error-fields-stack-test
  (let [cause (doto (RuntimeException. "CAUSE SECRET")
                (.setStackTrace (into-array [(frame "aps.parts.db$q")])))
        top   (doto (ex-info "TOP SECRET" {:notes "DATA SECRET"} cause)
                (.setStackTrace (into-array (concat (repeat 30 (frame "clojure.lang.AFn"))
                                                    [(frame "aps.parts.api.maps$get_map")]))))
        stack (:stack (errors/safe-error-fields top))]
    (testing "has the class of each throwable in the cause chain"
      (is (= ["clojure.lang.ExceptionInfo" "java.lang.RuntimeException"]
             (map :class stack))))
    (testing "keeps the top frames and each app frame below the limit"
      (is (= 26 (count (:frames (first stack)))))
      (is (str/starts-with? (last (:frames (first stack))) "aps.parts.api.maps$get_map")))
    (testing "has no exception message and no ex-data"
      (is (not (str/includes? (pr-str stack) "SECRET"))))))

(deftest safe-error-fields-oom-test
  (testing "keeps the message of an OutOfMemoryError"
    (is (= "Java heap space"
           (:oom-message (errors/safe-error-fields (OutOfMemoryError. "Java heap space"))))))
  (testing "has no message for other throwables"
    (is (not (contains? (errors/safe-error-fields (RuntimeException. "SECRET"))
                        :oom-message)))))

(deftest route-label-test
  (testing "is the method and the route template, not the URI"
    (is (= "GET /api/maps/:id"
           (#'errors/route-label {:request-method    :get
                                  :uri               "/api/maps/123"
                                  :reitit.core/match {:template "/api/maps/:id"}}))))
  (testing "is nil when no route matched"
    (is (nil? (#'errors/route-label {:request-method :get :uri "/x"})))))
