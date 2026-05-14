(ns aps.parts.common.change-event-test
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer-macros [deftest is testing]])
   [aps.parts.common.change-event :as ce]
   [clojure.spec.alpha :as s]))

;; -- canonical fixtures: one valid change-event per [entity type] ----------

(def part-create
  {:entity :part
   :type   :create
   :id     "part-1"
   :data   {:type       "manager"
            :label      "Inner Critic"
            :position_x 100
            :position_y 200}})

(def part-update
  {:entity :part
   :type   :update
   :id     "part-1"
   :data   {:label "Renamed"}})

(def part-remove
  {:entity :part
   :type   :remove
   :id     "part-1"
   :data   {}})

(def relationship-create
  {:entity :relationship
   :type   :create
   :id     "rel-1"
   :data   {:type      "protective"
            :source_id "part-1"
            :target_id "part-2"}})

(def relationship-update
  {:entity :relationship
   :type   :update
   :id     "rel-1"
   :data   {:notes "softened"}})

(def relationship-remove
  {:entity :relationship
   :type   :remove
   :id     "rel-1"
   :data   {}})

(deftest canonical-shape-test
  (testing "valid change-events of every entity/type combination conform"
    (is (s/valid? ::ce/change-event part-create))
    (is (s/valid? ::ce/change-event part-update))
    (is (s/valid? ::ce/change-event part-remove))
    (is (s/valid? ::ce/change-event relationship-create))
    (is (s/valid? ::ce/change-event relationship-update))
    (is (s/valid? ::ce/change-event relationship-remove)))

  (testing "the envelope requires :entity, :type, :id and :data"
    (is (not (s/valid? ::ce/change-event (dissoc part-create :entity))))
    (is (not (s/valid? ::ce/change-event (dissoc part-create :type))))
    (is (not (s/valid? ::ce/change-event (dissoc part-create :id))))
    (is (not (s/valid? ::ce/change-event (dissoc part-create :data)))))

  (testing ":entity and :type are canonical keywords, not wire strings"
    (is (not (s/valid? ::ce/change-event (assoc part-create :entity "part"))))
    (is (not (s/valid? ::ce/change-event (assoc part-create :type "create")))))

  (testing ":position is no longer a change-event type — collapsed into :update"
    (is (not (s/valid? ::ce/change-event (assoc part-update :type :position)))))

  (testing "unknown entities are rejected"
    (is (not (s/valid? ::ce/change-event (assoc part-create :entity :comment))))))

(deftest data-payload-test
  (testing ":create data must carry the entity's required attributes"
    (is (not (s/valid? ::ce/change-event
                       (assoc part-create :data {:type "manager"}))))
    (is (not (s/valid? ::ce/change-event
                       (assoc relationship-create :data {:type "protective"})))))

  (testing ":create data rejects invalid attribute values"
    (is (not (s/valid? ::ce/change-event
                       (assoc-in part-create [:data :type] "not-a-type")))))

  (testing ":update data must be a non-empty subset of attributes"
    (is (not (s/valid? ::ce/change-event (assoc part-update :data {}))))
    (is (not (s/valid? ::ce/change-event
                       (assoc-in part-update [:data :position_x] "not-an-int")))))

  (testing ":remove data must be empty"
    (is (not (s/valid? ::ce/change-event (assoc part-remove :data {:label "x"})))))

  (testing ":data is validated against the matching entity, not another"
    (is (not (s/valid? ::ce/change-event
                       (assoc part-create :data (:data relationship-create)))))))

(deftest data-spec-test
  (testing "data-spec resolves the right :data spec per [entity type]"
    (is (= ::ce/part-create-data         (ce/data-spec part-create)))
    (is (= ::ce/part-update-data         (ce/data-spec part-update)))
    (is (= ::ce/remove-data              (ce/data-spec part-remove)))
    (is (= ::ce/relationship-create-data (ce/data-spec relationship-create)))
    (is (= ::ce/relationship-update-data (ce/data-spec relationship-update)))
    (is (= ::ce/remove-data              (ce/data-spec relationship-remove))))

  (testing "data-spec returns nil for an unknown [entity type] combination"
    (is (nil? (ce/data-spec {:entity :comment :type :create})))))

(deftest constructors-test
  (testing "constructors build the canonical event for each operation"
    (is (= part-create         (ce/part-create "part-1" (:data part-create))))
    (is (= part-update         (ce/part-update "part-1" (:data part-update))))
    (is (= part-remove         (ce/part-remove "part-1")))
    (is (= relationship-create (ce/relationship-create "rel-1" (:data relationship-create))))
    (is (= relationship-update (ce/relationship-update "rel-1" (:data relationship-update))))
    (is (= relationship-remove (ce/relationship-remove "rel-1"))))

  (testing "part-moved builds an :update — :position is not a wire type"
    (let [event (ce/part-moved "part-1" 100 200)]
      (is (= :update (:type event)))
      (is (= {:position_x 100 :position_y 200} (:data event)))
      (is (s/valid? ::ce/change-event event))))

  (testing "part-moved coerces fractional coordinates to ints"
    (is (= {:position_x 100 :position_y 200}
           (:data (ce/part-moved "part-1" 100.7 200.2)))))

  (testing "constructors throw on invalid input"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
                 (ce/part-create "part-1" {:type "manager"})))
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
                 (ce/part-update "part-1" {})))
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
                 (ce/relationship-create "rel-1" {:type      "not-a-type"
                                                  :source_id "a"
                                                  :target_id "b"})))))
