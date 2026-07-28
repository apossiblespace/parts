(ns aps.parts.frontend.device-test
  (:require
   [aps.parts.frontend.device :as device]
   [cljs.test :refer-macros [deftest is testing]]))

(deftest phone?-test
  (testing "a coarse pointer on a small screen is a phone — portrait or
            landscape, the smallest side is what's measured, so rotating
            can never reclassify"
    (is (true? (device/phone? true 390))
        "iPhone portrait (390×844)")
    (is (true? (device/phone? true 390))
        "iPhone landscape is the same smallest side"))

  (testing "an iPad is coarse but not small — its smallest side (768)
            clears the threshold and keeps the full editing canvas"
    (is (false? (device/phone? true 768))))

  (testing "a narrow desktop window has a fine pointer — never a phone,
            however small it's dragged"
    (is (false? (device/phone? false 390))))

  (testing "the threshold is exclusive at 600"
    (is (true? (device/phone? true 599)))
    (is (false? (device/phone? true 600)))))
