(ns aps.parts.export-test
  "Tests the Map data-subject export builder (ADR-0010): it composes
   db.bitemporal/history into the export shape, includes clinical fields, and
   drops controller/internal columns."
  (:require
   [aps.parts.db :as db]
   [aps.parts.db.bitemporal :as bt]
   [aps.parts.entity.session :as session]
   [aps.parts.export :as export]
   [aps.parts.helpers.utils :refer [create-test-map! create-test-user! with-test-db]]
   [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each with-test-db)

(defn- part-row [map-id]
  {:id         (random-uuid)
   :map_id     (db/->uuid map-id)
   :type       "manager"
   :label      "P"
   :position_x 0
   :position_y 0})

(deftest test-export-shape-and-history
  (testing "export emits the ADR-0010 shape with each entity's full valid-time history"
    (let [user    (create-test-user!)
          the-map (create-test-map! (:id user))
          part    (assoc (part-row (:id the-map)) :label "v1" :notes "secret note")
          _       (bt/insert! db/datasource :parts part {:actor-id (:id user)})
          _       (Thread/sleep 50)
          _       (bt/update! db/datasource :parts (:id part) {:label "v2"} {:actor-id (:id user)})
          result  (export/export-map db/datasource (:id the-map))]

      (testing "envelope"
        (is (= "1" (:format_version result)))
        (is (some? (:exported_at result))))

      (testing "map carries id + created_at + title_history, not owner/internal cols"
        (is (= (db/->uuid (:id the-map)) (:id (:map result))))
        (is (some? (:created_at (:map result))))
        (is (vector? (:title_history (:map result))))
        (is (not (contains? (:map result) :owner_id)))
        (is (not (contains? (:map result) :actor_id))))

      (testing "the part appears once, with both versions, clinical fields kept"
        (is (= 1 (count (:parts result))))
        (let [p (first (:parts result))
              v (first (:versions p))]
          (is (= (:id part) (:id p)))
          (is (= ["v1" "v2"] (map :label (:versions p))))
          (is (= "secret note" (:notes v)) "clinical field is included")
          (testing "versions surface valid_from/valid_to and drop id/map_id/internal cols"
            (is (contains? v :valid_from))
            (is (not (contains? v :id)))
            (is (not (contains? v :map_id)))
            (is (not (contains? v :actor_id)))
            (is (not (contains? v :sys_period))))))

      (testing "relationships section is present (empty here)"
        (is (= [] (:relationships result))))

      (testing "sessions section is present (empty here)"
        (is (= [] (:sessions result)))))))

(deftest test-export-includes-sessions
  (testing "the export carries Sessions — ordinal, trigger, anchor — and
            their activated Part links (GDPR Art. 15/20, ADR-0014)"
    (let [user    (create-test-user!)
          the-map (create-test-map! (:id user))
          part    (part-row (:id the-map))
          _       (bt/insert! db/datasource :parts part {:actor-id (:id user)})
          s1      (session/create! (:id the-map) (:id user))
          _       (session/update-trigger! (:id s1) (:id the-map)
                                           "conflict with the client's mother"
                                           (:id user))
          _       (session/set-activation! (:id s1) (:id the-map)
                                           (:id part) (:id user))
          _       (session/create! (:id the-map) (:id user))
          result  (export/export-map db/datasource (:id the-map))
          [e1 e2] (:sessions result)]

      (testing "both Sessions export in anchor order"
        (is (= 2 (count (:sessions result))))
        (is (= [1 2] (map :ordinal (:sessions result)))))

      (testing "the trigger text — clinical data — is included"
        (is (= "conflict with the client's mother" (:trigger e1)))
        (is (nil? (:trigger e2))))

      (testing "the anchor instant is the Session's valid-time place"
        (is (some? (:anchor_valid_at e1))))

      (testing "the activation link is included"
        (is (= (:id part) (:activated_part_id e1)))
        (is (nil? (:activated_part_id e2))))

      (testing "map_id is dropped — the export is scoped to one Map"
        (is (not (contains? e1 :map_id)))))))
