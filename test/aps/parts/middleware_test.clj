(ns aps.parts.middleware-test
  (:require
   [aps.parts.middleware :as middleware]
   [clojure.test :refer [deftest is testing]]))

(deftest test-wrap-html-response
  (testing "sets content-type to HTML and converts body to string"
    (let [handler         (fn [_] {:status 200 :body [:div "Hello, World!"]})
          wrapped-handler (middleware/wrap-html-response handler)
          request         {}
          response        (wrapped-handler request)]
      (is (= 200 (:status response)))
      (is (= "text/html; charset=utf-8" (get-in response [:headers "Content-Type"])))
      (is (= "[:div \"Hello, World!\"]" (:body response)))))
  (testing "a response that has a content-type set is left as-is"
    (let [handler         (fn [_] {:status 200 :body [:div "Hello, JSON!"] :headers {"Content-Type" "application/json"}})
          wrapped-handler (middleware/wrap-html-response handler)
          request         {}
          response        (wrapped-handler request)]
      (is (= 200 (:status response)))
      (is (= "application/json" (get-in response [:headers "Content-Type"])))
      (is (= [:div "Hello, JSON!"] (:body response))))))
