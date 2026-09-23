(ns aps.parts.entity.conversation-entry-test
  "Conversation entries (ADR-0018) through the change-event batch, the
   path the app uses."
  (:require
   [aps.parts.api.maps-events :as events]
   [aps.parts.db :as db]
   [aps.parts.db.bitemporal :as bt]
   [aps.parts.entity.conversation-entry :as conversation-entry]
   [aps.parts.entity.map :as parts-map]
   [aps.parts.entity.session :as session]
   [aps.parts.helpers.utils :refer [with-test-db create-test-user! create-test-map!]]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [next.jdbc :as jdbc]))

(use-fixtures :once with-test-db)

(defn- setup!
  "A Map with Session 1 and one Part. Returns ids."
  []
  (let [user    (create-test-user!)
        the-map (create-test-map! (:id user) "Conversations")
        _       (session/create! (:id the-map) (:id user))
        part-id (str (random-uuid))]
    (events/apply-changes! db/datasource
                           {:map-id   (:id the-map)
                            :actor-id (:id user)
                            :changes  [{:entity "part"                                   :type "create" :id part-id
                                        :data   {:type       "exile" :label      "Exile"
                                                 :position_x 0       :position_y 0}}]})
    {:user-id (:id user) :map-id (:id the-map) :part-id part-id}))

(defn- apply! [{:keys [user-id map-id]} changes]
  (events/apply-changes! db/datasource
                         {:map-id map-id :actor-id user-id :changes changes}))

(defn- add-entry! [ctx speaker text]
  (let [id (str (random-uuid))]
    (apply! ctx [{:entity "conversation-entry"                                  :type "create" :id id
                  :data   {:part_id (:part-id ctx) :speaker speaker :text text}}])
    id))

(defn- entries [{:keys [map-id]}]
  (:conversation_entries (parts-map/fetch map-id)))

(deftest test-create-and-read
  (let [ctx (setup!)
        id  (add-entry! ctx "self" "I see you.")]
    (testing "the entry is on the Map, with its Part and speaker"
      (is (= [{:id      (db/->uuid id)
               :part_id (db/->uuid (:part-id ctx))
               :speaker "self"
               :text    "I see you."}]
             (mapv #(select-keys % [:id :part_id :speaker :text]) (entries ctx)))))
    (testing "the entry's Session is derived from when it was written"
      (is (= 1 (:ordinal (get (session/first-appearances (:map-id ctx))
                              (db/->uuid id))))))))

(deftest test-create-validation
  (let [ctx (setup!)]
    (testing "unknown speaker is rejected"
      (is (thrown? clojure.lang.ExceptionInfo
                   (add-entry! ctx "narrator" "…"))))
    (testing "blank text is rejected"
      (is (thrown? clojure.lang.ExceptionInfo
                   (add-entry! ctx "self" "   "))))
    (testing "a Part from another Map is rejected"
      (let [other (setup!)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a Part in this Map"
                              (add-entry! (assoc ctx :part-id (:part-id other))
                                          "self" "hello")))))))

(deftest test-edit-only-while-session-active
  (let [ctx (setup!)
        id  (add-entry! ctx "part" "I'm scared.")]
    (testing "edit and delete work while the entry's Session is active"
      (apply! ctx [{:entity "conversation-entry"               :type "update" :id id
                    :data   {:text "I'm scared you'll leave."}}])
      (is (= "I'm scared you'll leave." (:text (first (entries ctx))))))

    (testing "once a newer Session starts, the entry is read-only"
      (session/create! (:map-id ctx) (:user-id ctx))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"active Session"
                            (apply! ctx [{:entity "conversation-entry" :type "update" :id id
                                          :data   {:text "rewritten"}}])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"active Session"
                            (apply! ctx [{:entity "conversation-entry" :type "remove" :id id
                                          :data   {}}])))
      (is (= "I'm scared you'll leave." (:text (first (entries ctx))))))

    (testing "the Part an entry belongs to cannot be changed"
      (is (thrown? clojure.lang.ExceptionInfo
                   (apply! ctx [{:entity "conversation-entry"           :type "update"
                                 :id     (add-entry! ctx "self" "new")
                                 :data   {:part_id (str (random-uuid))}}]))))

    (testing "an entry in another Map reads as not-found"
      (let [other (setup!)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not found"
                              (apply! other [{:entity "conversation-entry" :type "remove"
                                              :id     id                   :data {}}])))))))

(deftest test-part-delete-retracts-its-entries
  (let [ctx (setup!)]
    (add-entry! ctx "self" "one")
    (add-entry! ctx "therapist" "two")
    (apply! ctx [{:entity "part" :type "remove" :id (:part-id ctx) :data {}}])
    (is (empty? (entries ctx)))))

(deftest test-map-delete-retracts-entries
  (let [ctx (setup!)]
    (add-entry! ctx "self" "one")
    (parts-map/delete! (:map-id ctx) (:user-id ctx))
    (is (empty? (bt/as-of-now db/datasource :conversation_entries
                              [:= :map_id (db/->uuid (:map-id ctx))])))))

(deftest test-entry-right-after-a-new-session-lands-in-it
  ;; Anchors and content share the app server's clock, so an entry
  ;; written the instant a Session starts belongs to that Session and is
  ;; editable — never filed under the previous one.
  (let [ctx (setup!)]
    (dotimes [_ 5]
      (let [s  (session/create! (:map-id ctx) (:user-id ctx))
            id (add-entry! ctx "self" "right away")]
        (is (= (:ordinal s)
               (:ordinal (get (session/first-appearances (:map-id ctx))
                              (db/->uuid id)))))
        (apply! ctx [{:entity "conversation-entry" :type "update" :id id
                      :data   {:text "edited"}}])))
    (testing "anchors stay strictly increasing"
      (let [anchors (mapv (comp db/->instant :anchor_valid_at)
                          (session/index (:map-id ctx)))]
        (is (apply distinct? anchors))
        (is (= anchors (sort anchors)))))))

(deftest test-session-start-waits-for-an-in-flight-edit
  ;; The edit check holds a shared lock on the Map until its batch
  ;; commits; starting a Session needs the exclusive lock, so it cannot
  ;; slip in between the check and the write.
  (let [ctx (setup!)
        id  (add-entry! ctx "self" "hold")]
    (jdbc/with-transaction [edit-tx db/datasource]
      (conversation-entry/update! id {:text "mid-edit"} (:user-id ctx) edit-tx (:map-id ctx))
      (let [start (future
                    (jdbc/with-transaction [s-tx db/datasource]
                      (jdbc/execute! s-tx ["SET LOCAL lock_timeout = '200ms'"])
                      (session/create! (:map-id ctx) (:user-id ctx) s-tx)))]
        (is (thrown? Exception @start)
            "the Session start is blocked while the edit is open")))))
