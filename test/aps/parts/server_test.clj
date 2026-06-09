(ns aps.parts.server-test
  (:require
   [aps.parts.server :as server]
   [clojure.test :refer [deftest is testing]]
   [ring.mock.request :as mock]))

(deftest head-requests-behave-like-get-test
  (testing "HEAD on a GET-only route returns 200 (reitit alone would 405) with no body"
    (let [app (server/app)]
      (let [response (app (mock/request :head "/up"))]
        (is (= 200 (:status response)) "HEAD /up is 200, not 405")
        (is (nil? (:body response)) "HEAD response carries no body"))
      (testing "GET still works as the baseline"
        (is (= 200 (:status (app (mock/request :get "/up")))))))))
