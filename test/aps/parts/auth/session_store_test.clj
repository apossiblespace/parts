(ns aps.parts.auth.session-store-test
  (:require
   [aps.parts.auth.session-store :as ss]
   [aps.parts.db :as db]
   [aps.parts.helpers.utils :refer [create-test-user! with-test-db]]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]
   [ring.middleware.session.store :as store]))

(use-fixtures :once with-test-db)

(def ^:private day (* 24 60 60))

(deftest test-roundtrip
  (let [s    (ss/db-store db/datasource day)
        user (create-test-user!)
        data {:identity                                        {:sub (str (:id user))}
              :ring.middleware.anti-forgery/anti-forgery-token "tok-123"}
        key  (store/write-session s nil data)]
    (testing "write mints an opaque id and read returns the exact map back"
      (is (uuid? (parse-uuid key)))
      (is (= data (store/read-session s key))))
    (testing "a rewrite under the same key updates the data"
      (store/write-session s key (assoc data :extra 1))
      (is (= 1 (:extra (store/read-session s key)))))
    (testing "delete revokes server-side; the old key reads as no session"
      (is (nil? (store/delete-session s key)))
      (is (= {} (store/read-session s key))))))

(deftest test-garbage-keys-read-as-no-session
  (let [s (ss/db-store db/datasource day)]
    (is (= {} (store/read-session s nil)))
    (is (= {} (store/read-session s "not-a-uuid")))
    (is (= {} (store/read-session s (str (random-uuid)))) "unknown id")))

(deftest test-absolute-expiry
  (let [s-dead (ss/db-store db/datasource 0)
        s-live (ss/db-store db/datasource day)
        key    (store/write-session s-dead nil {:x 1})]
    (testing "an expired session reads as no session"
      (is (= {} (store/read-session s-dead key))))
    (testing "a live write does NOT extend an existing deadline"
      (let [k        (store/write-session s-live nil {:x 1})
            deadline (fn []
                       (:expires_at
                        (jdbc/execute-one!
                         db/datasource
                         ["SELECT expires_at FROM auth_sessions WHERE id = ?"
                          (parse-uuid k)]
                         {:builder-fn rs/as-unqualified-maps})))
            before   (deadline)]
        (store/write-session s-live k {:x 2})
        (is (= before (deadline)))))
    (testing "writing to an expired id starts it afresh"
      (store/write-session s-live key {:x 2})
      (is (= {:x 2} (store/read-session s-live key))))))

(deftest test-revoke-for-user
  (let [s     (ss/db-store db/datasource day)
        alice (create-test-user!)
        bob   (create-test-user!)
        mk    (fn [u] (store/write-session s nil {:identity {:sub (str (:id u))}}))
        a1    (mk alice)
        a2    (mk alice)
        b1    (mk bob)]
    (is (= 2 (ss/revoke-for-user! db/datasource (:id alice)))
        "both of alice's sessions are revoked")
    (is (= {} (store/read-session s a1)))
    (is (= {} (store/read-session s a2)))
    (is (seq (store/read-session s b1)) "bob's session is untouched")))

(deftest test-account-deletion-cascades-sessions
  (let [s    (ss/db-store db/datasource day)
        user (create-test-user!)
        key  (store/write-session s nil {:identity {:sub (str (:id user))}})]
    (jdbc/execute! db/datasource
                   ["DELETE FROM users WHERE id = ?" (db/->uuid (:id user))])
    (is (= {} (store/read-session s key))
        "the users FK cascade removed the session row")))

(deftest test-expired-sweep
  (let [s-dead (ss/db-store db/datasource 0)
        s-live (ss/db-store db/datasource day)
        dead   (store/write-session s-dead nil {:x 1})
        live   (store/write-session s-live nil {:x 1})]
    (is (pos? (ss/delete-expired! db/datasource)))
    (is (zero? (:c (jdbc/execute-one!
                    db/datasource
                    ["SELECT count(*) AS c FROM auth_sessions WHERE id = ?"
                     (parse-uuid dead)]
                    {:builder-fn rs/as-unqualified-maps}))))
    (is (seq (store/read-session s-live live)) "live sessions survive the sweep")))
