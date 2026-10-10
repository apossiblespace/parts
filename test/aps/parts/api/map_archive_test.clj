(ns aps.parts.api.map-archive-test
  "Archiving through the full middleware stack: the archive endpoints, and
   the rule that an archived Map refuses every change but still serves
   every read."
  (:require
   [aps.parts.api.maps :as api-maps]
   [aps.parts.db :as db]
   [aps.parts.entity.map :as parts-map]
   [aps.parts.helpers.browser :refer [api browse decode sign-in]]
   [aps.parts.helpers.utils :refer [create-test-map! create-test-user! with-test-db]]
   [aps.parts.server :as server]
   [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :once with-test-db)

(defn- updated-at [user-id map-id]
  (:updated_at (first (filter #(= map-id (:id %)) (parts-map/index user-id)))))

(deftest test-archive-and-unarchive
  (let [app            (server/app)
        pw             "correct horse battery"
        user           (create-test-user! {:password pw :password_confirmation pw})
        the-map        (create-test-map! (:id user))
        id             (:id the-map)
        path           (str "/api/maps/" id)
        [cookie token] (sign-in app user pw)
        call           (fn [method suffix & [body]]
                         (first (browse app (api method (str path suffix) token body) cookie)))
        before         (updated-at (:id user) id)]

    (testing "archiving sets the archive time"
      (let [response (call :put "/archive")]
        (is (= 200 (:status response)))
        (is (some? (:archived_at (decode response))))))

    (testing "archiving again keeps the first archive time"
      (let [first-time (:archived_at (parts-map/fetch-identity id))]
        (call :put "/archive")
        (is (= first-time (:archived_at (parts-map/fetch-identity id))))))

    (testing "archiving does not change when the Map was last updated"
      (is (= before (updated-at (:id user) id))))

    (testing "the audit log records the archive"
      (is (pos? (:n (db/query-one ["SELECT count(*) AS n FROM audit_log
                                     WHERE table_name = 'maps' AND operation = 'U'
                                       AND row_pk->>'id' = ?" (str id)])))))

    (testing "every change to the archived Map is refused with 409"
      (let [session-id (str (random-uuid))]
        (doseq [[method suffix body] [[:put "" {:title "Renamed"}]
                                      [:post "/changes" []]
                                      [:post "/sessions" {}]
                                      [:put (str "/sessions/" session-id) {:trigger "x"}]
                                      [:delete (str "/sessions/" session-id)]
                                      [:put (str "/sessions/" session-id "/activation") {:part_id "x"}]
                                      [:delete (str "/sessions/" session-id "/activation")]]]
          (let [response (call method suffix body)]
            (is (= 409 (:status response)) (str method " " suffix))
            (is (re-find #"archived" (str (:error (decode response)))) (str method " " suffix))))))

    (testing "every read of the archived Map still works"
      (doseq [suffix ["" "/sessions" "/preview.svg" "/export.json"]]
        (is (= 200 (:status (call :get suffix))) suffix))
      (is (some? (:archived_at (decode (call :get ""))))
          "the Map carries its archive time"))

    (testing "unarchiving clears the archive time, and changes work again"
      (let [response (call :delete "/archive")]
        (is (= 200 (:status response)))
        (is (nil? (:archived_at (decode response)))))
      (is (= 200 (:status (call :put "" {:title "Renamed"})))))))

(deftest test-set-archived-on-a-vanished-map
  (testing "a Map deleted after the access check answers 404, not an empty 200"
    (with-redefs [parts-map/set-archived! (constantly nil)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not found"
                            (api-maps/set-archived {:request-method :put
                                                    :identity       {:sub "u1"}
                                                    :parameters     {:path {:id "m1"}}}))))))
