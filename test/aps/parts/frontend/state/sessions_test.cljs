(ns aps.parts.frontend.state.sessions-test
  (:require
   [aps.parts.frontend.state.sessions :as sessions]
   [cljs.test :refer-macros [deftest is testing]]))

(def ^:private s1 {:id "s1" :ordinal 1 :trigger nil})
(def ^:private s2 {:id "s2" :ordinal 2 :trigger "argument at work"})

(deftest apply-sessions-test
  (testing "a fetched list lands under [:map :sessions], anchor order kept"
    (is (= [s1 s2]
           (get-in (sessions/apply-sessions {:map {:id "map-1"}} "map-1" [s1 s2])
                   [:map :sessions]))))

  (testing "a stale fetch for a Map no longer open is dropped — navigating
            away mid-request must not graft another Map's Sessions on"
    (let [db {:map {:id "map-2"}}]
      (is (= db (sessions/apply-sessions db "map-1" [s1]))))))

(deftest active-session-test
  (testing "the active Session is the latest by anchor (the list's last)"
    (is (= s2 (sessions/active-session {:map {:id "m" :sessions [s1 s2]}}))))

  (testing "no Sessions loaded (or none exist) — no active Session"
    (is (nil? (sessions/active-session {:map {:id "m"}})))
    (is (nil? (sessions/active-session {:map {:id "m" :sessions []}})))))

(deftest editable?-test
  (testing "editing requires an active Session (ADR-0014)"
    (is (true? (sessions/editable? {:map {:id "m" :sessions [s1]}}))))

  (testing "no Session — read-only until one is started"
    (is (false? (sessions/editable? {:map {:id "m" :sessions []}}))))

  (testing "Sessions not yet fetched reads as read-only, never the reverse —
            a Map must prove it is editable, not be assumed so"
    (is (false? (sessions/editable? {:map {:id "m"}}))))

  (testing "demo-mode Maps are exempt from the Session model entirely"
    (is (true? (sessions/editable? {:demo-mode true :map {:id "m"}})))
    (is (true? (sessions/editable? {:demo-mode :minimal :map {:id "m"}})))))

(deftest read-only-reason-test
  (testing "the reason keyword drives the UI copy; nil when editable"
    (is (= :no-session (sessions/read-only-reason {:map {:id "m" :sessions []}})))
    (is (nil? (sessions/read-only-reason {:map {:id "m" :sessions [s1]}})))
    (is (nil? (sessions/read-only-reason {:demo-mode true :map {:id "m"}})))))

(deftest add-session-test
  (testing "a started Session appends and becomes the active one"
    (let [db (sessions/add-session {:map {:id "m" :sessions [s1]}} s2)]
      (is (= [s1 s2] (get-in db [:map :sessions])))
      (is (= s2 (sessions/active-session db)))))

  (testing "the very first Session of a previously read-only Map"
    (let [db (sessions/add-session {:map {:id "m" :sessions []}} s1)]
      (is (true? (sessions/editable? db)))))

  (testing "a successful start clears any earlier Session error"
    (is (nil? (get-in (sessions/add-session
                       {:map {:id "m" :sessions []}
                        :ui  {:session-error "boom"}}
                       s1)
                      [:ui :session-error])))))

(deftest set-trigger-test
  (testing "only the target Session's trigger changes"
    (let [db (sessions/set-trigger {:map {:id "m" :sessions [s1 s2]}}
                                   "s2" "conflict with mother")]
      (is (= "conflict with mother"
             (:trigger (sessions/active-session db))))
      (is (nil? (:trigger (first (get-in db [:map :sessions]))))))))

(deftest set-activation-test
  (testing "only the target Session's activated Part changes"
    (let [db (sessions/set-activation {:map {:id "m" :sessions [s1 s2]}}
                                      "s2" "part-9")]
      (is (= "part-9" (:activated_part_id (sessions/active-session db))))
      (is (nil? (:activated_part_id (first (get-in db [:map :sessions])))))))

  (testing "nil clears the link"
    (let [db (sessions/set-activation
              {:map {:id "m" :sessions [(assoc s2 :activated_part_id "part-9")]}}
              "s2" nil)]
      (is (nil? (:activated_part_id (sessions/active-session db)))))))

(deftest normalize-session-test
  (testing "ingestion stringifies transit's cljs-UUID ids, so
            [:map :sessions] holds one representation and consumers can
            compare :activated_part_id to (string) Part ids directly"
    (let [raw {:id                (uuid "0f8fad5b-d9cb-469f-a165-70867728950e")
               :activated_part_id (uuid "2f8fad5b-d9cb-469f-a165-70867728950e")
               :ordinal           1}
          db  (sessions/apply-sessions {:map {:id "m"}} "m" [raw])
          s   (first (get-in db [:map :sessions]))]
      (is (= "0f8fad5b-d9cb-469f-a165-70867728950e" (:id s)))
      (is (= "2f8fad5b-d9cb-469f-a165-70867728950e" (:activated_part_id s)))))

  (testing "add-session normalizes the started Session the same way; a
            missing activation stays nil"
    (let [db (sessions/add-session
              {:map {:id "m" :sessions []}}
              {:id (uuid "0f8fad5b-d9cb-469f-a165-70867728950e") :ordinal 1})
          s  (sessions/active-session db)]
      (is (= "0f8fad5b-d9cb-469f-a165-70867728950e" (:id s)))
      (is (nil? (:activated_part_id s))))))

(deftest remove-session-test
  (testing "deleting the latest Session re-activates the previous one —
            the started-by-mistake undo"
    (let [db (sessions/remove-session {:map {:id "m" :sessions [s1 s2]}} "s2")]
      (is (= [s1] (get-in db [:map :sessions])))
      (is (= s1 (sessions/active-session db)))))

  (testing "a successful delete clears any earlier Session error"
    (is (nil? (get-in (sessions/remove-session
                       {:map {:id "m" :sessions [s1]}
                        :ui  {:session-error "boom"}}
                       "s1")
                      [:ui :session-error])))))

(deftest session-error-test
  (testing "the server's refusal message is stored verbatim — the server is
            the judge of emptiness, the client just relays"
    (is (= "Only an empty Session can be deleted"
           (get-in (sessions/set-error {} "Only an empty Session can be deleted")
                   [:ui :session-error]))))
  (testing "clearing"
    (is (nil? (get-in (sessions/clear-error {:ui {:session-error "x"}})
                      [:ui :session-error])))))

(deftest undo-window-test
  (testing "starting a Session opens its undo window"
    (let [db (-> {:map {:id "m" :sessions [s1]}}
                 (sessions/add-session s2)
                 (sessions/open-undo-window "s2"))]
      (is (true? (sessions/undoable? db)))))

  (testing "the first content creation closes the window — server parity:
            only first appearances make a Session undeletable"
    (let [db (-> {:map {:id "m" :sessions [s1 s2]}}
                 (sessions/open-undo-window "s2")
                 (sessions/close-undo-window))]
      (is (false? (sessions/undoable? db)))))

  (testing "a fetched list never opens a window — reload closes it (the
            client cannot reconstruct deletability from what it sees)"
    (let [db (sessions/apply-sessions {:map {:id "m"}} "m" [s1 s2])]
      (is (false? (sessions/undoable? db)))))

  (testing "the window only counts for the Session that is still active —
            a stale id from a superseded start does not resurrect it"
    (let [db (-> {:map {:id "m" :sessions [s1 s2]}}
                 (sessions/open-undo-window "s1"))]
      (is (false? (sessions/undoable? db)))))

  (testing "undoing (removing) the Session closes its window"
    (let [db (-> {:map {:id "m" :sessions [s1 s2]}}
                 (sessions/open-undo-window "s2")
                 (sessions/remove-session "s2"))]
      (is (false? (sessions/undoable? db)))
      (is (= s1 (sessions/active-session db))))))

(deftest stamp-first-appearance-test
  (testing "a freshly created entity first appears in the active Session —
            the optimistic row gets the badge datum the server would
            compute, so badges never wait for a refetch"
    (is (= 2 (:first_appeared_ordinal
              (sessions/stamp-first-appearance
               {:map {:id "m" :sessions [s1 s2]}}
               {:id "p9" :label "new"})))))

  (testing "no active Session (demo Maps) — the entity passes through
            unstamped and wears no badge"
    (is (= {:id "p9"}
           (sessions/stamp-first-appearance {:demo-mode true :map {:id "m"}}
                                            {:id "p9"})))))

(deftest trigger-preview-test
  (testing "short triggers pass through whole"
    (is (= {:preview "conflict at work" :truncated? false}
           (sessions/trigger-preview "conflict at work" 100))))

  (testing "long triggers cut at the limit; the caller renders the
            see-more affordance"
    (is (= {:preview "abcde" :truncated? true}
           (sessions/trigger-preview "abcdefgh" 5))))

  (testing "multi-line text keeps its newlines in the preview"
    (is (= {:preview "line one\nline" :truncated? true}
           (sessions/trigger-preview "line one\nline two\nline three" 13)))))

(deftest display-label-test
  (testing "Session {ordinal} — {trigger}; ordinal alone when no trigger"
    (is (= "Session 2 — argument at work" (sessions/display-label s2)))
    (is (= "Session 1" (sessions/display-label s1)))
    (is (= "Session 1" (sessions/display-label (assoc s1 :trigger ""))))))
