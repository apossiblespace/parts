(ns aps.parts.common.models.part-test
  (:require
   #?(:clj [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer-macros [deftest is testing]])
   [aps.parts.common.models.part :as part]))

(deftest make-part-test
  (testing "Creates a valid part with minimal attributes"
    (let [system-id "test-system-id"
          result    (part/make-part {:system_id system-id})]
      #?(:cljs (is (string? (:id result)))
         :clj (is (nil? (:id result))))
      (is (= system-id (:system_id result)))
      (is (= "unknown" (:type result)))
      (is (= "Unknown" (:label result)))
      (is (= 0 (:position_x result)))
      (is (= 0 (:position_y result)))
      (is (nil? (:notes result)))))

  (testing "Creates a part with a custom label but no custom type"
    (let [attrs  {:system_id "system-123"
                  :label     "Custom label"}
          result (part/make-part attrs)]
      (is (= (:type result) "unknown"))
      (is (= (:label result) "Custom label"))))

  (testing "Creates a part with a custom type but no custom label"
    (let [attrs  {:system_id "system-123"
                  :type      "firefighter"}
          result (part/make-part attrs)]
      (is (= (:type result) "firefighter"))
      (is (= (:label result) "Firefighter"))))

  (testing "Creates a part with provided attributes"
    (let [attrs  {:id            "custom-id"
                  :system_id     "system-123"
                  :type          "manager"
                  :label         "Test Manager"
                  :position_x    100
                  :position_y    200
                  :description   "Test description"
                  :width         150
                  :height        80
                  :body_location "head"
                  :notes         "Test notes"}
          result (part/make-part attrs)]
      (is (= attrs result))))

  (testing "Throws exception for invalid part type"
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo
            :cljs cljs.core.ExceptionInfo) #"Validation failed"
         (part/make-part {:system_id "test" :type "invalid-type"}))))

  (testing "Throws exception for missing required attributes"
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo
            :cljs cljs.core.ExceptionInfo) #"Validation failed"
         (part/make-part {})))))

(deftest validate-update-test
  (testing "Accepts a partial map of mutable attributes"
    (is (nil? (part/validate-update {:label "New label"}))))

  (testing "Rejects :id and :system_id — a Part's identity can't be updated"
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo
            :cljs cljs.core.ExceptionInfo) #"Validation failed"
         (part/validate-update {:label "x" :id "sneaky"})))
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo
            :cljs cljs.core.ExceptionInfo) #"Validation failed"
         (part/validate-update {:label "x" :system_id "sneaky"})))))
