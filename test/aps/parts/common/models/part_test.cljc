(ns aps.parts.common.models.part-test
  (:require
   #?(:clj [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer-macros [deftest is testing]])
   [aps.parts.common.models.part :as part]))

(deftest make-part-test
  (testing "Creates a valid part with minimal attributes"
    (let [map-id "test-map-id"
          result (part/make-part {:map_id map-id})]
      #?(:cljs (is (string? (:id result)))
         :clj (is (nil? (:id result))))
      (is (= map-id (:map_id result)))
      (is (= "unknown" (:type result)))
      (is (= "Unknown" (:label result)))
      (is (= 0 (:position_x result)))
      (is (= 0 (:position_y result)))
      (is (nil? (:notes result)))))

  (testing "Creates a part with a custom label but no custom type"
    (let [attrs  {:map_id "map-123"
                  :label  "Custom label"}
          result (part/make-part attrs)]
      (is (= (:type result) "unknown"))
      (is (= (:label result) "Custom label"))))

  (testing "Creates a part with a custom type but no custom label"
    (let [attrs  {:map_id "map-123"
                  :type   "firefighter"}
          result (part/make-part attrs)]
      (is (= (:type result) "firefighter"))
      (is (= (:label result) "Firefighter"))))

  (testing "Creates a part with provided attributes"
    (let [attrs  {:id            "custom-id"
                  :map_id        "map-123"
                  :type          "manager"
                  :label         "Test Manager"
                  :position_x    100
                  :position_y    200
                  :description   "Test description"
                  :width         150
                  :height        80
                  :body_location {:view "front" :x 0.42 :y 0.31}
                  :notes         "Test notes"}
          result (part/make-part attrs)]
      (is (= attrs result))))

  (testing "Accepts a structured body location on both views"
    (is (= {:view "back" :x 0.0 :y 1.0}
           (:body_location (part/make-part {:map_id        "m"
                                            :body_location {:view "back"
                                                            :x    0.0
                                                            :y    1.0}})))))

  (testing "Rejects a malformed body location — free text, bad view,
            out-of-range coordinate, or stray keys"
    (doseq [bad [{:view "front" :x 0.5 :y "head"}
                 {:view "front" :x 1.5 :y 0.5}
                 {:view "left" :x 0.5 :y 0.5}
                 {:view "front" :x 0.5 :y 0.5 :z 0.5}
                 {:x 0.5 :y 0.5}]]
      (is (thrown-with-msg?
           #?(:clj clojure.lang.ExceptionInfo
              :cljs cljs.core.ExceptionInfo) #"Validation failed"
           (part/make-part {:map_id "m" :body_location bad})))))

  (testing "Coerces float position coordinates to ints — ReactFlow's
            screenToFlowPosition yields floats, and the spec is strict `int?`"
    (let [result (part/make-part {:map_id     "m"
                                  :position_x 123.7
                                  :position_y -8.4})]
      (is (= 123 (:position_x result)))
      (is (= -8  (:position_y result)))
      (is (int? (:position_x result)))
      (is (int? (:position_y result)))))

  (testing "Rejects dimensions outside the canvas resize bounds"
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo
            :cljs cljs.core.ExceptionInfo) #"Validation failed"
         (part/make-part {:map_id "m" :width 59})))
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo
            :cljs cljs.core.ExceptionInfo) #"Validation failed"
         (part/make-part {:map_id "m" :height 401}))))

  (testing "Accepts dimensions at the bounds"
    (let [result (part/make-part {:map_id "m" :width 60 :height 400})]
      (is (= 60 (:width result)))
      (is (= 400 (:height result)))))

  (testing "Throws exception for invalid part type"
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo
            :cljs cljs.core.ExceptionInfo) #"Validation failed"
         (part/make-part {:map_id "test" :type "invalid-type"}))))

  (testing "Throws exception for missing required attributes"
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo
            :cljs cljs.core.ExceptionInfo) #"Validation failed"
         (part/make-part {})))))

(deftest validate-update-test
  (testing "Accepts a partial map of mutable attributes"
    (is (nil? (part/validate-update {:label "New label"}))))

  (testing "Rejects out-of-bounds dimensions — the update gate enforces the
            same 60–400 resize bounds as the UI, so a malformed change-event
            can't write absurd sizes"
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo
            :cljs cljs.core.ExceptionInfo) #"Validation failed"
         (part/validate-update {:width 59})))
    (is (nil? (part/validate-update {:width 60 :height 400}))))

  (testing "Accepts a structured body location, rejects free text"
    (is (nil? (part/validate-update {:body_location {:view "front" :x 0.1 :y 0.9}})))
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo
            :cljs cljs.core.ExceptionInfo) #"Validation failed"
         (part/validate-update {:body_location "left shoulder"}))))

  (testing "Rejects :id and :map_id — a Part's identity can't be updated"
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo
            :cljs cljs.core.ExceptionInfo) #"Validation failed"
         (part/validate-update {:label "x" :id "sneaky"})))
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo
            :cljs cljs.core.ExceptionInfo) #"Validation failed"
         (part/validate-update {:label "x" :map_id "sneaky"})))))
