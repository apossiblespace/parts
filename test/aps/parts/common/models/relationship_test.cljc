(ns aps.parts.common.models.relationship-test
  (:require
   #?(:clj [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer-macros [deftest is testing]])
   [aps.parts.common.constants :as constants]
   [aps.parts.common.models.relationship :as relationship]))

(deftest relationship-vocabulary-test
  (testing "The vocabulary is pinned — exactly these eight types, nothing else"
    (is (= #{"unknown" "protects" "polarizes-with" "works-with"
             "activates" "carries-burden" "fearful-of" "suppresses"}
           constants/relationship-types)))

  (testing "Display order, labels, and colours cover exactly the vocabulary"
    (is (= (set constants/relationship-type-order)
           (set (keys constants/relationship-labels))
           (set (keys constants/relationship-colors))))))

(deftest make-relationship-test
  (testing "Creates a valid relationship with minimal attributes"
    (let [map-id    "test-map-id"
          source-id "source-123"
          target-id "target-456"
          result    (relationship/make-relationship {:map_id    map-id
                                                     :source_id source-id
                                                     :target_id target-id})]
      #?(:cljs (is (string? (:id result)))
         :clj (is (nil? (:id result))))
      (is (= map-id (:map_id result)))
      (is (= "unknown" (:type result)))
      (is (= source-id (:source_id result)))
      (is (= target-id (:target_id result)))
      (is (nil? (:notes result)))
      (is (= 0 (:intensity result)) "intensity defaults to calm")))

  (testing "Creates a relationship with provided attributes"
    (let [attrs  {:id        "custom-id"
                  :map_id    "map-123"
                  :type      "protects"
                  :source_id "source-123"
                  :target_id "target-456"
                  :notes     "Test notes"
                  :intensity 62}
          result (relationship/make-relationship attrs)]
      (is (= attrs result))))

  (testing "Throws for an out-of-range intensity"
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo
            :cljs cljs.core.ExceptionInfo) #"Validation failed"
         (relationship/make-relationship {:map_id    "test"
                                          :source_id "source"
                                          :target_id "target"
                                          :intensity 101}))))

  (testing "Throws exception for invalid relationship type"
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo
            :cljs cljs.core.ExceptionInfo) #"Validation failed"
         (relationship/make-relationship {:map_id    "test"
                                          :source_id "source"
                                          :target_id "target"
                                          :type      "invalid-type"}))))

  (testing "Rejects the retired blended type"
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo
            :cljs cljs.core.ExceptionInfo) #"Validation failed"
         (relationship/make-relationship {:map_id    "test"
                                          :source_id "source"
                                          :target_id "target"
                                          :type      "blended"}))))

  (testing "Throws exception for missing required attributes"
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo
            :cljs cljs.core.ExceptionInfo) #"Validation failed"
         (relationship/make-relationship {})))))

(deftest validate-update-test
  (testing "Accepts a partial map of mutable attributes"
    (is (nil? (relationship/validate-update {:notes "New note"}))))

  (testing "Rejects :id and :map_id — a Relationship's identity can't be updated"
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo
            :cljs cljs.core.ExceptionInfo) #"Validation failed"
         (relationship/validate-update {:notes "x" :id "sneaky"})))
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo
            :cljs cljs.core.ExceptionInfo) #"Validation failed"
         (relationship/validate-update {:notes "x" :map_id "sneaky"})))))

(deftest can-connect?-test
  (let [a   "part-a"
        b   "part-b"
        c   "part-c"
        rel (fn [s t type]
              {:source_id s :target_id t :type type})]

    (testing "Empty relationships always permit a new connection"
      (is (relationship/can-connect? [] a b)))

    (testing "Different source/target permitted"
      (is (relationship/can-connect? [(rel a b "unknown")] a c)))

    (testing "Reverse direction permitted — A->B + B->A is the motivating case"
      (is (relationship/can-connect? [(rel a b "protects")] b a))
      (is (relationship/can-connect? [(rel a b "unknown")]  b a)))

    (testing "Self-loops blocked — a Part cannot relate to itself, and a
              degenerate self-edge has no drawable curve"
      (is (not (relationship/can-connect? [] a a)))
      (is (not (relationship/can-connect? [(rel a b "unknown")] a a))))

    (testing "Same source/target blocked regardless of existing type"
      (is (not (relationship/can-connect? [(rel a b "unknown")]        a b)))
      (is (not (relationship/can-connect? [(rel a b "protects")]       a b)))
      (is (not (relationship/can-connect? [(rel a b "carries-burden")] a b))))))
