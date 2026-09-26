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

(deftest test-wrap-static
  (let [app     (middleware/wrap-static (fn [_] {:status 200 :body "app"}))
        get-req (fn [uri & [qs headers]]
                  {:request-method :get :uri uri :query-string qs :headers (or headers {})})]
    (testing "a versioned URL may be cached for a year"
      (is (= "public, max-age=31536000, immutable"
             (get-in (app (get-req "/marketing.js" "v=0123abcd")) [:headers "Cache-Control"]))))

    (testing "an unversioned file must be revalidated each time"
      (is (= "no-cache"
             (get-in (app (get-req "/marketing.js")) [:headers "Cache-Control"]))))

    (testing "an unchanged file answers a conditional request with 304"
      (let [last-modified (get-in (app (get-req "/marketing.js")) [:headers "Last-Modified"])]
        (is (some? last-modified))
        (is (= 304 (:status (app (get-req "/marketing.js" nil {"if-modified-since" last-modified})))))))

    (testing "requests that are not files reach the app"
      (is (= "app" (:body (app (get-req "/app/maps"))))))))
