(ns aps.parts.common.models.relationship-test
  (:require
   #?(:clj [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer-macros [deftest is testing]])
   [aps.parts.common.models.relationship :as relationship]))

(deftest make-relationship-test
  (testing "Creates a valid relationship with minimal attributes"
    (let [system-id "test-system-id"
          source-id "source-123"
          target-id "target-456"
          result    (relationship/make-relationship {:system_id system-id
                                                     :source_id source-id
                                                     :target_id target-id})]
      #?(:cljs (is (string? (:id result)))
         :clj (is (nil? (:id result))))
      (is (= system-id (:system_id result)))
      (is (= "unknown" (:type result)))
      (is (= source-id (:source_id result)))
      (is (= target-id (:target_id result)))
      (is (nil? (:notes result)))))

  (testing "Creates a relationship with provided attributes"
    (let [attrs  {:id        "custom-id"
                  :system_id "system-123"
                  :type      "protective"
                  :source_id "source-123"
                  :target_id "target-456"
                  :notes     "Test notes"}
          result (relationship/make-relationship attrs)]
      (is (= attrs result))))

  (testing "Throws exception for invalid relationship type"
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo
            :cljs cljs.core.ExceptionInfo) #"Validation failed"
         (relationship/make-relationship {:system_id "test"
                                          :source_id "source"
                                          :target_id "target"
                                          :type      "invalid-type"}))))

  (testing "Throws exception for missing required attributes"
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo
            :cljs cljs.core.ExceptionInfo) #"Validation failed"
         (relationship/make-relationship {})))))
