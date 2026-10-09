(ns aps.parts.server-test
  (:require
   [aps.parts.config :as conf]
   [aps.parts.middleware :as middleware]
   [aps.parts.server :as server]
   [clojure.spec.alpha :as s]
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

(deftest requests-do-not-grow-the-spec-registry-test
  (testing "repeated requests leave the global spec registry the same size"
    (let [app    (server/app)
          _      (app (mock/request :get "/up"))
          before (count (s/registry))]
      (dotimes [_ 3] (app (mock/request :get "/up")))
      (is (= before (count (s/registry)))))))

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
    (testing "public pages carry the Plausible-allowlisting CSP, no inline
              script (analytics is wired via /js/marketing.js data
              attributes)"
      (doseq [path ["/" "/playground" "/privacy" "/terms" "/dpa"]]
        (let [v (csp app path)]
          (is (some? v) path)
          (is (str/includes? v "script-src 'self' https://plausible.io") path)
          (is (not (str/includes? v "unsafe-inline")) path)
          (is (str/includes? v "frame-ancestors 'none'") path))))))

(deftest content-security-policy-prod-is-strict-test
  (testing "prod permits no eval; non-prod allows it for shadow-cljs dev loading"
    (is (= "script-src 'self'; frame-ancestors 'none'"
           (#'middleware/content-security-policy true)))
    (is (str/includes? (#'middleware/content-security-policy false) "'unsafe-eval'"))
    (is (= "script-src 'self' https://plausible.io; frame-ancestors 'none'"
           (#'middleware/public-content-security-policy true)))))

(deftest test-console-socket-closes-when-chmod-fails
  (let [dir  (java.nio.file.Files/createTempDirectory
              "console" (make-array java.nio.file.attribute.FileAttribute 0))
        path (str dir "/console.sock")]
    (with-redefs [conf/console-socket (constantly path)
                  server/owner-only!  (fn [_] (throw (ex-info "chmod failed" {})))]
      (testing "returns nil and leaves no socket that accepts connections"
        (is (nil? (server/start-console)))
        (is (thrown? java.io.IOException
                     (with-open [_ (java.nio.channels.SocketChannel/open
                                    (java.net.UnixDomainSocketAddress/of ^String path))])))))))
