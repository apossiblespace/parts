(ns aps.parts.auth.middleware-test
  (:require
   [aps.parts.auth.middleware :as auth-mw]
   [aps.parts.entity.map :as parts-map]
   [clojure.test :refer [deftest is testing]]))

(deftest test-require-auth-middleware
  (testing "require-auth middleware allows authenticated requests"
    (let [handler  (auth-mw/require-auth (fn [_] {:status 200 :body "Success"}))
          request  {:identity {:user-id 1}}
          response (handler request)]
      (is (= 200 (:status response)))
      (is (= "Success" (:body response)))))

  (testing "require-auth middleware blocks unauthenticated requests"
    (let [handler  (auth-mw/require-auth (fn [_] {:status 200 :body "Success"}))
          request  {}
          response (handler request)]
      (is (= 401 (:status response)))
      (is (= {:error "Unauthorized"} (:body response))))))

(deftest test-wrap-map-access
  (let [owner-uuid   (random-uuid)
        map-uuid     (random-uuid)
        identity-row {:id map-uuid :owner_id owner-uuid}
        ok-handler   (fn [_] {:status 200 :body "reached handler"})
        request-for  (fn [user-id]
                       {:identity   {:sub (str user-id)}
                        :parameters {:path {:id (str map-uuid)}}})]

    (testing "the owner reaches the handler"
      (with-redefs [parts-map/fetch-identity (fn [_] identity-row)]
        (let [handler  (auth-mw/wrap-map-access ok-handler)
              response (handler (request-for owner-uuid))]
          (is (= 200 (:status response))))))

    (testing "a non-owner is rejected and never reaches the handler"
      (with-redefs [parts-map/fetch-identity (fn [_] identity-row)]
        (let [handler (auth-mw/wrap-map-access ok-handler)]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not found"
                                (handler (request-for (random-uuid))))))))

    (testing "a missing map is rejected"
      (with-redefs [parts-map/fetch-identity (fn [_] nil)]
        (let [handler (auth-mw/wrap-map-access ok-handler)]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not found"
                                (handler (request-for owner-uuid)))))))

    (testing "rejections carry :type :not-found — missing and not-owned are indistinguishable (ADR-0006)"
      (with-redefs [parts-map/fetch-identity (fn [_] nil)]
        (let [handler (auth-mw/wrap-map-access ok-handler)]
          (try
            (handler (request-for owner-uuid))
            (is false "expected wrap-map-access to throw")
            (catch clojure.lang.ExceptionInfo e
              (is (= :not-found (:type (ex-data e)))))))))))
