(ns aps.parts.common.models.map-test
  (:require
   #?(:clj [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer-macros [deftest is testing]])
   [aps.parts.common.models.map :as model]))

(deftest make-map-test
  (testing "Creates a valid map with minimal attributes"
    (let [owner-id "test-owner-id"
          result   (model/make-map {:owner_id owner-id})]
      #?(:cljs (is (string? (:id result)))
         :clj (is (nil? (:id result))))
      (is (= owner-id (:owner_id result)))
      (is (= "Untitled Map" (:title result)))))

  (testing "Creates a map with provided attributes"
    (let [attrs  {:id                "custom-id"
                  :owner_id          "user-123"
                  :title             "A Test Map"
                  :viewport_settings "{:zoom_level 3}"}
          result (model/make-map attrs)]
      (is (= attrs result))))

  (testing "Throws an exception for missing owner ID"
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo
            :cljs cljs.core.ExceptionInfo) #"Validation failed"
         (model/make-map {})))))
