(ns aps.parts.handlers.pages-test
  (:require
   [aps.parts.handlers.pages :as pages]
   [clojure.test :refer [deftest is testing]]))

(deftest home-redirects-logged-in
  (testing "a logged-in user hitting / is redirected into the app"
    (let [response (pages/home-page {:identity {:sub "user-1"}})]
      (is (= 302 (:status response)))
      (is (= "/app" (get-in response [:headers "Location"]))))))
