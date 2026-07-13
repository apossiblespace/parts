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

(deftest no-flicker-test
  (testing "stepping to an uncached Session keeps the previous content on
            screen until its snapshot lands — the canvas never blanks"
    (let [db (-> db3 tt/enter (tt/step :back))]
      (is (= "s2" (get-in db [:time-travel :session-id])) "the bar shows the target")
      (is (= [{:id "p1"} {:id "p2"}] (:parts (tt/canvas-content db)))
          "the canvas still shows the previous (live) content")
      (is (true? (tt/snapshot-needed? db)))))

  (testing "the arriving snapshot switches the shown content"
    (let [db (-> db3 tt/enter (tt/step :back)
                 (tt/store-snapshot "s2" {:parts [{:id "p1"}] :relationships []}))]
      (is (= [{:id "p1"}] (:parts (tt/canvas-content db))))
      (is (false? (tt/snapshot-needed? db)))))

  (testing "a snapshot for a Session that is no longer the target is
            cached but does not hijack the view (rapid multi-step)"
    (let [db (-> db3 tt/enter
                 (tt/step :back)            ; target s2, fetch fires
                 (tt/step :back)            ; target s1 before s2 lands
                 (tt/store-snapshot "s2" {:parts [{:id "p1"}] :relationships []}))]
      (is (= "s1" (get-in db [:time-travel :session-id])))
      (is (= [{:id "p1"} {:id "p2"}] (:parts (tt/canvas-content db)))
          "still the last shown content — s1's snapshot hasn't arrived")
      (is (some? (get-in db [:time-travel :snapshots "s2"])) "s2 cached for later")))

  (testing "stepping to an already-cached Session switches instantly"
    (let [db (-> db3 tt/enter (tt/step :back)
                 (tt/store-snapshot "s2" {:parts [{:id "p1"}] :relationships []})
                 (tt/step :forward)
                 (tt/step :back))]
      (is (= [{:id "p1"}] (:parts (tt/canvas-content db))))
      (is (false? (tt/snapshot-needed? db)))))

  (testing "stepping to the latest is always instant — it is the live Map"
    (let [db (-> db3 tt/enter (tt/step :back) (tt/step :forward))]
      (is (= [{:id "p1"} {:id "p2"}] (:parts (tt/canvas-content db))))
      (is (false? (tt/snapshot-needed? db))))))

(deftest viewed-session-test
  (testing "editing mode: the active Session — its activation marker is
            the one on screen"
    (is (= s3 (tt/viewed-session db3))))

  (testing "time travel: the SHOWN Session, lagging the target until its
            snapshot lands — the marker must match the content on screen"
    (let [stepped (-> db3 tt/enter (tt/step :back))]
      (is (= s3 (tt/viewed-session stepped)))
      (is (= s2 (tt/viewed-session
                 (tt/store-snapshot stepped "s2" {:parts [] :relationships []}))))))

  (testing "nil when no Sessions are loaded (demo Maps)"
    (is (nil? (tt/viewed-session {:map {:id "m"}})))))

(deftest interpolate-parts-test
  (let [from [{:id "a" :position_x 0 :position_y 0 :label "old label"}
              {:id "gone" :position_x 5 :position_y 5}]
        to   [{:id "a" :position_x 100 :position_y 50 :label "new label"}
              {:id "new" :position_x 20 :position_y 20}]]
    (testing "Parts present in both glide: positions lerp, everything else
              is already the target's"
      (let [half (tt/interpolate-parts from to 0.5)]
        (is (= {:id "a" :position_x 50 :position_y 25 :label "new label"}
               (first half)))))

    (testing "a Part new to the target renders at its final position from
              the first frame"
      (is (= {:id "new" :position_x 20 :position_y 20}
             (second (tt/interpolate-parts from to 0.5)))))

    (testing "t=1 is exactly the target — the tween lands, never drifts"
      (is (= to (tt/interpolate-parts from to 1))))

    (testing "t=0 is the target set at the source's positions"
      (is (= {:id "a" :position_x 0 :position_y 0 :label "new label"}
             (first (tt/interpolate-parts from to 0))))))

  (testing "sizes glide too — nil means the default size"
    (let [from [{:id "a" :position_x 0 :position_y 0 :width 100 :height 100}
                {:id "b" :position_x 0 :position_y 0}]
          to   [{:id "a" :position_x 0 :position_y 0 :width 200 :height 100}
                {:id "b" :position_x 0 :position_y 0 :width 150 :height 150}]
          half (tt/interpolate-parts from to 0.5)]
      (is (= 150 (:width (first half))))
      (is (= 100 (:height (first half))) "an unchanged size stays put")
      (is (= 125 (:width (second half)))
          "a never-resized Part glides from the default size")
      (is (= to (tt/interpolate-parts from to 1))
          "sizes land exactly on the target too"))))

(deftest key-event-test
  (testing "the mode's keys: Escape exits, arrows step"
    (is (= [:time-travel/exit] (tt/key-event "Escape")))
    (is (= [:time-travel/step :back] (tt/key-event "ArrowLeft")))
    (is (= [:time-travel/step :forward] (tt/key-event "ArrowRight"))))

  (testing "other keys are not the mode's — they fall through to the
            tool shortcuts (V/H, Space spring-hand)"
    (is (nil? (tt/key-event "v")))
    (is (nil? (tt/key-event " ")))
    (is (nil? (tt/key-event "h")))))

(deftest toggle-key?-test
  (testing "T toggles the mode from either side, case-insensitive"
    (is (true? (tt/toggle-key? "t")))
    (is (true? (tt/toggle-key? "T"))))
  (testing "other keys don't"
    (is (false? (tt/toggle-key? "h")))
    (is (false? (tt/toggle-key? "Escape")))))

(deftest has-history?-test
  (testing "two Sessions make a Map travelable; fewer leave nothing to see"
    (is (false? (tt/has-history? [])))
    (is (false? (tt/has-history? [s1])))
    (is (true? (tt/has-history? [s1 s2])))))

(deftest error-test
  (testing "a fetch failure is held for the bar; stepping clears it"
    (let [db (-> db3 tt/enter (tt/step :back)
                 (tt/set-error "Could not load that session"))]
      (is (= "Could not load that session" (tt/error db)))
      (is (nil? (tt/error (tt/step db :forward)))))))
