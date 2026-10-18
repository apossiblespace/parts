(ns aps.parts.stats-test
  (:require
   [aps.parts.billing :as billing]
   [aps.parts.db :as db]
   [aps.parts.db.erasure :as erasure]
   [aps.parts.entity.map :as emap]
   [aps.parts.entity.part :as part]
   [aps.parts.entity.relationship :as relationship]
   [aps.parts.entity.user :as user]
   [aps.parts.helpers.utils :refer [create-test-map! create-test-user!
                                    silently with-test-db]]
   [aps.parts.stats :as stats]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [next.jdbc :as jdbc])
  (:import
   (java.time LocalDate OffsetDateTime ZoneOffset)))

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

(defn- founder!
  "A test user marked Founding Circle. Only the operator sets the flag, so
   it is written after the user is created."
  []
  (let [user (create-test-user!)]
    (db/update! :users {:is_founding_circle true} [:= :id (:id user)])
    user))

(defn- add-part! [map-id user-id]
  (part/create! {:map_id map-id} user-id))

(deftest test-user-stats-basics-and-counts
  (let [user    (founder!)
        the-map (create-test-map! (:id user))
        p1      (add-part! (:id the-map) (:id user))
        p2      (add-part! (:id the-map) (:id user))
        _       (relationship/create! {:map_id    (:id the-map)
                                       :source_id (:id p1)
                                       :target_id (:id p2)
                                       :type      "protects"}
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
                         :type      "protects"}
                        user-id))

(deftest test-fleet-users-and-flags
  (founder!)
  (founder!)
  (create-test-user!)
  (let [result (silently #(stats/fleet-stats!))]
    (testing "total counts every non-tombstone account"
      (is (= 3 (-> result :users :total))))
    (testing "founding-circle count"
      (is (= 2 (:founding_circle result))))))

;;; user-activity

(def ^:private today
  "A fixed date in the future. The fixtures also write audit rows at the
   current time, and these rows must fall outside the window."
  (LocalDate/of 2030 1 15))

(defn- at-noon
  "Returns noon UTC on the day `days-ago` days before `today`."
  [days-ago]
  (-> today (.minusDays days-ago) (.atTime 12 0) (.atOffset ZoneOffset/UTC)))

(defn- change!
  "Inserts an audit_log row for a change by `actor-id` to a row of `table`
   that carries `map-id`."
  [actor-id days-ago table op map-id]
  (jdbc/execute!
   db/datasource
   ["INSERT INTO audit_log (actor_id, occurred_at, table_name, operation, row_pk, after_row)
     VALUES (?::uuid, ?, ?, ?, '{}'::jsonb, ?::jsonb)"
    (str actor-id) (at-noon days-ago) table op
    (str "{\"map_id\": \"" map-id "\", \"notes\": \"clinical\"}")]))

(deftest test-user-activity-counts-the-window
  (let [user    (create-test-user!)
        the-map (create-test-map! (:id user))
        m1      (random-uuid)
        m2      (random-uuid)]
    (jdbc/execute! db/datasource
                   ["INSERT INTO sessions (map_id, ordinal) VALUES (?, 1), (?, 2)"
                    (:id the-map) (:id the-map)])
    (change! (:id user) 0 "parts" "I" m1)
    (change! (:id user) 0 "relationships" "U" m1)
    (change! (:id user) 3 "conversation_entries" "I" m2)
    (change! (:id user) 3 "sessions" "I" m2)
    (change! (:id user) 3 "sessions" "U" m2)
    (change! (:id user) 29 "parts" "D" m2)
    (change! (:id user) 30 "parts" "U" (random-uuid))
    (change! erasure/tombstone-id 0 "parts" "U" m1)
    (let [[row & more] (stats/user-activity today)]
      (testing "returns one row per real user"
        (is (= (:id user) (:id row)))
        (is (empty? more)))
      (testing "active days are the UTC dates with a change inside the window"
        (is (= #{today (.minusDays today 3) (.minusDays today 29)}
               (:active_days row))))
      (testing "counts changes per kind inside the window only"
        (is (= {:parts            2 :relationships 1 :conversation_entries 1
                :sessions_started 1 :maps_edited   2}
               (select-keys (:counts row) [:parts :relationships :conversation_entries
                                           :sessions_started :maps_edited]))))
      (testing "counts current maps and all their sessions"
        (is (= 1 (-> row :counts :maps)))
        (is (= 2 (-> row :counts :sessions))))
      (testing "last active is the latest change of all time"
        (is (= (at-noon 0) (:last_active row))))
      (testing "returns no row content"
        (is (not (re-find #"clinical" (pr-str row))))))))

(deftest test-fleet-active-windows
  (let [u1 (create-test-user!)
        u2 (create-test-user!)
        u3 (create-test-user!)]
    (change! (:id u1) 0 "parts" "U" (random-uuid))
    (change! (:id u1) 0 "parts" "U" (random-uuid))
    (change! (:id u2) 6 "parts" "U" (random-uuid))
    (change! (:id u3) 7 "parts" "U" (random-uuid))
    (change! erasure/tombstone-id 0 "parts" "U" (random-uuid))
    (let [{:keys [active]} (stats/fleet today)]
      (testing "active today: distinct actors in the utc day, tombstone excluded"
        (is (= {:count 1 :pct 33.3} (:today active))))
      (testing "active 7 d: the 7 whole utc days that end today"
        (is (= {:count 2 :pct 66.7} (:last_7d active))))
      (testing "active 30 d"
        (is (= {:count 3 :pct 100.0} (:last_30d active)))))))

(deftest test-fleet-30-days-is-the-activity-window
  (let [inside  (create-test-user!)
        outside (create-test-user!)]
    (change! (:id inside) 29 "parts" "I" (random-uuid))
    (change! (:id outside) 30 "parts" "I" (random-uuid))
    (testing "counts the users active in the 30 utc days that end on today"
      (is (= 1 (-> (stats/fleet today) :active :last_30d :count))))))

(deftest test-user-activity-orders-and-zero-fills
  (let [idle   (create-test-user!)
        older  (create-test-user!)
        recent (create-test-user!)]
    (change! (:id older) 5 "parts" "I" (random-uuid))
    (change! (:id recent) 1 "parts" "I" (random-uuid))
    (let [rows (stats/user-activity today)]
      (testing "sorts the most recently active first and the never active last"
        (is (= (map :id [recent older idle]) (map :id rows))))
      (testing "gives an inactive user zero counts and no active days"
        (let [row (last rows)]
          (is (nil? (:last_active row)))
          (is (= #{} (:active_days row)))
          (is (every? zero? (vals (:counts row)))))))))

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
      (is (= 0 (-> result :active :today :count)))
      (is (= 0.0 (-> result :active :today :pct))))))

(deftest test-product-updates-figures
  (let [out (create-test-user!)
        sub (create-test-user!)]
    (user/set-product-updates! (:id out) false)
    (user/set-product-updates! (:id sub) true)
    (create-test-user!)
    (testing "fleet counts subscribed, opted out and never-asked users"
      (is (= {:subscribed 1 :opted_out 1 :not_asked 1} (:product_updates (stats/fleet today)))))
    (testing "user activity marks the opted out user"
      (is (= {(:id out) true (:id sub) false}
             (-> (into {} (map (juxt :id :product_updates_opted_out)) (stats/user-activity today))
                 (select-keys [(:id out) (:id sub)])))))))

;;; Daily history

(defn- created! [user days-ago]
  (db/update! :users {:created_at (at-noon days-ago)} [:= :id (:id user)]))

(deftest test-snapshot-definitions
  (let [early (create-test-user!)
        late  (create-test-user!)]
    (created! early 3)
    (created! late 1)
    (change! (:id early) 10 "parts" "U" (random-uuid))
    (change! (:id early) 2 "parts" "U" (random-uuid))
    (change! (:id early) 1 "parts" "U" (random-uuid))
    (change! (:id late) 1 "parts" "U" (random-uuid))
    (change! erasure/tombstone-id 1 "parts" "U" (random-uuid))
    (db/update! :users {:stripe_subscription_status "active"} [:= :id (:id late)])
    (db/update! :users {:stripe_subscription_status "unpaid"} [:= :id (:id early)])
    (user/set-product-updates! (:id early) true)
    (silently #(billing/set-paid-through! (:email late) "2099-01-01"))
    (testing "writes one row for each day from the first account to yesterday"
      (is (= 3 (stats/snapshot! today true))))
    (let [rows (stats/history (.minusDays today 30))
          row  (fn [days-ago] (some #(when (= (.minusDays today days-ago) (:day %)) %) rows))]
      (testing "counts the users created by the end of each day"
        (is (= [1 1 2] (map :users rows))))
      (testing "counts distinct actors in the day, 7 and 30 days, tombstone excluded"
        (is (= [0 1 2] (map :active_day rows)))
        (is (= [0 1 2] (map :active_7d rows)))
        (is (= [1 1 2] (map :active_30d rows))))
      (testing "back-filled days have no snapshot counts"
        (is (every? nil? (map :subscribers (butlast rows)))))
      (testing "yesterday has the snapshot counts; unpaid is not a subscriber"
        (is (= {:subscribers 1 :product_updates 1 :paid 1}
               (select-keys (row 1) [:subscribers :product_updates :paid])))))
    (testing "fleet counts the same subscribers, and unpaid as payment failing"
      (is (= [1 1] ((juxt :subscribers :payment_failing) (stats/fleet today)))))
    (testing "a second run on the same day writes nothing"
      (is (= 0 (stats/snapshot! today true))))
    (testing "the next day writes one row"
      (is (= 1 (stats/snapshot! (.plusDays today 1) true))))))

(deftest test-snapshot-late-in-the-day
  (created! (create-test-user!) 2)
  (testing "a run late in the day leaves out the snapshot counts"
    (is (= 2 (stats/snapshot! today false)))
    (is (every? nil? (map :subscribers (stats/history (.minusDays today 30)))))))

(deftest test-snapshot-with-no-accounts
  (is (= 0 (stats/snapshot! today true))))
