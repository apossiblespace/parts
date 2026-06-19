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

(deftest test-history-returns-all-valid-time-versions
  (testing "history folds across every valid-time version (current belief),
            chronological, with the valid interval surfaced as valid_from/valid_to"
    (let [user     (create-test-user!)
          the-map  (create-test-map! (:id user))
          row      (assoc (part-row (:id the-map)) :label "v1")
          _        (bt/insert! db/datasource :parts row {:actor-id (:id user)})
          _        (Thread/sleep 50)
          _        (bt/update! db/datasource :parts (:id row) {:label "v2"} {:actor-id (:id user)})
          _        (Thread/sleep 50)
          _        (bt/update! db/datasource :parts (:id row) {:label "v3"} {:actor-id (:id user)})
          versions (bt/history db/datasource :parts [:= :id (:id row)])]

      (testing "one row per valid-time version, in chronological order"
        (is (= ["v1" "v2" "v3"] (map :label versions))))

      (testing "the live version's valid_to is nil; superseded ones are closed"
        (is (nil? (:valid_to (last versions))))
        (is (every? some? (map :valid_to (butlast versions)))))

      (testing "every version carries a valid_from"
        (is (every? :valid_from versions)))

      (testing "internal temporal/actor columns are not surfaced"
        (is (not-any? #(contains? % :valid_at) versions))
        (is (not-any? #(contains? % :sys_period) versions))
        (is (not-any? #(contains? % :actor_id) versions))))))

(deftest test-history-includes-retracted-versions
  (testing "a retracted entity stays in history with a closed valid interval —
            removed from the live view, not erased"
    (let [user     (create-test-user!)
          the-map  (create-test-map! (:id user))
          row      (assoc (part-row (:id the-map)) :label "Removed")
          _        (bt/insert! db/datasource :parts row {:actor-id (:id user)})
          _        (Thread/sleep 50)
          _        (bt/retract! db/datasource :parts (:id row) {:actor-id (:id user)})
          versions (bt/history db/datasource :parts [:= :id (:id row)])]
      (is (zero? (count-current :parts (:id row))) "gone from the live view")
      (is (= 1 (count versions)) "but still present in history")
      (is (= "Removed" (:label (first versions))))
      (is (some? (:valid_to (first versions))) "with a closed valid_to"))))

(deftest test-same-entity-twice-in-one-transaction
  ;; Regression: the write path used to look up and close rows against SQL
  ;; now(), which Postgres freezes at transaction start, while stamping new
  ;; rows with wall-clock time. The second write to an entity in the same
  ;; transaction then found the wrong row (inverted range) or no row at all.
  ;; Observed live as batch-update 422s ("Row not found in current state").
  (let [user    (create-test-user!)
        the-map (create-test-map! (:id user))
        row     (assoc (part-row (:id the-map)) :label "Twice")
        _       (bt/insert! db/datasource :parts row {:actor-id (:id user)})]

    (testing "update → update of one id inside a single transaction"
      (jdbc/with-transaction [tx db/datasource]
        (bt/update! tx :parts (:id row) {:position_x 10} {:actor-id (:id user)})
        (bt/update! tx :parts (:id row) {:position_x 20} {:actor-id (:id user)}))
      (let [current (bt/as-of-now db/datasource :parts [:= :id (:id row)])]
        (is (= 1 (count current)) "exactly one currently-believed live row")
        (is (= 20 (:position_x (first current))) "the later update wins"))
      (is (= [0 10 20]
             (mapv :position_x (bt/history db/datasource :parts [:= :id (:id row)])))
          "the intermediate update is preserved as its own valid-time slice"))

    (testing "update → retract of one id inside a single transaction"
      (jdbc/with-transaction [tx db/datasource]
        (bt/update! tx :parts (:id row) {:position_x 30} {:actor-id (:id user)})
        (is (true? (:retracted (bt/retract! tx :parts (:id row)
                                            {:actor-id (:id user)})))))
      (is (zero? (count-current :parts (:id row))) "gone from the live view"))))

(deftest test-write-timestamps-clamp-against-clock-regression
  ;; A write's wall-clock timestamp can land at or before the found row's
  ;; bounds (NTP stepping the clock back between two writes). The write path
  ;; clamps its timestamp to just past the row's bounds instead of producing
  ;; an inverted range. Simulated here with a row whose sys_period starts in
  ;; the future: without the clamp, close-current-row! would build
  ;; sys_period = [future, now) — inverted — and Postgres rejects the write.
  (let [user      (create-test-user!)
        the-map   (create-test-map! (:id user))
        row       (part-row (:id the-map))
        in-future (.plusSeconds (OffsetDateTime/now) 5)
        _         (bt/insert! db/datasource :parts row
                              {:actor-id (:id user)
                               :sys-from in-future})]
    (testing "an update whose wall clock lags the row's bounds still succeeds"
      (let [updated (bt/update! db/datasource :parts (:id row)
                                {:label "Clamped"}
                                {:actor-id (:id user)})]
        (is (= "Clamped" (:label updated)))))
    (testing "the clamped write leaves a complete, well-formed timeline"
      (let [versions (bt/history db/datasource :parts [:= :id (:id row)])]
        (is (= 2 (count versions)) "historical slice + live slice")
        (is (= "Clamped" (:label (last versions))))
        (is (every? :valid_from versions) "no empty/degenerate ranges"))
      (is (= 1 (count-current :parts (:id row)))
          "exactly one currently-believed live row"))))

(deftest test-concurrent-writers-conflict-cleanly
  ;; Two transactions writing one entity (same map open in two tabs). The
  ;; loser's close-current-row! matches nothing after the winner commits
  ;; (EvalPlanQual re-check) — it must surface as a typed :conflict, not
  ;; proceed to inserts that explode on the EXCLUDE constraint.
  (let [user    (create-test-user!)
        the-map (create-test-map! (:id user))
        row     (assoc (part-row (:id the-map)) :label "Contended")
        _       (bt/insert! db/datasource :parts row {:actor-id (:id user)})
        conn    (jdbc/get-connection db/datasource)]
    (try
      (.setAutoCommit conn false)
      ;; Winner: updates inside an open transaction, holding the row lock.
      (bt/update! conn :parts (:id row) {:position_x 1} {:actor-id (:id user)})
      ;; Loser: reads the same current row, then blocks on the lock at close.
      (let [loser (future
                    (try
                      (bt/update! db/datasource :parts (:id row)
                                  {:position_x 2} {:actor-id (:id user)})
                      :updated
                      (catch clojure.lang.ExceptionInfo e
                        (:type (ex-data e)))
                      (catch Exception e
                        (class e))))]
        ;; Give the loser time to pass its read and reach the row lock; then
        ;; let the winner commit, unblocking it.
        (Thread/sleep 300)
        (.commit conn)
        (testing "the losing writer gets a typed :conflict"
          (is (= :conflict (deref loser 5000 :timed-out)))))
      (testing "the winner's value survives"
        (is (= 1 (-> (bt/as-of-now db/datasource :parts [:= :id (:id row)])
                     first :position_x))))
      (finally
        (.close conn)))))

(deftest test-count-current
  (testing "counts only currently-existing rows (TT-current ∧ VT-open)"
    (let [user    (create-test-user!)
          the-map (create-test-map! (:id user))
          mid     (:id the-map)
          scope   [:= :map_id (db/->uuid mid)]]
      (testing "zero when the table has no matching rows"
        (is (= 0 (bt/count-current db/datasource :parts scope))))

      (let [a (part-row mid)
            b (part-row mid)
            c (part-row mid)]
        (doseq [r [a b c]]
          (bt/insert! db/datasource :parts r {:actor-id (:id user)}))

        (testing "counts each live entity once"
          (is (= 3 (bt/count-current db/datasource :parts scope))))

        (testing "an update keeps the entity at a single current row"
          (bt/update! db/datasource :parts (:id a)
                      {:label "renamed"} {:actor-id (:id user)})
          (is (= 3 (bt/count-current db/datasource :parts scope))))

        (testing "a retracted entity drops out of the count"
          (bt/retract! db/datasource :parts (:id b) {:actor-id (:id user)})
          (is (= 2 (bt/count-current db/datasource :parts scope))))

        (testing "the where fragment scopes the count to one map"
          (let [other-map (create-test-map! (:id user))]
            (bt/insert! db/datasource :parts (part-row (:id other-map))
                        {:actor-id (:id user)})
            (is (= 2 (bt/count-current db/datasource :parts scope)))
            (is (= 1 (bt/count-current db/datasource :parts
                                       [:= :map_id (db/->uuid (:id other-map))])))))

        (testing "a nil where counts every live row in the table"
          (is (= 3 (bt/count-current db/datasource :parts nil))))))))
