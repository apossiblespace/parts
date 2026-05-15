(ns aps.parts.entity.system-test
  (:require
   [aps.parts.db :as db]
   [aps.parts.db.bitemporal :as bt]
   [aps.parts.entity.part :as part]
   [aps.parts.entity.relationship :as relationship]
   [aps.parts.entity.system :as system]
   [aps.parts.helpers.utils :refer [create-test-user! with-test-db]]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [next.jdbc :as jdbc])
  (:import
   (java.time OffsetDateTime)))

(use-fixtures :each with-test-db)

(deftest test-title-history-is-replayable
  (testing "renaming a system twice; scrubber-style queries see each title at its time"
    (let [user      (create-test-user!)
          system    (system/create! {:title "Title One" :owner_id (:id user)} (:id user))
          system-id (:id system)
          t-one     (do (Thread/sleep 50) (OffsetDateTime/now))
          _         (Thread/sleep 50)
          _         (system/update! system-id {:title "Title Two"} (:id user))
          t-two     (do (Thread/sleep 50) (OffsetDateTime/now))
          _         (Thread/sleep 50)
          _         (system/update! system-id {:title "Title Three"} (:id user))]
      (testing "as-of-now returns the latest title"
        (is (= "Title Three" (:title (system/fetch system-id)))))
      (testing "as-of-valid at t-one returns Title One"
        (is (= "Title One"
               (-> (bt/as-of-valid db/datasource :system_metadata (str t-one)
                                   [:= :system_id system-id])
                   first :title))))
      (testing "as-of-valid at t-two returns Title Two"
        (is (= "Title Two"
               (-> (bt/as-of-valid db/datasource :system_metadata (str t-two)
                                   [:= :system_id system-id])
                   first :title)))))))

(deftest test-system-crud
  (let [user        (create-test-user!)
        system-data {:title    "Test System"
                     :owner_id (:id user)}]

    (testing "create!"
      (let [created (system/create! system-data (:id user))]
        (is (uuid? (:id created)))
        (is (= (:title system-data) (:title created)))
        (is (= (:owner_id system-data) (:owner_id created)))
        (is (some? (:created_at created)))))

    (testing "fetch"
      (let [created (system/create! system-data (:id user))
            fetched (system/fetch (:id created))]
        (is (= (:id created) (:id fetched)))
        (is (= (:title created) (:title fetched)))
        (is (vector? (:parts fetched)))
        (is (vector? (:relationships fetched)))))

    (testing "index"
      (let [_       (system/create! system-data (:id user))
            systems (system/index (:id user))]
        (is (seq systems))
        (is (every? #(= (:owner_id %) (:id user)) systems))))

    (testing "update!"
      (let [created (system/create! system-data (:id user))
            updated (system/update! (:id created) {:title    "Updated Title"
                                                   :owner_id (:id user)}
                                    (:id user))]
        (is (= "Updated Title" (:title updated)))
        (is (= (:id created) (:id updated)))))

    (testing "delete!"
      (let [created (system/create! system-data (:id user))
            result  (system/delete! (:id created) (:id user))]
        (is (:success result))
        (is (= (:id created) (:id result)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"System not found"
                              (system/fetch (:id created))))))))

(deftest test-system-validations
  (testing "creates fails with invalid data"
    (let [user (create-test-user!)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Validation failed"
                            (system/create! {} (:id user))))))

  (testing "update fails with invalid data"
    (let [user   (create-test-user!)
          system (system/create! {:title "Test" :owner_id (:id user)} (:id user))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Validation failed"
                            (system/update! (:id system) {:title nil} (:id user)))))))

(deftest test-delete-with-transaction
  (testing "Deleting a system with parts and relationships removes everything"
    (let [user                   (create-test-user!)
          system                 (system/create! {:title "Transaction Test" :owner_id (:id user)} (:id user))

          part1                  (part/create! {:system_id  (:id system)
                                                :type       "manager"
                                                :label      "Part 1"
                                                :position_x 0
                                                :position_y 0}
                                               (:id user))

          part2                  (part/create! {:system_id  (:id system)
                                                :type       "exile"
                                                :label      "Part 2"
                                                :position_x 100
                                                :position_y 100}
                                               (:id user))

          _relationship          (relationship/create! {:system_id (:id system)
                                                        :source_id (:id part1)
                                                        :target_id (:id part2)
                                                        :type      "unknown"}
                                                       (:id user))
          system-before-deletion (system/fetch (:id system))
          result                 (system/delete! (:id system) (:id user))]

      (is (= 2 (count (:parts system-before-deletion))))
      (is (= 1 (count (:relationships system-before-deletion))))

      (is (:success result))
      (is (= (:id system) (:id result)))
      (is (= 2 (:parts-deleted result)))
      (is (= 1 (:relationships-deleted result)))

      (is (thrown? Exception (system/fetch (:id system)))))))

(deftest test-transaction-rollback
  (testing "Transaction rolls back if an error occurs"
    (let [user                   (create-test-user!)
          system                 (system/create! {:title "Transaction Test" :owner_id (:id user)} (:id user))
          _part                  (part/create! {:system_id  (:id system)
                                                :type       "manager"
                                                :label      "Test Part"
                                                :position_x 0
                                                :position_y 0}
                                               (:id user))

          system-before-deletion (system/fetch (:id system))]

      (is (= 1 (count (:parts system-before-deletion))))

      ;; next.jdbc/with-transaction rolls back automatically on uncaught
      ;; throws; redef execute! to throw mid-flight and verify nothing
      ;; landed.
      (with-redefs [next.jdbc/execute! (fn [& _]
                                         (throw (Exception. "Simulated transaction error")))]
        (is (thrown? Exception (system/delete! (:id system) (:id user)))))

      (let [system-after (system/fetch (:id system))]
        (is system-after "System should still exist")
        (is (= 1 (count (:parts system-after))) "Part should still exist")))))

(deftest test-fetch-identity
  (let [user   (create-test-user!)
        system (system/create! {:title "Identity Test" :owner_id (:id user)} (:id user))]

    (testing "returns the identity row — id and owner_id, no enrichment"
      (let [identity-row (system/fetch-identity (:id system))]
        (is (= (:id system) (:id identity-row)))
        (is (= (:id user) (:owner_id identity-row)))
        (is (not (contains? identity-row :parts)))
        (is (not (contains? identity-row :relationships)))))

    (testing "returns nil for a nonexistent system — no throw, unlike fetch"
      (is (nil? (system/fetch-identity (random-uuid)))))

    (testing "returns nil for a soft-deleted system"
      (system/delete! (:id system) (:id user))
      (is (nil? (system/fetch-identity (:id system)))))))
