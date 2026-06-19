(ns aps.parts.stats-test
  (:require
   [aps.parts.billing :as billing]
   [aps.parts.db :as db]
   [aps.parts.db.erasure :as erasure]
   [aps.parts.entity.map :as emap]
   [aps.parts.entity.part :as part]
   [aps.parts.entity.relationship :as relationship]
   [aps.parts.helpers.utils :refer [create-test-map! create-test-user!
                                    silently with-test-db]]
   [aps.parts.stats :as stats]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [next.jdbc :as jdbc])
  (:import
   (java.time OffsetDateTime)))

(use-fixtures :each with-test-db)

(defn- audit!
  "Insert an audit_log row attributed to `actor-id` at `occurred-at`. The
   stats activity signal reads only actor_id + occurred_at, so the rest is
   filler that satisfies the table's constraints."
  [actor-id ^OffsetDateTime occurred-at]
  (jdbc/execute!
   db/datasource
   ["INSERT INTO audit_log (actor_id, occurred_at, table_name, operation, row_pk)
     VALUES (?::uuid, ?, 'parts', 'U', '{}'::jsonb)"
    (str actor-id) occurred-at]))

(defn- add-part! [map-id user-id]
  (part/create! {:map_id map-id} user-id))

(deftest test-user-stats-basics-and-counts
  (let [user    (create-test-user! {:is_founding_circle true})
        the-map (create-test-map! (:id user))
        p1      (add-part! (:id the-map) (:id user))
        p2      (add-part! (:id the-map) (:id user))
        _       (relationship/create! {:map_id    (:id the-map)
                                       :source_id (:id p1)
                                       :target_id (:id p2)
                                       :type      "protective"}
                                      (:id user))
        result  (silently #(stats/user-stats! (:email user)))]
    (testing "carries account basics"
      (is (= (:id user) (:id result)))
      (is (= (:email user) (:email result)))
      (is (= (:display_name user) (:display_name result)))
      (is (true? (:is_founding_circle result)))
      (is (some? (:created_at result))))

    (testing "carries billing standing (reused from billing)"
      (is (= :never-paid (-> result :billing :status))))

    (testing "counts current maps / parts / relationships the user owns"
      (is (= {:maps 1 :parts 2 :relationships 1} (:counts result))))))

(deftest test-user-stats-excludes-deleted-map-children
  (let [user     (create-test-user!)
        live-map (create-test-map! (:id user))
        _        (add-part! (:id live-map) (:id user))
        gone-map (create-test-map! (:id user))
        _        (add-part! (:id gone-map) (:id user))
        _        (emap/delete! (:id gone-map) (:id user))
        result   (silently #(stats/user-stats! (:id user)))]
    (testing "a soft-deleted map and its parts drop out of the counts"
      (is (= {:maps 1 :parts 1 :relationships 0} (:counts result))))))

(deftest test-user-stats-last-active
  (let [user (create-test-user!)
        now  (OffsetDateTime/now)]
    (testing "nil when the user has never made a change"
      (is (nil? (:last_active (silently #(stats/user-stats! (:id user)))))))

    (testing "the most recent audit entry by this actor"
      (audit! (:id user) (.minusDays now 3))
      (audit! (:id user) (.minusHours now 2))
      (audit! (:id user) (.minusDays now 9))
      (let [latest (:last_active (silently #(stats/user-stats! (:id user))))]
        (is (some? latest))
        ;; within a few seconds of (now - 2h)
        (is (< (Math/abs (- (.toEpochSecond latest)
                            (.toEpochSecond (.minusHours now 2))))
               5))))

    (testing "another actor's activity does not bleed in"
      (let [other (create-test-user!)]
        (audit! (:id other) now)
        (let [latest (:last_active (silently #(stats/user-stats! (:id user))))]
          (is (< (.toEpochSecond latest) (.toEpochSecond now))))))))

(deftest test-user-stats-lookup-by-email-or-id
  (let [user (create-test-user!)]
    (testing "found by email"
      (is (= (:id user) (:id (silently #(stats/user-stats! (:email user)))))))
    (testing "found by uuid (string or object)"
      (is (= (:id user) (:id (silently #(stats/user-stats! (:id user))))))
      (is (= (:id user) (:id (silently #(stats/user-stats! (str (:id user))))))))))

(deftest test-user-stats-missing
  (testing "returns nil for an unknown email"
    (is (nil? (silently #(stats/user-stats! "nobody@example.com"))))))

;; -- Fleet -----------------------------------------------------------------

(defn- add-relationship! [map-id source target user-id]
  (relationship/create! {:map_id    map-id
                         :source_id source
                         :target_id target
                         :type      "protective"}
                        user-id))

(deftest test-fleet-users-and-flags
  (create-test-user! {:is_founding_circle true})
  (create-test-user! {:is_founding_circle true})
  (let [pending (create-test-user! {:is_founding_circle false})
        _       (erasure/request-deletion! db/datasource (:id pending))
        result  (silently #(stats/fleet-stats!))]
    (testing "total counts every non-tombstone account (pending-deletion included)"
      (is (= 3 (-> result :users :total))))
    (testing "pending-deletion surfaced separately"
      (is (= 1 (-> result :users :pending_deletion))))
    (testing "founding-circle count"
      (is (= 2 (:founding_circle result))))))

(deftest test-fleet-active-windows
  (let [u1  (create-test-user!)
        u2  (create-test-user!)
        u3  (create-test-user!)
        now (OffsetDateTime/now)]
    ;; u1 active twice within 24h (DISTINCT must collapse to one)
    (audit! (:id u1) (.minusHours now 2))
    (audit! (:id u1) (.minusHours now 5))
    ;; u2 active within 7d but not 24h
    (audit! (:id u2) (.minusDays now 3))
    ;; u3 active outside both windows
    (audit! (:id u3) (.minusDays now 10))
    ;; tombstone activity must never count
    (audit! erasure/tombstone-id (.minusHours now 1))
    (let [result (silently #(stats/fleet-stats!))]
      (testing "active in last 24h: distinct actors, tombstone excluded"
        (is (= 1 (-> result :active :last_24h :count)))
        (is (= 33.3 (-> result :active :last_24h :pct))))
      (testing "active in last 7d"
        (is (= 2 (-> result :active :last_7d :count)))
        (is (= 66.7 (-> result :active :last_7d :pct)))))))

(deftest test-fleet-totals
  (let [a      (create-test-user!)
        am     (create-test-map! (:id a))
        ap1    (add-part! (:id am) (:id a))
        ap2    (add-part! (:id am) (:id a))
        _      (add-relationship! (:id am) (:id ap1) (:id ap2) (:id a))
        b      (create-test-user!)
        bm     (create-test-map! (:id b))
        _      (add-part! (:id bm) (:id b))
        result (silently #(stats/fleet-stats!))]
    (testing "fleet totals sum current rows across all owners"
      (is (= {:maps 2 :parts 3 :relationships 1} (:totals result))))))

(deftest test-fleet-billing-breakdown
  (create-test-user!)                                   ; never-paid
  (let [paid   (create-test-user!)
        over   (create-test-user!)
        _      (silently #(billing/set-paid-through! (:email paid) "2099-01-01"))
        _      (silently #(billing/set-paid-through! (:email over) "2000-01-01"))
        result (silently #(stats/fleet-stats!))]
    (testing "paid / overdue / never-paid frequencies reuse billing's classification"
      (is (= {:paid 1 :overdue 1 :never_paid 1} (:billing result))))))

(deftest test-fleet-empty
  (testing "only the tombstone exists: zero totals, no division by zero"
    (let [result (silently #(stats/fleet-stats!))]
      (is (= 0 (-> result :users :total)))
      (is (= 0 (-> result :active :last_24h :count)))
      (is (= 0.0 (-> result :active :last_24h :pct))))))
