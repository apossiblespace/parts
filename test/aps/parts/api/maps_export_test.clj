(ns aps.parts.api.maps-export-test
  "Handler-level test for GET /api/maps/:id/export.json — it returns the export
   builder's output as a downloadable JSON attachment. Owner-scoping (AC #4) is
   the `wrap-map-access` route middleware's job, exercised where that middleware
   is tested; here we prove the handler shapes the download correctly."
  (:require
   [aps.parts.api.maps :as api.maps]
   [aps.parts.db :as db]
   [aps.parts.db.bitemporal :as bt]
   [aps.parts.helpers.utils :refer [create-test-map! create-test-user! with-test-db]]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [jsonista.core :as json]))

(use-fixtures :each with-test-db)

(deftest test-export-json-handler
  (testing "the handler returns the Map export as a JSON download"
    (let [user     (create-test-user!)
          the-map  (create-test-map! (:id user))
          _        (bt/insert! db/datasource :parts
                               {:id         (random-uuid)
                                :map_id     (db/->uuid (:id the-map))
                                :type       "manager"
                                :label      "Inner Critic"
                                :notes      "a sensitive note"
                                :position_x 0
                                :position_y 0}
                               {:actor-id (:id user)})
          request  {:parameters {:path {:id (:id the-map)}}}
          response (api.maps/export-json request)]

      (testing "200 JSON attachment"
        (is (= 200 (:status response)))
        (is (= "application/json" (get-in response [:headers "Content-Type"])))
        (is (re-find #"attachment; filename="
                     (get-in response [:headers "Content-Disposition"]))))

      (testing "the body is valid JSON in the ADR-0010 shape"
        (let [parsed (json/read-value (:body response)
                                      json/keyword-keys-object-mapper)]
          (is (= "1" (:format_version parsed)))
          (is (= 1 (count (:parts parsed))))
          (is (= "Inner Critic"
                 (-> parsed :parts first :versions first :label)))
          (is (= "a sensitive note"
                 (-> parsed :parts first :versions first :notes))
              "clinical field present in the JSON"))))))
