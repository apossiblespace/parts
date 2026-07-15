(ns aps.parts.entity.schema-test
  "Fitness tests for the vocabulary mirrors that live in DDL. The CHECK
   constraints re-enumerate the type vocabularies from
   `aps.parts.common.constants`; unlike the CSS mirror (see
   architecture-test) no grep of src/ surfaces them, and drift only
   shows up as a 422 at commit time — which is exactly how the gap was
   found (2026-07-15)."
  (:require
   [aps.parts.common.constants :as constants]
   [aps.parts.db :as db]
   [aps.parts.helpers.utils :refer [with-test-db]]
   [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :once with-test-db)

(defn- constraint-types
  "The set of quoted string literals in a CHECK constraint's definition.
   Postgres rewrites `IN (...)` to `= ANY (ARRAY['a'::text, ...])`, so
   the literals are extracted rather than string-comparing the whole
   definition."
  [conname]
  (let [definition (-> (db/query
                        ["SELECT pg_get_constraintdef(oid) AS definition
                          FROM pg_constraint WHERE conname = ?" conname])
                       first
                       vals
                       first)]
    (set (map second (re-seq #"'([^']+)'" (str definition))))))

(deftest type-check-constraints-mirror-the-vocabulary-test
  (testing "relationships_type_check carries exactly the model vocabulary"
    (is (= constants/relationship-types
           (constraint-types "relationships_type_check"))))

  (testing "parts_type_check carries exactly the model vocabulary"
    (is (= constants/part-types
           (constraint-types "parts_type_check")))))
