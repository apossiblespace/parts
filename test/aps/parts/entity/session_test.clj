(ns aps.parts.entity.session-test
  (:require
   [aps.parts.db :as db]
   [aps.parts.entity.map :as parts-map]
   [aps.parts.entity.part :as part]
   [aps.parts.entity.relationship :as relationship]
   [aps.parts.entity.session :as session]
   [aps.parts.helpers.utils :refer [with-test-db create-test-user!]]
   [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :once with-test-db)

(defn- make-map! [user]
  (parts-map/create! {:title "Test Map" :owner_id (:id user)} (:id user)))

(defn- make-part! [user the-map label]
  (part/create! {:map_id     (:id the-map)
                 :type       "manager"
                 :label      label
                 :position_x 100
                 :position_y 100}
                (:id user)))

(deftest test-session-create-and-ordering
  (let [user    (create-test-user!)
        the-map (make-map! user)
        s1      (session/create! (:id the-map) (:id user))
        s2      (session/create! (:id the-map) (:id user))]

    (testing "create! captures a server-side anchor and per-Map ordinals"
      (is (uuid? (:id s1)))
      (is (= 1 (:ordinal s1)))
      (is (= 2 (:ordinal s2)))
      (is (some? (:anchor_valid_at s1)))
      (is (neg? (compare (:anchor_valid_at s1) (:anchor_valid_at s2)))
          "anchors are monotonic with creation order"))

    (testing "index lists a Map's Sessions ordered by anchor"
      (is (= [(:id s1) (:id s2)]
             (mapv :id (session/index (:id the-map))))))

    (testing "ordinals are per-Map, not global"
      (let [other-map (make-map! user)
            other-s1  (session/create! (:id other-map) (:id user))]
        (is (= 1 (:ordinal other-s1)))))

    (testing "index is scoped to the Map"
      (let [empty-map (make-map! user)]
        (is (empty? (session/index (:id empty-map))))))))

(deftest test-activation-link
  (let [user    (create-test-user!)
        the-map (make-map! user)
        s1      (session/create! (:id the-map) (:id user))
        p1      (make-part! user the-map "A")
        p2      (make-part! user the-map "B")]

    (testing "set-activation! links a Part; index surfaces it"
      (session/set-activation! (:id s1) (:id the-map) (:id p1) (:id user))
      (is (= (:id p1) (:activated_part_id (first (session/index (:id the-map)))))))

    (testing "setting again replaces — at most one activation per Session"
      (session/set-activation! (:id s1) (:id the-map) (:id p2) (:id user))
      (is (= (:id p2) (:activated_part_id (first (session/index (:id the-map)))))))

    (testing "the activated Part must currently exist in the same Map"
      (let [other-map  (make-map! user)
            other-part (make-part! user other-map "Elsewhere")]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a Part in this Map"
                              (session/set-activation!
                               (:id s1) (:id the-map) (:id other-part) (:id user))))))

    (testing "clear-activation! removes the link"
      (session/clear-activation! (:id s1) (:id the-map) (:id user))
      (is (nil? (:activated_part_id (first (session/index (:id the-map)))))))))

(deftest test-derived-membership
  (let [user    (create-test-user!)
        the-map (make-map! user)
        s1      (session/create! (:id the-map) (:id user))
        p1      (make-part! user the-map "In S1")
        p2      (make-part! user the-map "Also S1")
        r1      (relationship/create! {:map_id    (:id the-map)
                                       :source_id (:id p1)
                                       :target_id (:id p2)
                                       :type      "protects"}
                                      (:id user))
        s2      (session/create! (:id the-map) (:id user))
        p3      (make-part! user the-map "In S2")]

    (testing "first-appearances buckets content into the Session whose
              [anchor, next-anchor) range holds its earliest valid_at"
      (let [appeared (session/first-appearances (:id the-map))]
        (is (= (:id s1) (:id (get appeared (:id p1)))))
        (is (= (:id s1) (:id (get appeared (:id r1)))))
        (is (= (:id s2) (:id (get appeared (:id p3)))))))

    (testing "an edit does not move a Part's first appearance"
      (part/update! (:id p1) {:label "Renamed in S2"} (:id user))
      (is (= (:id s1)
             (:id (get (session/first-appearances (:id the-map)) (:id p1))))))

    (testing "session-at returns the Session covering an instant"
      (is (= (:id s1)
             (:id (session/session-at (:id the-map) (:anchor_valid_at s1)))))
      (is (= (:id s2)
             (:id (session/session-at (:id the-map)
                                      (java.time.OffsetDateTime/now))))))))

(deftest test-as-of-instant
  (let [user    (create-test-user!)
        the-map (make-map! user)
        s1      (session/create! (:id the-map) (:id user))
        s2      (session/create! (:id the-map) (:id user))]

    (testing "a past Session reads 1µs before the next anchor — ranges are
              half-open, so reading AT the anchor would leak the next
              Session's first content"
      (is (= (.minusNanos (db/->instant (:anchor_valid_at s2)) 1000)
             (.toInstant (session/as-of-instant (:id the-map) (:id s1))))))

    (testing "the latest Session's range is open — nil means the live view"
      (is (nil? (session/as-of-instant (:id the-map) (:id s2)))))

    (testing "a Session id from another Map reads as not-found"
      (let [other-map (make-map! user)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not found"
                              (session/as-of-instant (:id other-map) (:id s1))))))))

(deftest test-trigger-mutation-rules
  (let [user    (create-test-user!)
        the-map (make-map! user)
        s1      (session/create! (:id the-map) (:id user))
        _       (session/create! (:id the-map) (:id user))
        s-last  (second (session/index (:id the-map)))]

    (testing "the trigger of the active (latest) Session is editable"
      (session/update-trigger! (:id s-last) (:id the-map)
                               "argument at work" (:id user))
      (is (= "argument at work"
             (:trigger (session/fetch (:id s-last) (:id the-map))))))

    (testing "a past Session's trigger is locked (ADR-0014: past is read-only)"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"active"
                            (session/update-trigger!
                             (:id s1) (:id the-map) "nope" (:id user)))))))

(deftest test-delete-rules
  (let [user    (create-test-user!)
        the-map (make-map! user)
        s1      (session/create! (:id the-map) (:id user))
        _p1     (make-part! user the-map "Content in S1")]

    (testing "the latest Session deletes when empty — the
              started-by-mistake undo"
      (let [s2 (session/create! (:id the-map) (:id user))]
        (session/delete! (:id s2) (:id the-map) (:id user))
        (is (= [(:id s1)] (mapv :id (session/index (:id the-map)))))))

    (testing "a Session with content in its range does not delete"
      ;; The content must sit in a Session with a predecessor — deleting
      ;; the only Session is refused before the content rule is reached.
      (let [s2 (session/create! (:id the-map) (:id user))]
        (make-part! user the-map "Content in S2")
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"content"
                              (session/delete! (:id s2) (:id the-map) (:id user))))))

    (testing "a non-latest Session does not delete"
      (let [_s2 (session/create! (:id the-map) (:id user))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"latest"
                              (session/delete! (:id s1) (:id the-map) (:id user))))))

    (testing "an empty-but-activated Session does not delete"
      ;; Activating links a Part without adding content — the Session
      ;; stays "empty" apart from the link.
      (let [s-last  (last (session/index (:id the-map)))
            part-id (-> (parts-map/fetch (:id the-map)) :parts first :id)]
        (session/set-activation! (:id s-last) (:id the-map) part-id (:id user))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"activation"
                              (session/delete! (:id s-last) (:id the-map) (:id user)))))))

  (testing "a Map's only Session does not delete, even empty — Maps are
            born with Session 1 and no-session Maps must not exist"
    (let [user    (create-test-user!)
          the-map (make-map! user)
          s1      (session/create! (:id the-map) (:id user))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"only"
                            (session/delete! (:id s1) (:id the-map) (:id user)))))))

(deftest test-explicit-audit
  (let [user    (create-test-user!)
        the-map (make-map! user)
        s1      (session/create! (:id the-map) (:id user))
        audits  (fn [session-id]
                  (db/query (db/sql-format
                             {:select   [:operation :actor_id]
                              :from     [:audit_log]
                              :where    [:and
                                         [:= :table_name "sessions"]
                                         [:= [:raw "row_pk->>'id'"] (str session-id)]]
                              :order-by [[:occurred_at :asc]]})))]

    (testing "create! writes its own audit row — no trigger fires on the
              non-temporal sessions table"
      (let [rows (audits (:id s1))]
        (is (= ["I"] (mapv :operation rows)))
        (is (= (:id user) (:actor_id (first rows))))))

    (testing "trigger edits audit too"
      (session/update-trigger! (:id s1) (:id the-map) "t" (:id user))
      (is (= ["I" "U"] (mapv :operation (audits (:id s1))))))

    (testing "deletes audit too (a second Session — the only one is
              undeletable)"
      (let [s2 (session/create! (:id the-map) (:id user))]
        (session/delete! (:id s2) (:id the-map) (:id user))
        (is (= ["I" "D"] (mapv :operation (audits (:id s2)))))))))
