(ns aps.parts.frontend.state.time-travel-test
  (:require
   [aps.parts.frontend.state.sessions :as sessions]
   [aps.parts.frontend.state.time-travel :as tt]
   [cljs.test :refer-macros [deftest is testing]]))

(def ^:private s1 {:id "s1" :ordinal 1 :trigger nil})
(def ^:private s2 {:id "s2" :ordinal 2 :trigger "argument at work"})
(def ^:private s3 {:id "s3" :ordinal 3 :trigger nil})

(def ^:private db3
  {:map {:id            "m"
         :parts         [{:id "p1"} {:id "p2"}]
         :relationships [{:id "r1"}]
         :sessions      [s1 s2 s3]}
   :ui  {:selected-node-ids ["p1"] :selected-edge-ids ["r1"]}})

(deftest enter-exit-test
  (testing "entering lands on the latest Session — the live view — and
            clears the selection (ids must not dangle across sources)"
    (let [db (tt/enter db3)]
      (is (true? (tt/active? db)))
      (is (= "s3" (get-in db [:time-travel :session-id])))
      (is (empty? (get-in db [:ui :selected-node-ids])))
      (is (empty? (get-in db [:ui :selected-edge-ids])))))

  (testing "a Map with fewer than two Sessions has no history to enter"
    (let [db {:map {:id "m" :sessions [s1]}}]
      (is (= db (tt/enter db)))))

  (testing "exit drops the whole subtree and clears selection again"
    (let [db (-> db3 tt/enter (assoc-in [:ui :selected-node-ids] ["p1"]) tt/exit)]
      (is (false? (tt/active? db)))
      (is (nil? (:time-travel db)))
      (is (empty? (get-in db [:ui :selected-node-ids]))))))

(deftest read-only-in-mode-test
  (testing "the mode drives the existing read-only seam: reason
            :viewing-past, editable? false — even at the latest Session
            (leaving is only ever the explicit exit)"
    (let [db (tt/enter db3)]
      (is (= :viewing-past (sessions/read-only-reason db)))
      (is (false? (sessions/editable? db)))))

  (testing "exiting restores editability"
    (is (true? (sessions/editable? (-> db3 tt/enter tt/exit))))))

(deftest step-test
  (let [db (tt/enter db3)]
    (testing "stepping back moves one Session; viewing reports position"
      (let [db' (tt/step db :back)]
        (is (= "s2" (get-in db' [:time-travel :session-id])))
        (is (= {:session s2 :index 2 :count 3 :latest? false}
               (tt/viewing db')))))

    (testing "stepping is clamped at both ends"
      (let [at-first (-> db (tt/step :back) (tt/step :back))]
        (is (= "s1" (get-in at-first [:time-travel :session-id])))
        (is (= "s1" (get-in (tt/step at-first :back)
                            [:time-travel :session-id])))
        (is (= "s3" (get-in (-> at-first (tt/step :forward)
                                (tt/step :forward)
                                (tt/step :forward))
                            [:time-travel :session-id])))))

    (testing "the latest Session reads as :latest? — the live view"
      (is (true? (:latest? (tt/viewing db)))))))

(deftest snapshot-test
  (let [db (-> db3 tt/enter (tt/step :back))]
    (testing "a past Session without a cached snapshot needs a fetch"
      (is (true? (tt/snapshot-needed? db))))

    (testing "the latest Session never needs one — it is the live Map"
      (is (false? (tt/snapshot-needed? (tt/step db :forward)))))

    (testing "a stored snapshot satisfies the need — stepping back to a
              visited Session refetches nothing (the past is immutable)"
      (let [db' (tt/store-snapshot db "s2" {:parts         [{:id "p1"}]
                                            :relationships []
                                            :title         "ignored"})]
        (is (false? (tt/snapshot-needed? db')))
        (is (= {:parts [{:id "p1"}] :relationships []}
               (get-in db' [:time-travel :snapshots "s2"])))))

    (testing "a snapshot landing after exit is dropped, not grafted on"
      (is (= {:map (:map db3)}
             (-> {:map (:map db3)}
                 (tt/store-snapshot "s2" {:parts []})))))))

(deftest canvas-content-test
  (testing "editing mode renders the live Map"
    (is (= {:parts         [{:id "p1"} {:id "p2"}]
            :relationships [{:id "r1"}]}
           (tt/canvas-content db3))))

  (testing "at the latest Session the mode still renders the live Map"
    (is (= [{:id "p1"} {:id "p2"}]
           (:parts (tt/canvas-content (tt/enter db3))))))

  (testing "a past Session renders its snapshot"
    (let [db (-> db3 tt/enter (tt/step :back)
                 (tt/store-snapshot "s2" {:parts         [{:id "p1"}]
                                          :relationships []}))]
      (is (= [{:id "p1"}] (:parts (tt/canvas-content db)))))))

(deftest viewed-ordinal-test
  (testing "editing mode: the active Session's ordinal (its newcomers
            wear the accented badge)"
    (is (= 3 (tt/viewed-ordinal db3))))

  (testing "time travel: the viewed Session's ordinal"
    (is (= 2 (tt/viewed-ordinal (-> db3 tt/enter (tt/step :back)))))))

(deftest error-test
  (testing "a fetch failure is held for the bar; stepping clears it"
    (let [db (-> db3 tt/enter (tt/step :back)
                 (tt/set-error "Could not load that session"))]
      (is (= "Could not load that session" (tt/error db)))
      (is (nil? (tt/error (tt/step db :forward)))))))
