(ns aps.parts.handlers.pages-test
  (:require
   [aps.parts.common.constants :as c]
   [aps.parts.handlers.pages :as pages]
   [clojure.test :refer [deftest is testing]]))

(defn- render-home
  "Render / for an anonymous visitor and return the HTML as a string."
  []
  (str (:body (pages/home-page {}))))

(deftest home-redirects-logged-in
  (testing "a logged-in user hitting / is redirected into the app"
    (let [response (pages/home-page {:identity {:sub "user-1"}})]
      (is (= 302 (:status response)))
      (is (= "/app" (get-in response [:headers "Location"]))))))

(deftest home-pricing-section
  (let [body (render-home)]
    (testing "the homepage carries the pricing section"
      (is (re-find #"id=\"pricing\"" body)))
    (testing "every plan's price and cadence render from the shared constant"
      (doseq [{:keys [title price cadence]} c/subscription-plans]
        (is (.contains body title))
        (is (.contains body price))
        (is (.contains body cadence))))
    (testing "the VAT-inclusive note is present"
      (is (re-find #"include VAT" body)))
    (testing "the footer links to the pricing section"
      (is (re-find #"href=\"/#pricing\"" body)))))
