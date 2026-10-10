(ns aps.parts.frontend.components.product-updates-view-test
  (:require
   [aps.parts.frontend.components.product-updates-view :as view]
   [cljs.test :refer [deftest is testing]]))

(def ^:private never-chose {:product_updates_chosen_at nil :product_updates_opted_out_at "t"})

(deftest test-prompt
  (testing "asks a User who never chose"
    (is (= :ask (view/prompt never-chose nil))))
  (testing "does not ask a User who chose, either way"
    (is (nil? (view/prompt {:product_updates_chosen_at "t" :product_updates_opted_out_at nil} nil)))
    (is (nil? (view/prompt {:product_updates_chosen_at "t" :product_updates_opted_out_at "t"} nil))))
  (testing "hides the question as soon as a button is clicked"
    (is (nil? (view/prompt never-chose :no)))
    (is (nil? (view/prompt never-chose :yes)) "no confirmation before the server records it"))
  (testing "confirms Subscribe once the server recorded it"
    (is (= :subscribed (view/prompt {:product_updates_chosen_at "t" :product_updates_opted_out_at nil} :yes))))
  (testing "shows nothing before the User is loaded"
    (is (nil? (view/prompt nil nil)))))
