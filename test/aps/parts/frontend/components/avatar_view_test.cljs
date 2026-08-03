(ns aps.parts.frontend.components.avatar-view-test
  (:require
   [aps.parts.frontend.components.avatar-view :refer [initial-of]]
   [cljs.test :refer-macros [deftest is testing]]))

(deftest test-initial-of
  (testing "plain names give an uppercased first letter"
    (is (= "G" (initial-of "gosha")))
    (is (= "T" (initial-of "Ting-yi"))))

  (testing "astral-plane characters survive whole"
    ;; 🦊 is one code point but two UTF-16 code units — a subs-based
    ;; implementation would return a broken lone surrogate here.
    (is (= "🦊" (initial-of "🦊 Fox")))
    (is (= "𝔊" (initial-of "𝔊othic"))))

  (testing "nil and empty names give nil"
    (is (nil? (initial-of nil)))
    (is (nil? (initial-of "")))))
