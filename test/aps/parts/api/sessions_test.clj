(ns aps.parts.api.sessions-test
  (:require
   [aps.parts.api.sessions :as api]
   [aps.parts.entity.map :as parts-map]
   [aps.parts.entity.part :as part]
   [aps.parts.helpers.utils :refer [with-test-db create-test-user!]]
   [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :once with-test-db)

(defn- make-request
  "Helper to create authenticated request map"
  [user & {:keys [params body]}]
  {:identity    {:sub (:id user)}
   :parameters  {:path params}
   :body-params body})

(deftest test-session-endpoints
  (let [user    (create-test-user!)
        the-map (parts-map/create! {:title "Test" :owner_id (:id user)}
                                   (:id user))
        map-id  (:id the-map)]

    (testing "POST creates a Session with a server-side anchor
              (non-owner case is `wrap-map-access`'s job)"
      (let [response (api/create-session
                      (make-request user :params {:id map-id}))]
        (is (= 201 (:status response)))
        (is (= 1 (-> response :body :ordinal)))
        (is (some? (-> response :body :anchor_valid_at)))))

    (testing "GET lists Sessions ordered by anchor, with activation ids"
      (api/create-session (make-request user :params {:id map-id}))
      (let [response (api/list-sessions
                      (make-request user :params {:id map-id}))]
        (is (= 200 (:status response)))
        (is (= [1 2] (mapv :ordinal (:body response))))
        (is (contains? (first (:body response)) :activated_part_id))))

    (testing "PUT updates the active Session's trigger"
      (let [latest   (last (:body (api/list-sessions
                                   (make-request user :params {:id map-id}))))
            response (api/update-trigger
                      (make-request user
                                    :params {:id map-id :session-id (:id latest)}
                                    :body {:trigger "conflict at work"}))]
        (is (= 200 (:status response)))
        (is (= "conflict at work" (-> response :body :trigger)))))

    (testing "PUT/DELETE manage the activation link"
      (let [p      (part/create! {:map_id     map-id
                                  :type       "exile"
                                  :label      "Kid"
                                  :position_x 0
                                  :position_y 0}
                                 (:id user))
            latest (last (:body (api/list-sessions
                                 (make-request user :params {:id map-id}))))
            put    (api/set-activation
                    (make-request user
                                  :params {:id map-id :session-id (:id latest)}
                                  :body {:part_id (:id p)}))
            listed (api/list-sessions (make-request user :params {:id map-id}))]
        (is (= 200 (:status put)))
        (is (= (:id p) (:activated_part_id (last (:body listed)))))
        (is (= 204 (:status (api/clear-activation
                             (make-request user
                                           :params {:id         map-id
                                                    :session-id (:id latest)})))))))

    (testing "DELETE removes a latest-and-empty Session"
      ;; The prior latest gained content above — only a fresh Session is
      ;; deletable.
      (let [s3       (:body (api/create-session
                             (make-request user :params {:id map-id})))
            response (api/delete-session
                      (make-request user
                                    :params {:id         map-id
                                             :session-id (:id s3)}))]
        (is (= 204 (:status response)))
        (is (= [1 2] (mapv :ordinal
                           (:body (api/list-sessions
                                   (make-request user :params {:id map-id}))))))))

    (testing "a Session id from another Map reads as not-found even with
              access to one's own Map (entity-level scoping)"
      (let [other-map (parts-map/create! {:title "Other" :owner_id (:id user)}
                                         (:id user))
            other-s   (:body (api/create-session
                              (make-request user :params {:id (:id other-map)})))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not found"
                              (api/update-trigger
                               (make-request user
                                             :params {:id         map-id
                                                      :session-id (:id other-s)}
                                             :body {:trigger "x"}))))))))
