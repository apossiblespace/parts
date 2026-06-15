(ns aps.parts.server-test
  (:require
   [aps.parts.middleware :as middleware]
   [aps.parts.server :as server]
   [clojure.string :as str]
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

(defn- csp [app path]
  (get-in (app (mock/request :get path)) [:headers "Content-Security-Policy"]))

(deftest content-security-policy-scoping-test
  (let [app (server/app)]
    (testing "authed surfaces carry a CSP with the core directives"
      (doseq [path ["/app" "/app/maps/whatever" "/invite/bogus-token"]]
        (let [v (csp app path)]
          (is (some? v) path)
          (is (str/includes? v "script-src 'self'") path)
          (is (str/includes? v "frame-ancestors 'none'") path))))
    (testing "marketing pages are deliberately excluded (they load Plausible)"
      (is (nil? (csp app "/")) "no CSP on the marketing home"))))

(deftest content-security-policy-prod-is-strict-test
  (testing "prod permits no eval; non-prod allows it for shadow-cljs dev loading"
    (is (= "script-src 'self'; frame-ancestors 'none'"
           (#'middleware/content-security-policy true)))
    (is (str/includes? (#'middleware/content-security-policy false) "'unsafe-eval'"))))
