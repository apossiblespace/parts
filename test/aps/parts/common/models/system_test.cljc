(ns aps.parts.common.models.system-test
  (:require
   #?(:clj [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer-macros [deftest is testing]])
   [aps.parts.common.models.system :as system]))

(deftest make-system-test
  (testing "Creates a valid system with minimal attributes"
    (let [owner-id "test-owner-id"
          result   (system/make-system {:owner_id owner-id})]
      (is (string? (:id result)))
      (is (= owner-id (:owner_id result)))
      (is (= "Untitled System" (:title result)))))

  (testing "Creates a system with provided attributes"
    (let [attrs  {:id                "custom-id"
                  :owner_id          "user-123"
                  :title             "A Test System"
                  :viewport_settings "{:zoom_level 3}"}
          result (system/make-system attrs)]
      (is (= attrs result))))

  (testing "Throws an exception for missing owner ID"
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo
            :cljs cljs.core.ExceptionInfo) #"Validation failed"
         (system/make-system {})))))
