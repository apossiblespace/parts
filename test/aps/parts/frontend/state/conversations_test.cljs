(ns aps.parts.frontend.state.conversations-test
  (:require
   [aps.parts.frontend.state.conversations :as c]
   [cljs.test :refer-macros [deftest is testing]]))

(def ^:private db
  {:map {:sessions             [{:id "s1" :ordinal 1 :anchor_valid_at "2026-01-01"}
                                {:id "s2" :ordinal 2 :anchor_valid_at "2026-02-01"}]
         :conversation_entries [{:id "e1" :part_id "a" :speaker "self" :first_appeared_ordinal 1}
                                {:id "e2" :part_id "b" :speaker "part" :first_appeared_ordinal 1}
                                {:id "e3" :part_id "a" :speaker "part" :first_appeared_ordinal 2}]}})

(defn- entries [db] (get-in db [:map :conversation_entries]))

(deftest add-merge-remove-test
  (testing "a new entry is appended and stamped with the active Session"
    (is (= {:id "e4" :part_id "a" :first_appeared_ordinal 2}
           (last (entries (c/add-entry db {:id "e4" :part_id "a"}))))))
  (testing "a Map loaded without entries (the playground) starts a vector"
    (is (= [{:id "e1"}] (entries (c/add-entry {} {:id "e1"})))))
  (testing "merge edits one entry"
    (is (= "hi" (:text (first (c/merge-entry (entries db) "e1" {:text "hi"}))))))
  (testing "remove drops one entry; a Part's removal drops its whole conversation"
    (is (= ["e2" "e3"] (mapv :id (c/remove-entry (entries db) "e1"))))
    (is (= ["e2"] (mapv :id (c/remove-part-entries (entries db) "a"))))))

(deftest grouping-test
  (testing "by Session, oldest first, writing order kept inside a group"
    (is (= [[1 ["e1" "e2"]] [2 ["e3"]]]
           (mapv (fn [[o es]] [o (mapv :id es)]) (c/by-session (entries db))))))
  (testing "by Part, in the order Parts first speak"
    (is (= [["a" ["e1" "e3"]] ["b" ["e2"]]]
           (mapv (fn [[p es]] [p (mapv :id es)]) (c/by-part (entries db))))))
  (testing "filters"
    (is (= ["e1" "e3"] (mapv :id (c/for-part (entries db) "a"))))))

(deftest runs-test
  (testing "consecutive entries from one speaker form one run"
    (is (= [["self" ["e1"]] ["part" ["e2" "e3"]] ["self" ["e4"]]]
           (mapv (fn [[sp es]] [sp (mapv :id es)])
                 (c/runs [{:id "e1" :speaker "self"} {:id "e2" :speaker "part"}
                          {:id "e3" :speaker "part"} {:id "e4" :speaker "self"}])))))
  (is (= [] (c/runs []))))

(deftest speaker-label-test
  (is (= "Self" (c/speaker-label "self" {:label "Exile"})))
  (is (= "Therapist" (c/speaker-label "therapist" nil)))
  (is (= "Exile" (c/speaker-label "part" {:label "Exile"}))))

(deftest window-mode-test
  (testing "Part mode needs a chosen Part mode and one selected Part"
    (is (= :part (c/window-mode :part {:id "a"})))
    (is (= :self (c/window-mode :part nil)))
    (is (= :self (c/window-mode :self {:id "a"})))))
