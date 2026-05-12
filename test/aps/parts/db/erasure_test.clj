(ns aps.parts.db.erasure-test
  "Covers the right-to-erasure lifecycle:
     - `request-deletion!` marks the account
     - `cancel-deletion!` reverses it within the window
     - `pending-deletions` surfaces accounts past the 30-day grace
     - `purge-account!` hard-deletes data + pseudonymizes the audit trail
     - the tombstone user itself is protected"
  (:require
   [aps.parts.db :as db]
   [aps.parts.db.bitemporal :as bt]
   [aps.parts.db.erasure :as erasure]
   [aps.parts.helpers.utils :refer [create-test-system! create-test-user! with-test-db]]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]))

(use-fixtures :each with-test-db)

(defn- fetch-user-row [user-id]
  (jdbc/execute-one!
   db/datasource
   ["SELECT deletion_requested_at, deletion_completed_at FROM users WHERE id = ?::uuid"
    (str user-id)]
   {:builder-fn rs/as-unqualified-maps}))

(defn- backdate-request! [user-id days]
  ;; Push deletion_requested_at into the past so pending-deletions picks it up.
  (jdbc/execute!
   db/datasource
   [(str "UPDATE users SET deletion_requested_at = now() - interval '" days " days'
          WHERE id = ?::uuid")
    (str user-id)]))

(deftest test-request-deletion-marks-the-account
  (let [user (create-test-user!)]
    (testing "before request, no marker is set"
      (is (nil? (:deletion_requested_at (fetch-user-row (:id user))))))

    (erasure/request-deletion! db/datasource (:id user))

    (testing "after request, deletion_requested_at is populated"
      (is (some? (:deletion_requested_at (fetch-user-row (:id user))))))

    (testing "completion timestamp stays nil until the purge runs"
      (is (nil? (:deletion_completed_at (fetch-user-row (:id user))))))))

(deftest test-request-is-idempotent
  (let [user (create-test-user!)]
    (erasure/request-deletion! db/datasource (:id user))
    (let [first-ts (:deletion_requested_at (fetch-user-row (:id user)))]
      (Thread/sleep 50)
      (erasure/request-deletion! db/datasource (:id user))
      (testing "re-requesting deletion does not bump the timestamp"
        (is (= first-ts (:deletion_requested_at (fetch-user-row (:id user)))))))))

(deftest test-cancel-deletion-clears-the-marker
  (let [user (create-test-user!)]
    (erasure/request-deletion! db/datasource (:id user))
    (is (some? (:deletion_requested_at (fetch-user-row (:id user)))))

    (erasure/cancel-deletion! db/datasource (:id user))

    (testing "cancel nulls deletion_requested_at"
      (is (nil? (:deletion_requested_at (fetch-user-row (:id user))))))))

(deftest test-pending-deletions-respects-the-grace-window
  (let [fresh-user (create-test-user!)
        stale-user (create-test-user!)]
    (erasure/request-deletion! db/datasource (:id fresh-user))
    (erasure/request-deletion! db/datasource (:id stale-user))
    (backdate-request! (:id stale-user) 31)

    (let [pending (set (erasure/pending-deletions db/datasource))]
      (testing "the fresh request is still within the window — excluded"
        (is (not (contains? pending (:id fresh-user)))))
      (testing "the stale request is past the window — included"
        (is (contains? pending (:id stale-user)))))))

(deftest test-purge-account-hard-deletes-owned-data
  (let [user    (create-test-user!)
        system  (create-test-system! (:id user) "Doomed")
        part-id (random-uuid)]
    (bt/insert! db/datasource :parts
                {:id         part-id
                 :system_id  (:id system)
                 :type       "manager"
                 :label      "Goodbye"
                 :position_x 0            :position_y 0}
                {:actor-id (:id user)})

    (erasure/purge-account! db/datasource (:id user))

    (let [parts-left   (jdbc/execute-one!
                        db/datasource
                        ["SELECT count(*) AS c FROM parts WHERE id = ?::uuid" (str part-id)]
                        {:builder-fn rs/as-unqualified-maps})
          systems-left (jdbc/execute-one!
                        db/datasource
                        ["SELECT count(*) AS c FROM systems WHERE owner_id = ?::uuid"
                         (str (:id user))]
                        {:builder-fn rs/as-unqualified-maps})
          users-left   (jdbc/execute-one!
                        db/datasource
                        ["SELECT count(*) AS c FROM users WHERE id = ?::uuid"
                         (str (:id user))]
                        {:builder-fn rs/as-unqualified-maps})]
      (testing "all parts physically gone"
        (is (zero? (:c parts-left))))
      (testing "all systems physically gone"
        (is (zero? (:c systems-left))))
      (testing "user row physically gone"
        (is (zero? (:c users-left)))))))

(deftest test-purge-pseudonymizes-audit-trail
  (let [user   (create-test-user!)
        system (create-test-system! (:id user) "Pre-purge")]
    ;; Generate some audit history attributed to the user.
    (bt/insert! db/datasource :parts
                {:id         (random-uuid)
                 :system_id  (:id system)
                 :type       "manager"
                 :label      "Audited"
                 :position_x 0             :position_y 0}
                {:actor-id (:id user)})

    (let [pre-purge-rows (jdbc/execute-one!
                          db/datasource
                          ["SELECT count(*) AS c FROM audit_log WHERE actor_id = ?::uuid"
                           (str (:id user))]
                          {:builder-fn rs/as-unqualified-maps})]
      (is (pos? (:c pre-purge-rows))))

    (erasure/purge-account! db/datasource (:id user))

    (let [user-rows      (jdbc/execute-one!
                          db/datasource
                          ["SELECT count(*) AS c FROM audit_log WHERE actor_id = ?::uuid"
                           (str (:id user))]
                          {:builder-fn rs/as-unqualified-maps})
          tombstone-rows (jdbc/execute-one!
                          db/datasource
                          ["SELECT count(*) AS c FROM audit_log
                            WHERE actor_id = ?::uuid"
                           (str erasure/tombstone-id)]
                          {:builder-fn rs/as-unqualified-maps})]
      (testing "no audit_log row references the deleted user"
        (is (zero? (:c user-rows))))
      (testing "the audit trail survives, reassigned to the tombstone"
        (is (pos? (:c tombstone-rows)))))))

(deftest test-purge-refuses-to-delete-the-tombstone
  (testing "guard prevents accidentally wiping the schema's anchor user"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Refusing to purge the tombstone user"
         (erasure/purge-account! db/datasource erasure/tombstone-id)))))
