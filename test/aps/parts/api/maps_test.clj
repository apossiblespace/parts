(ns aps.parts.api.maps-test
  (:require
   [aps.parts.api.maps :as api]
   [aps.parts.api.maps-events :as events]
   [aps.parts.db :as db]
   [aps.parts.entity.map :as parts-map]
   [aps.parts.entity.part :as part]
   [aps.parts.helpers.utils :refer [with-test-db create-test-user!
                                    create-test-map!]]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :once with-test-db)

(defn- make-request
  "Helper to create authenticated request map"
  [user & {:keys [params body headers]}]
  {:identity    {:sub (:id user)}
   :parameters  {:path params}
   :body-params body
   :headers     (or headers {})})

(deftest test-list-maps
  (testing "returns maps for authenticated user"
    (let [user     (create-test-user!)
          _        (parts-map/create! {:title "Test Map" :owner_id (:id user)} (:id user))
          request  (make-request user)
          response (api/list-maps request)]
      (is (= 200 (:status response)))
      (is (= 1 (count (:body response))))
      (is (= (:id user) (-> response :body first :owner_id))))))

(deftest test-create-map
  (testing "creates map for authenticated user"
    (let [user     (create-test-user!)
          request  (make-request user :body {:title "New Map"})
          response (api/create-map request)]
      (is (= 201 (:status response)))
      (is (= "New Map" (-> response :body :title)))
      (is (= (:id user) (-> response :body :owner_id))))))

(deftest test-get-map
  (testing "returns the map (non-owner case is `wrap-map-access`'s job)"
    (let [user     (create-test-user!)
          the-map  (parts-map/create! {:title "Test" :owner_id (:id user)} (:id user))
          request  (make-request user :params {:id (:id the-map)})
          response (api/get-map request)]
      (is (= 200 (:status response)))
      (is (= (:id the-map) (-> response :body :id)))
      (is (vector? (-> response :body :parts)))
      (is (vector? (-> response :body :relationships))))))

(deftest test-update-map
  (testing "updates the map (non-owner case is `wrap-map-access`'s job)"
    (let [user     (create-test-user!)
          the-map  (parts-map/create! {:title "Test" :owner_id (:id user)} (:id user))
          request  (make-request user
                                 :params {:id (:id the-map)}
                                 :body {:title "Updated"})
          response (api/update-map request)]
      (is (= 200 (:status response)))
      (is (= "Updated" (-> response :body :title))))))

(deftest test-delete-map
  (testing "deletes the map (non-owner case is `wrap-map-access`'s job)"
    (let [user     (create-test-user!)
          the-map  (parts-map/create! {:title "Test" :owner_id (:id user)} (:id user))
          request  (make-request user :params {:id (:id the-map)})
          response (api/delete-map request)]
      (is (= 204 (:status response)))
      (is (nil? (:body response))))))

(deftest test-batch-rollback-when-one-change-fails
  (testing "all-or-nothing batch: one bad change rolls back the rest"
    (let [user    (create-test-user!)
          the-map (parts-map/create! {:title "Rollback Test" :owner_id (:id user)} (:id user))
          part-id (random-uuid)
          _       (part/create! {:id         part-id
                                 :map_id     (:id the-map)
                                 :type       "manager"
                                 :label      "Original"
                                 :position_x 0
                                 :position_y 0}
                                (:id user))
          ;; A batch that renames the part, then tries to update a nonexistent
          ;; part. The second change throws :not-found and must roll back the
          ;; first.
          batch   [{:entity "part" :type "update" :id part-id :data {:label "Should NOT stick"}}
                   {:entity "part" :type "update" :id (random-uuid) :data {:label "Doesn't matter"}}]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (events/apply-changes!
                    db/datasource
                    {:map-id   (:id the-map)
                     :actor-id (:id user)
                     :changes  batch})))
      (testing "the earlier change in the batch was rolled back"
        (is (= "Original" (:label (part/fetch part-id)))
            "label should not have been renamed because the batch failed")))))

(deftest test-parse-rejects-invalid-change-before-transaction
  (testing "a structurally invalid change is rejected as a batch failure, before any DB work"
    (let [user    (create-test-user!)
          the-map (parts-map/create! {:title "Parse Test" :owner_id (:id user)} (:id user))
          part-id (random-uuid)
          _       (part/create! {:id         part-id
                                 :map_id     (:id the-map)
                                 :type       "manager"
                                 :label      "Original"
                                 :position_x 0
                                 :position_y 0}
                                (:id user))
          ;; The second change has an unknown :type — `parse` rejects the whole
          ;; batch before `apply-changes!` opens a transaction.
          bad     {:entity "part" :type "teleport" :id part-id :data {}}
          batch   [{:entity "part" :type "update" :id part-id :data {:label "Should NOT stick"}}
                   bad]]
      (try
        (events/apply-changes! db/datasource
                               {:map-id   (:id the-map)
                                :actor-id (:id user)
                                :changes  batch})
        (is false "expected apply-changes! to throw")
        (catch clojure.lang.ExceptionInfo e
          (let [data (ex-data e)]
            (is (= :batch-failure (:type data)))
            (is (= :validation (:cause-type data)))
            (is (= bad (:failing-change data))))))
      (testing "the valid earlier change was never applied"
        (is (= "Original" (:label (part/fetch part-id))))))))

(deftest test-render-pdf
  (testing "returns a PDF body with Content-Type application/pdf, named after the Map"
    (let [user     (create-test-user!)
          the-map  (create-test-map! (:id user) "Smoke Map")
          response (api/render-pdf (make-request user :params {:id (:id the-map)}))]
      (is (= 200 (:status response)))
      (is (= "application/pdf" (get-in response [:headers "Content-Type"])))
      (is (str/includes? (get-in response [:headers "Content-Disposition"])
                         "Smoke Map.pdf"))
      ;; PDF file signature is the four ASCII bytes "%PDF" at offset 0.
      (let [^java.io.InputStream body (:body response)
            buf                       (byte-array 4)]
        (.read body buf 0 4)
        (is (= "%PDF" (String. buf "UTF-8"))))))
  (testing "If-None-Match matching the current ETag returns 304 — skipping the FOP transcode"
    (let [user        (create-test-user!)
          the-map     (create-test-map! (:id user))
          first-resp  (api/render-pdf (make-request user :params {:id (:id the-map)}))
          etag        (get-in first-resp [:headers "ETag"])
          second-resp (api/render-pdf
                       (make-request user
                                     :params  {:id (:id the-map)}
                                     :headers {"if-none-match" etag}))]
      (is (= 304 (:status second-resp)))
      (is (nil? (:body second-resp)))
      (is (= etag (get-in second-resp [:headers "ETag"])))))
  (testing "a title containing a quote does not break the Content-Disposition header"
    (let [user     (create-test-user!)
          the-map  (create-test-map! (:id user) "Evil \" Title")
          response (api/render-pdf (make-request user :params {:id (:id the-map)}))
          disp     (get-in response [:headers "Content-Disposition"])]
      ;; The header has exactly the wrapping `filename="…"` quotes — the
      ;; raw `"` from the title is stripped, not allowed to inject.
      (is (= 2 (count (re-seq #"\"" disp)))))))

(deftest test-preview-svg
  (testing "returns an SVG body with Content-Type image/svg+xml and an ETag header"
    (let [user     (create-test-user!)
          the-map  (create-test-map! (:id user))
          response (api/preview-svg (make-request user :params {:id (:id the-map)}))]
      (is (= 200 (:status response)))
      (is (str/starts-with? (:body response) "<svg"))
      (is (= "image/svg+xml" (get-in response [:headers "Content-Type"])))
      (is (string? (get-in response [:headers "ETag"])))))
  (testing "a request whose If-None-Match matches the current ETag gets 304 with no body"
    (let [user        (create-test-user!)
          the-map     (create-test-map! (:id user))
          first-resp  (api/preview-svg (make-request user :params {:id (:id the-map)}))
          etag        (get-in first-resp [:headers "ETag"])
          second-resp (api/preview-svg
                       (make-request user
                                     :params  {:id (:id the-map)}
                                     :headers {"if-none-match" etag}))]
      (is (= 304 (:status second-resp)))
      (is (nil? (:body second-resp)))
      (is (= etag (get-in second-resp [:headers "ETag"]))))))
