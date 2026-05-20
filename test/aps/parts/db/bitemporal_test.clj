(ns aps.parts.db.bitemporal-test
  "End-to-end tests for the bitemporal layer — proves the split-retract-insert
   semantics, time-slice queries (`as-of-now`, `as-of-valid`), the no-overlap
   constraint, and audit-trail integration all compose correctly."
  (:require
   [aps.parts.db :as db]
   [aps.parts.db.bitemporal :as bt]
   [aps.parts.helpers.utils :refer [create-test-map! create-test-user! with-test-db]]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs])
  (:import
   (java.time OffsetDateTime)))

(use-fixtures :each with-test-db)

(defn- part-row [map-id]
  {:id         (random-uuid)
   :map_id     (db/->uuid map-id)
   :type       "manager"
   :label      "Test"
   :position_x 0
   :position_y 0})

(defn- count-current [table id]
  (count (bt/as-of-now db/datasource table [:= :id (db/->uuid id)])))

(defn- count-all-history [table id]
  (-> (jdbc/execute-one!
       db/datasource
       [(str "SELECT count(*) AS c FROM " (name table) " WHERE id = ?::uuid") (str id)]
       {:builder-fn rs/as-unqualified-maps})
      :c))

(deftest test-insert-and-fetch
  (let [user    (create-test-user!)
        the-map (create-test-map! (:id user))
        row     (assoc (part-row (:id the-map)) :label "Hello")
        result  (bt/insert! db/datasource :parts row {:actor-id (:id user)})]
    (testing "insert returns the row, without internal columns"
      (is (= (:id row) (:id result)))
      (is (= "Hello" (:label result)))
      (is (not (contains? result :valid_at)))
      (is (not (contains? result :sys_period)))
      (is (not (contains? result :actor_id))))

    (testing "as-of-now finds the inserted row"
      (let [rows (bt/as-of-now db/datasource :parts [:= :id (:id row)])]
        (is (= 1 (count rows)))
        (is (= "Hello" (-> rows first :label)))))))

(deftest test-update-preserves-history
  (let [user     (create-test-user!)
        the-map  (create-test-map! (:id user))
        row      (assoc (part-row (:id the-map)) :label "Original")
        _        (bt/insert! db/datasource :parts row {:actor-id (:id user)})
        t-insert (OffsetDateTime/now)
        _        (Thread/sleep 100)
        _        (bt/update! db/datasource :parts (:id row)
                             {:label "Renamed"}
                             {:actor-id (:id user)})
        t-after  (OffsetDateTime/now)]
    (testing "as-of-now shows the rename"
      (is (= "Renamed"
             (-> (bt/as-of-now db/datasource :parts [:= :id (:id row)])
                 first :label))))

    (testing "as-of-valid at the pre-rename moment returns the original"
      (is (= "Original"
             (-> (bt/as-of-valid db/datasource :parts (str t-insert)
                                 [:= :id (:id row)])
                 first :label))))

    (testing "as-of-valid at the post-rename moment returns the rename"
      (is (= "Renamed"
             (-> (bt/as-of-valid db/datasource :parts (str t-after)
                                 [:= :id (:id row)])
                 first :label))))

    (testing "the table accumulates physical history rows"
      (is (>= (count-all-history :parts (:id row)) 3)))))

(deftest test-retract-disappears-from-current-view
  (let [user    (create-test-user!)
        the-map (create-test-map! (:id user))
        row     (assoc (part-row (:id the-map)) :type "exile" :label "Ephemeral")
        _       (bt/insert! db/datasource :parts row {:actor-id (:id user)})
        t-alive (OffsetDateTime/now)
        _       (Thread/sleep 100)
        result  (bt/retract! db/datasource :parts (:id row) {:actor-id (:id user)})]

    (testing "retract returns :retracted true"
      (is (true? (:retracted result))))

    (testing "as-of-now finds no current row"
      (is (zero? (count-current :parts (:id row)))))

    (testing "as-of-valid before the retract still finds it"
      (is (pos? (count (bt/as-of-valid db/datasource :parts
                                       (str t-alive)
                                       [:= :id (:id row)])))))))

(deftest test-no-overlap-constraint-rejects-conflict
  (let [user    (create-test-user!)
        the-map (create-test-map! (:id user))
        row     (part-row (:id the-map))]
    (bt/insert! db/datasource :parts row {:actor-id (:id user)})
    (testing "second insert with same id + overlapping valid_at is rejected"
      (is (thrown-with-msg?
           org.postgresql.util.PSQLException
           #"exclusion constraint"
           (bt/insert! db/datasource :parts (assoc row :label "Conflict")
                       {:actor-id (:id user)}))))))

(deftest test-audit-log-captures-actor
  (let [user    (create-test-user!)
        the-map (create-test-map! (:id user))
        row     (assoc (part-row (:id the-map)) :label "Audited")]
    (bt/insert! db/datasource :parts row {:actor-id (:id user)})
    (testing "audit_log has at least one row for the insert"
      (let [entries (jdbc/execute! db/datasource
                                   ["SELECT operation, actor_id FROM audit_log
                                     WHERE table_name = 'parts'
                                       AND row_pk ->> 'id' = ?"
                                    (str (:id row))]
                                   {:builder-fn rs/as-unqualified-maps})]
        (is (= 1 (count entries)))
        (is (= "I" (:operation (first entries))))
        (is (= (:id user) (:actor_id (first entries))))))))

(deftest test-three-step-timeline
  (testing "the bitemporal API supports the scrubber's exact use case"
    (let [user    (create-test-user!)
          the-map (create-test-map! (:id user))
          row     (assoc (part-row (:id the-map)) :label "Step 1")
          _       (bt/insert! db/datasource :parts row {:actor-id (:id user)})
          t1      (do (Thread/sleep 50) (OffsetDateTime/now))
          _       (Thread/sleep 50)
          _       (bt/update! db/datasource :parts (:id row) {:label "Step 2"} {:actor-id (:id user)})
          t2      (do (Thread/sleep 50) (OffsetDateTime/now))
          _       (Thread/sleep 50)
          _       (bt/update! db/datasource :parts (:id row) {:label "Step 3"} {:actor-id (:id user)})]
      (is (= "Step 1" (-> (bt/as-of-valid db/datasource :parts (str t1)
                                          [:= :id (:id row)])
                          first :label)))
      (is (= "Step 2" (-> (bt/as-of-valid db/datasource :parts (str t2)
                                          [:= :id (:id row)])
                          first :label)))
      (is (= "Step 3" (-> (bt/as-of-now db/datasource :parts
                                        [:= :id (:id row)])
                          first :label))))))

(deftest test-update-cannot-relocate-the-row
  (testing "a sequenced update is keyed on its `id` parameter — an `:id` in
            `changes` cannot move the entity to a different identity"
    (let [user     (create-test-user!)
          the-map  (create-test-map! (:id user))
          row      (assoc (part-row (:id the-map)) :label "Original")
          _        (bt/insert! db/datasource :parts row {:actor-id (:id user)})
          rogue-id (random-uuid)
          updated  (bt/update! db/datasource :parts (:id row)
                               {:label "Renamed" :id rogue-id}
                               {:actor-id (:id user)})]
      (testing "the new history row keeps the entity's real id"
        (is (= (:id row) (:id updated))))
      (testing "the real entity is intact and the rename applied"
        (is (= 1 (count-current :parts (:id row))))
        (is (= "Renamed" (-> (bt/as-of-now db/datasource :parts [:= :id (:id row)])
                             first :label))))
      (testing "no phantom row was created under the rogue id"
        (is (zero? (count-current :parts rogue-id)))))))
