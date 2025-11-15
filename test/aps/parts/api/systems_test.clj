(ns aps.parts.api.systems-test
  (:require
   [aps.parts.api.systems :as api]
   [aps.parts.entity.system :as system]
   [aps.parts.helpers.utils :refer [with-test-db register-test-user]]
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
    (let [user     (register-test-user)
          _        (system/create! {:title "Test System" :owner_id (:id user)})
          request  (make-request user)
          response (api/list-systems request)]
      (is (= 200 (:status response)))
      (is (= 1 (count (:body response))))
      (is (= (:id user) (-> response :body first :owner_id))))))

(deftest test-create-system
  (testing "creates system for authenticated user"
    (let [user     (register-test-user)
          request  (make-request user :body {:title "New System"})
          response (api/create-system request)]
      (is (= 201 (:status response)))
      (is (= "New System" (-> response :body :title)))
      (is (= (:id user) (-> response :body :owner_id))))))

(deftest test-get-system
  (let [user       (register-test-user)
        other-user (register-test-user)
        system     (system/create! {:title "Test" :owner_id (:id user)})]

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
  (let [user       (register-test-user)
        other-user (register-test-user)
        system     (system/create! {:title "Test" :owner_id (:id user)})]

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
  (let [user       (register-test-user)
        other-user (register-test-user)
        system     (system/create! {:title "Test" :owner_id (:id user)})]

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

(deftest test-export-pdf
  (testing "returns not implemented"
    (let [user     (register-test-user)
          system   (system/create! {:title "Test" :owner_id (:id user)})
          request  (make-request user :params {:id (:id system)})
          response (api/export-pdf request)]
      (is (= 501 (:status response)))
      (is (= "Not implemented" (-> response :body :error))))))
