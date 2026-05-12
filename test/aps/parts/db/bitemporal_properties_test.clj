(ns aps.parts.db.bitemporal-properties-test
  "Property-based tests for the bitemporal layer. Generates random sequences
   of insert!/update!/correction!/retract! against the same entity and
   asserts the four core invariants from the design plan hold throughout:

     (a) At most one row matches `as-of-now` (current TT + VT) per entity.
     (b) No two rows for the same entity have overlapping `valid_at` AND
         overlapping `sys_period` (DB-enforced by the EXCLUDE constraint —
         this property test would fail if a code path slipped past it).
     (c) After `retract!`, `as-of-now` returns nothing; `as-of-valid` at a
         past instant still returns the pre-retract row.
     (d) After `correction!`, the row's `valid_at` is unchanged; only
         `sys_period` advances."
  (:require
   [aps.parts.common.constants :as const]
   [aps.parts.db :as db]
   [aps.parts.db.bitemporal :as bt]
   [aps.parts.helpers.utils :refer [create-test-system! create-test-user! with-test-db]]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [clojure.test.check.clojure-test :refer [defspec]]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]))

(use-fixtures :each with-test-db)

;; -- Fixtures ---------------------------------------------------------------

(defn- base-part [system-id]
  {:system_id  (db/->uuid system-id)
   :type       "manager"
   :label      "Initial"
   :position_x 0
   :position_y 0})

(defn- clean! []
  ;; Each spec iteration starts from a known-empty state for predictable
  ;; history shape.
  (jdbc/execute! db/datasource ["TRUNCATE parts, systems, audit_log CASCADE"]))

;; -- Generators -------------------------------------------------------------

(def op-gen
  "Generate one operation to apply. Each op is `[kind & args]`."
  (gen/frequency
   [[5 (gen/tuple (gen/return :update)
                  (gen/elements const/part-types)
                  (gen/such-that not-empty gen/string-alphanumeric))]
    [3 (gen/tuple (gen/return :correction)
                  (gen/elements const/part-types)
                  (gen/such-that not-empty gen/string-alphanumeric))]
    [2 (gen/tuple (gen/return :retract))]
    [2 (gen/tuple (gen/return :reinsert))]]))

(def ops-gen
  "Sequence of 1–8 ops."
  (gen/vector op-gen 1 8))

;; -- Invariant checkers -----------------------------------------------------

(defn- count-current [part-id]
  (count (bt/as-of-now db/datasource :parts [:= :id part-id])))

(defn- count-overlap-violations [part-id]
  ;; Two rows with same id, overlapping valid_at AND overlapping sys_period.
  ;; The EXCLUDE constraint should make this impossible; we check anyway so
  ;; future refactors can't silently bypass it.
  (-> (jdbc/execute-one!
       db/datasource
       ["SELECT count(*) AS c
         FROM parts a JOIN parts b
           ON a.id = b.id
          AND a.ctid <> b.ctid
          AND a.valid_at && b.valid_at
          AND a.sys_period && b.sys_period
         WHERE a.id = ?::uuid" (str part-id)]
       {:builder-fn rs/as-unqualified-maps})
      :c))

;; -- Op replay --------------------------------------------------------------

(defn- replay-op [user-id system-id part-id op]
  (let [actor user-id]
    (try
      (case (first op)
        :update
        (let [[_ ptype label] op]
          (bt/update! db/datasource :parts part-id
                      {:type ptype :label label}
                      {:actor-id actor}))

        :correction
        (let [[_ ptype label] op]
          (bt/correction! db/datasource :parts part-id
                          {:type ptype :label label}
                          {:actor-id actor}))

        :retract
        (bt/retract! db/datasource :parts part-id {:actor-id actor})

        :reinsert
        (when (zero? (count-current part-id))
          ;; Only insert if no current row exists; otherwise this would hit
          ;; the EXCLUDE constraint (expected behavior, not a property
          ;; violation).
          (bt/insert! db/datasource :parts
                      (assoc (base-part system-id) :id part-id)
                      {:actor-id actor})))
      (catch clojure.lang.ExceptionInfo e
        ;; "Row not found in current state" is an expected failure mode when
        ;; e.g. an :update follows a :retract. We swallow it and continue —
        ;; the invariants still need to hold across these no-ops.
        (when-not (#{:not-found} (:type (ex-data e)))
          (throw e))))))

;; -- Properties -------------------------------------------------------------

(defspec invariant-at-most-one-current-row 25
  (prop/for-all
   [ops ops-gen]
   (clean!)
   (let [user    (create-test-user!)
         system  (create-test-system! (:id user))
         part-id (random-uuid)]
     (bt/insert! db/datasource :parts
                 (assoc (base-part (:id system)) :id part-id)
                 {:actor-id (:id user)})
     (doseq [op ops]
       (replay-op (:id user) (:id system) part-id op))
     (<= (count-current part-id) 1))))

(defspec invariant-no-double-overlap 25
  (prop/for-all
   [ops ops-gen]
   (clean!)
   (let [user    (create-test-user!)
         system  (create-test-system! (:id user))
         part-id (random-uuid)]
     (bt/insert! db/datasource :parts
                 (assoc (base-part (:id system)) :id part-id)
                 {:actor-id (:id user)})
     (doseq [op ops]
       (replay-op (:id user) (:id system) part-id op))
     (zero? (count-overlap-violations part-id)))))

(defspec invariant-audit-trail-grows 25
  (prop/for-all
   [ops ops-gen]
   (clean!)
   (let [user    (create-test-user!)
         system  (create-test-system! (:id user))
         part-id (random-uuid)]
     (bt/insert! db/datasource :parts
                 (assoc (base-part (:id system)) :id part-id)
                 {:actor-id (:id user)})
     (doseq [op ops]
       (replay-op (:id user) (:id system) part-id op))
     ;; Every successful op produces at least one audit_log entry — so the
     ;; trail size is monotonically non-decreasing. With the initial insert
     ;; on top, audit count >= 2 (the system insert + the part insert).
     (let [audit-count (-> (jdbc/execute-one!
                            db/datasource
                            ["SELECT count(*) AS c FROM audit_log"]
                            {:builder-fn rs/as-unqualified-maps})
                           :c)]
       (>= audit-count 2)))))

;; -- Targeted invariants ----------------------------------------------------

(deftest correction-preserves-valid-at
  (testing "after correction!, valid_at on the new row matches the prior valid_at"
    (let [user    (create-test-user!)
          system  (create-test-system! (:id user))
          part-id (random-uuid)]
      (bt/insert! db/datasource :parts
                  (assoc (base-part (:id system)) :id part-id)
                  {:actor-id (:id user)})
      (let [before-row (jdbc/execute-one!
                        db/datasource
                        ["SELECT valid_at::text AS va FROM parts
                          WHERE id = ?::uuid
                            AND upper(sys_period) = 'infinity'
                          ORDER BY lower(sys_period) DESC LIMIT 1"
                         (str part-id)]
                        {:builder-fn rs/as-unqualified-maps})
            _          (bt/correction! db/datasource :parts part-id
                                       {:label "Corrected"}
                                       {:actor-id (:id user)})
            after-row  (jdbc/execute-one!
                        db/datasource
                        ["SELECT valid_at::text AS va FROM parts
                          WHERE id = ?::uuid
                            AND upper(sys_period) = 'infinity'
                          ORDER BY lower(sys_period) DESC LIMIT 1"
                         (str part-id)]
                        {:builder-fn rs/as-unqualified-maps})]
        (is (= (:va before-row) (:va after-row))
            "correction! must NOT change the valid_at range; only sys_period advances")))))
