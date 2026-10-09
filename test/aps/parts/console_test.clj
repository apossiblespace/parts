(ns aps.parts.console-test
  (:require
   [aps.parts.console :as console]
   [aps.parts.helpers.utils :refer [create-test-user! with-test-db]]
   [aps.parts.server :as server]
   [aps.parts.stats :as stats]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :as mock]))

(use-fixtures :each with-test-db)

(defn- with-host [request host]
  (assoc-in request [:headers "host"] host))

(deftest test-host-allowlist
  (let [app (console/handler)]
    (testing "serves the page to a local host on any port"
      (doseq [host ["localhost" "localhost:9100" "127.0.0.1:3100" "[::1]:9100"]]
        (is (= 200 (:status (app (with-host (mock/request :get "/") host)))) host)))
    (testing "refuses any other host with 403"
      (doseq [host ["evil.example" "evil.example:9100" "localhost.evil.example" ""]]
        (is (= 403 (:status (app (with-host (mock/request :get "/") host)))) host)))
    (testing "serves static files"
      (is (= 200 (:status (app (mock/request :get "/css/flow.css"))))))
    (testing "refuses a foreign host for static files too"
      (is (= 403 (:status (app (with-host (mock/request :get "/css/style.css")
                                 "evil.example"))))))))

(deftest test-post-requires-anti-forgery-token
  (let [app (console/handler)]
    (testing "rejects a post without a token"
      (is (= 403 (:status (app (mock/request :post "/"))))))))

(deftest test-routes-are-separate
  (let [console-app (console/handler)
        public-app  (server/app)]
    (testing "does not serve public app routes on the console"
      (doseq [path ["/up" "/app" "/api/maps" "/reset-password"]]
        (is (= 404 (:status (console-app (mock/request :get path)))) path)))
    (testing "does not serve the console on the public app"
      (is (not (str/includes? (str (:body (public-app (mock/request :get "/"))))
                              "Operator console"))))))

(deftest test-errors-are-plain-text
  (with-redefs [stats/fleet (fn [_] (throw (ex-info "<b>boom</b>" {})))]
    (let [response ((console/handler) (mock/request :get "/"))]
      (testing "answers a failure with 500 in plain text, not html"
        (is (= 500 (:status response)))
        (is (str/starts-with? (get-in response [:headers "Content-Type"]) "text/plain"))
        (is (string? (:body response)))))))

(deftest test-stats-page
  (create-test-user! {:email "jane@example.com" :display_name "Jane Example"})
  (let [body (:body ((console/handler) (mock/request :get "/")))]
    (testing "lists the users with their email"
      (is (str/includes? body "jane@example.com")))))
