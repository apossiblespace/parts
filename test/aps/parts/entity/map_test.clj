(ns aps.parts.entity.map-test
  (:require
   [aps.parts.db :as db]
   [aps.parts.db.bitemporal :as bt]
   [aps.parts.entity.map :as parts-map]
   [aps.parts.entity.part :as part]
   [aps.parts.entity.relationship :as relationship]
   [aps.parts.helpers.utils :refer [create-test-user! with-test-db]]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [next.jdbc :as jdbc])
  (:import
   (java.time OffsetDateTime)))

(use-fixtures :each with-test-db)

(deftest test-title-history-is-replayable
  (testing "renaming a map twice; scrubber-style queries see each title at its time"
    (let [user    (create-test-user!)
          the-map (parts-map/create! {:title "Title One" :owner_id (:id user)} (:id user))
          map-id  (:id the-map)
          t-one   (do (Thread/sleep 50) (OffsetDateTime/now))
          _       (Thread/sleep 50)
          _       (parts-map/update! map-id {:title "Title Two"} (:id user))
          t-two   (do (Thread/sleep 50) (OffsetDateTime/now))
          _       (Thread/sleep 50)
          _       (parts-map/update! map-id {:title "Title Three"} (:id user))]
      (testing "as-of-now returns the latest title"
        (is (= "Title Three" (:title (parts-map/fetch map-id)))))
      (testing "as-of-valid at t-one returns Title One"
        (is (= "Title One"
               (-> (bt/as-of-valid db/datasource :map_metadata (str t-one)
                                   [:= :map_id map-id])
                   first :title))))
      (testing "as-of-valid at t-two returns Title Two"
        (is (= "Title Two"
               (-> (bt/as-of-valid db/datasource :map_metadata (str t-two)
                                   [:= :map_id map-id])
                   first :title)))))))

(deftest test-map-crud
  (let [user     (create-test-user!)
        map-data {:title    "Test Map"
                  :owner_id (:id user)}]

    (testing "create!"
      (let [created (parts-map/create! map-data (:id user))]
        (is (uuid? (:id created)))
        (is (= (:title map-data) (:title created)))
        (is (= (:owner_id map-data) (:owner_id created)))
        (is (some? (:created_at created)))))

    (testing "fetch"
      (let [created (parts-map/create! map-data (:id user))
            fetched (parts-map/fetch (:id created))]
        (is (= (:id created) (:id fetched)))
        (is (= (:title created) (:title fetched)))
        (is (vector? (:parts fetched)))
        (is (vector? (:relationships fetched)))))

    (testing "index"
      (let [_    (parts-map/create! map-data (:id user))
            maps (parts-map/index (:id user))]
        (is (seq maps))
        (is (every? #(= (:owner_id %) (:id user)) maps))))

    (testing "update!"
      (let [created (parts-map/create! map-data (:id user))
            updated (parts-map/update! (:id created) {:title    "Updated Title"
                                                      :owner_id (:id user)}
                                       (:id user))]
        (is (= "Updated Title" (:title updated)))
        (is (= (:id created) (:id updated)))))

    (testing "delete!"
      (let [created (parts-map/create! map-data (:id user))
            result  (parts-map/delete! (:id created) (:id user))]
        (is (:success result))
        (is (= (:id created) (:id result)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Map not found"
                              (parts-map/fetch (:id created))))))))

(deftest test-map-validations
  (testing "creates fails with invalid data"
    (let [user (create-test-user!)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Validation failed"
                            (parts-map/create! {} (:id user))))))

  (testing "update fails with invalid data"
    (let [user    (create-test-user!)
          the-map (parts-map/create! {:title "Test" :owner_id (:id user)} (:id user))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Validation failed"
                            (parts-map/update! (:id the-map) {:title nil} (:id user)))))))

(deftest test-delete-with-transaction
  (testing "Deleting a map with parts and relationships removes everything"
    (let [user                (create-test-user!)
          the-map             (parts-map/create! {:title "Transaction Test" :owner_id (:id user)} (:id user))

          part1               (part/create! {:map_id     (:id the-map)
                                             :type       "manager"
                                             :label      "Part 1"
                                             :position_x 0
                                             :position_y 0}
                                            (:id user))

          part2               (part/create! {:map_id     (:id the-map)
                                             :type       "exile"
                                             :label      "Part 2"
                                             :position_x 100
                                             :position_y 100}
                                            (:id user))

          _relationship       (relationship/create! {:map_id    (:id the-map)
                                                     :source_id (:id part1)
                                                     :target_id (:id part2)
                                                     :type      "unknown"}
                                                    (:id user))
          map-before-deletion (parts-map/fetch (:id the-map))
          result              (parts-map/delete! (:id the-map) (:id user))]

      (is (= 2 (count (:parts map-before-deletion))))
      (is (= 1 (count (:relationships map-before-deletion))))

      (is (:success result))
      (is (= (:id the-map) (:id result)))
      (is (= 2 (:parts-deleted result)))
      (is (= 1 (:relationships-deleted result)))

      (is (thrown? Exception (parts-map/fetch (:id the-map)))))))

(deftest test-transaction-rollback
  (testing "Transaction rolls back if an error occurs"
    (let [user                (create-test-user!)
          the-map             (parts-map/create! {:title "Transaction Test" :owner_id (:id user)} (:id user))
          _part               (part/create! {:map_id     (:id the-map)
                                             :type       "manager"
                                             :label      "Test Part"
                                             :position_x 0
                                             :position_y 0}
                                            (:id user))

          map-before-deletion (parts-map/fetch (:id the-map))]

      (is (= 1 (count (:parts map-before-deletion))))

      ;; next.jdbc/with-transaction rolls back automatically on uncaught
      ;; throws; redef execute! to throw mid-flight and verify nothing
      ;; landed.
      (with-redefs [next.jdbc/execute! (fn [& _]
                                         (throw (Exception. "Simulated transaction error")))]
        (is (thrown? Exception (parts-map/delete! (:id the-map) (:id user)))))

      (let [map-after (parts-map/fetch (:id the-map))]
        (is map-after "Map should still exist")
        (is (= 1 (count (:parts map-after))) "Part should still exist")))))

(deftest test-fetch-identity
  (let [user    (create-test-user!)
        the-map (parts-map/create! {:title "Identity Test" :owner_id (:id user)} (:id user))]

    (testing "returns the identity row — id and owner_id, no enrichment"
      (let [identity-row (parts-map/fetch-identity (:id the-map))]
        (is (= (:id the-map) (:id identity-row)))
        (is (= (:id user) (:owner_id identity-row)))
        (is (not (contains? identity-row :parts)))
        (is (not (contains? identity-row :relationships)))))

    (testing "returns nil for a nonexistent map — no throw, unlike fetch"
      (is (nil? (parts-map/fetch-identity (random-uuid)))))

    (testing "returns nil for a soft-deleted map"
      (parts-map/delete! (:id the-map) (:id user))
      (is (nil? (parts-map/fetch-identity (:id the-map)))))))
