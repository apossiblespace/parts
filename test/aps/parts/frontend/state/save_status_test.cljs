(ns aps.parts.frontend.state.save-status-test
  (:require
   [aps.parts.frontend.state.save-status :as save-status]
   [cljs.test :refer-macros [deftest is testing]]))

(deftest status-lifecycle-test
  (testing "a fresh Map has nothing needing saving — green is the
            resting state, there is no blank state"
    (is (= :saved (save-status/status {}))))

  (testing "queued changes read as dirty until a flush starts"
    (is (= :dirty (save-status/status (save-status/mark-dirty {})))))

  (testing "a flush consumes the dirty flag and shows as saving"
    (let [db (-> {} save-status/mark-dirty save-status/flush-started)]
      (is (= :saving (save-status/status db)))
      (is (= :saved (save-status/status (save-status/request-done db))))))

  (testing "edits made while a batch is in flight keep the Map dirty
            after that batch lands — another flush is coming"
    (is (= :dirty (-> {}
                      save-status/mark-dirty
                      save-status/flush-started
                      save-status/mark-dirty
                      save-status/request-done
                      save-status/status)))))

(deftest error-test
  (testing "a failed batch reads from the banner's [:map :save-error]
            flag — one fact, no shadow copy — and stays red through
            later activity until the Map is reloaded"
    (let [failed {:map {:save-error true}}]
      (is (= :error (save-status/status failed)))
      (is (= :error (save-status/status (save-status/mark-dirty failed))))
      (is (= :error (-> failed
                        save-status/mark-dirty
                        save-status/flush-started
                        save-status/request-done
                        save-status/status)))))

  (testing "a failed direct write (rename/trigger) reads as error but is
            cleared by the next successful write — those requests roll
            back optimistic state, so consistency is restored"
    (let [failed (-> {} save-status/write-started save-status/write-failed)]
      (is (= :error (save-status/status failed)))
      (is (= :saved (-> failed
                        save-status/write-started
                        save-status/write-succeeded
                        save-status/status))))))

(deftest concurrent-requests-test
  (testing "overlapping requests (batch + trigger PUT) both count; the
            spinner stays until the last one lands"
    (let [db (-> {} save-status/flush-started save-status/write-started)]
      (is (= :saving (save-status/status db)))
      (is (= :saving (save-status/status (save-status/request-done db))))
      (is (= :saved (-> db
                        save-status/request-done
                        save-status/write-succeeded
                        save-status/status)))))

  (testing "an unmatched completion (a late flush for a Map navigated
            away from) never drives the counter negative"
    (is (= :saved (-> {} save-status/request-done save-status/status)))))
