(ns aps.parts.api.systems-test
  (:require
   [aps.parts.api.systems :as api]
   [aps.parts.api.systems-events :as events]
   [aps.parts.db :as db]
   [aps.parts.entity.part :as part]
   [aps.parts.entity.system :as system]
   [aps.parts.helpers.utils :refer [with-test-db create-test-user!]]
   [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :once with-test-db)

(defn- make-request
  "Helper to create authenticated request map"
  [user & {:keys [params body]}]
  {:identity    {:sub (:id user)}
   :parameters  {:path params}
   :body-params body})

(deftest test-list-systems
  (testing "returns systems for authenticated user"
    (let [user     (create-test-user!)
          _        (system/create! {:title "Test System" :owner_id (:id user)} (:id user))
          request  (make-request user)
          response (api/list-systems request)]
      (is (= 200 (:status response)))
      (is (= 1 (count (:body response))))
      (is (= (:id user) (-> response :body first :owner_id))))))

(deftest test-create-system
  (testing "creates system for authenticated user"
    (let [user     (create-test-user!)
          request  (make-request user :body {:title "New System"})
          response (api/create-system request)]
      (is (= 201 (:status response)))
      (is (= "New System" (-> response :body :title)))
      (is (= (:id user) (-> response :body :owner_id))))))

(deftest test-get-system
  (let [user       (create-test-user!)
        other-user (create-test-user!)
        system     (system/create! {:title "Test" :owner_id (:id user)} (:id user))]

    (testing "returns system for owner"
      (let [request  (make-request user :params {:id (:id system)})
            response (api/get-system request)]
        (is (= 200 (:status response)))
        (is (= (:id system) (-> response :body :id)))
        (is (vector? (-> response :body :parts)))
        (is (vector? (-> response :body :relationships)))))

    (testing "returns 403 for non-owner"
      (let [request  (make-request other-user :params {:id (:id system)})
            response (api/get-system request)]
        (is (= 403 (:status response)))
        (is (= "Not authorized" (-> response :body :error)))))))

(deftest test-update-system
  (let [user       (create-test-user!)
        other-user (create-test-user!)
        system     (system/create! {:title "Test" :owner_id (:id user)} (:id user))]

    (testing "updates system for owner"
      (let [request  (make-request user
                                   :params {:id (:id system)}
                                   :body {:title "Updated"})
            response (api/update-system request)]
        (is (= 200 (:status response)))
        (is (= "Updated" (-> response :body :title)))))

    (testing "returns 403 for non-owner"
      (let [request  (make-request other-user
                                   :params {:id (:id system)}
                                   :body {:title "Updated"})
            response (api/update-system request)]
        (is (= 403 (:status response)))
        (is (= "Not authorized" (-> response :body :error)))))))

(deftest test-delete-system
  (let [user       (create-test-user!)
        other-user (create-test-user!)
        system     (system/create! {:title "Test" :owner_id (:id user)} (:id user))]

    (testing "returns 403 for non-owner"
      (let [request  (make-request other-user :params {:id (:id system)})
            response (api/delete-system request)]
        (is (= 403 (:status response)))
        (is (= "Not authorized" (-> response :body :error)))))

    (testing "deletes system for owner"
      (let [request  (make-request user :params {:id (:id system)})
            response (api/delete-system request)]
        (is (= 204 (:status response)))
        (is (nil? (:body response)))))))

(deftest test-batch-rollback-when-one-change-fails
  (testing "all-or-nothing batch: one bad change rolls back the rest"
    (let [user    (create-test-user!)
          sys     (system/create! {:title "Rollback Test" :owner_id (:id user)} (:id user))
          part-id (random-uuid)
          _       (part/create! {:id         part-id
                                 :system_id  (:id sys)
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
                    {:system-id (:id sys)
                     :actor-id  (:id user)
                     :changes   batch})))
      (testing "the earlier change in the batch was rolled back"
        (is (= "Original" (:label (part/fetch part-id)))
            "label should not have been renamed because the batch failed")))))

(deftest test-parse-rejects-invalid-change-before-transaction
  (testing "a structurally invalid change is rejected as a batch failure, before any DB work"
    (let [user    (create-test-user!)
          sys     (system/create! {:title "Parse Test" :owner_id (:id user)} (:id user))
          part-id (random-uuid)
          _       (part/create! {:id         part-id
                                 :system_id  (:id sys)
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
                               {:system-id (:id sys)
                                :actor-id  (:id user)
                                :changes   batch})
        (is false "expected apply-changes! to throw")
        (catch clojure.lang.ExceptionInfo e
          (let [data (ex-data e)]
            (is (= :batch-failure (:type data)))
            (is (= :validation (:cause-type data)))
            (is (= bad (:failing-change data))))))
      (testing "the valid earlier change was never applied"
        (is (= "Original" (:label (part/fetch part-id))))))))

(deftest test-export-pdf
  (testing "returns not implemented"
    (let [user     (create-test-user!)
          system   (system/create! {:title "Test" :owner_id (:id user)} (:id user))
          request  (make-request user :params {:id (:id system)})
          response (api/export-pdf request)]
      (is (= 501 (:status response)))
      (is (= "Not implemented" (-> response :body :error))))))
