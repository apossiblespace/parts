(ns aps.parts.ops-test
  (:require
   [aps.parts.billing :as billing]
   [aps.parts.config :as conf]
   [aps.parts.invitations :as invitations]
   [aps.parts.mail :as mail]
   [aps.parts.ops :as ops]
   [aps.parts.stats :as stats]
   [clojure.string :as cstr]
   [clojure.test :refer [deftest is testing]]))

(deftest test-reexports-dispatch-to-live-source
  (testing "facade calls forward to the source var at call time, so a reload
            (here simulated with with-redefs) is picked up immediately"
    (with-redefs [stats/fleet-stats!                  (fn [& _] ::fleet)
                  stats/user-stats!                   (fn [& _] ::user)
                  billing/billing-status!             (fn [& _] ::billing)
                  invitations/print-invitation-links! (fn [& _] ::invites)]
      (is (= ::fleet   (ops/fleet-stats!)))
      (is (= ::user    (ops/user-stats! "jane@example.com")))
      (is (= ::billing (ops/billing-status!)))
      (is (= ::invites (ops/print-invitation-links!))))))

(deftest test-reexports-preserve-repl-help
  (testing "docstrings carry over so (doc ops/…) still works"
    (is (some? (:doc (meta #'ops/user-stats!))))
    (is (some? (:doc (meta #'ops/billing-status!)))))
  (testing "arglists carry over so arg hints still work"
    (is (= (:arglists (meta #'stats/user-stats!))
           (:arglists (meta #'ops/user-stats!))))))

(deftest test-erasure-is-not-reexported
  (testing "no console path to a destructive purge"
    (is (nil? (resolve 'aps.parts.ops/purge-account!)))))

(def ^:private invite
  {:email      "jane@example.com"
   :token      "tok"
   :magic-link "https://parts.ifs.tools/invite/tok"})

(deftest test-invite-message
  (with-redefs [conf/mail-reply-to (constantly "gosha@gosha.net")]
    (let [msg (ops/invite-message invite)]
      (testing "to the invitee; Reply-To routes replies to the operator's
                personal inbox (the sender identity is the mail layer's)"
        (is (= "jane@example.com" (:to msg)))
        (is (= "gosha@gosha.net" (:reply-to msg)))
        (is (not (contains? msg :from))))
      (testing "fixed subject"
        (is (= "Your invite to Parts, the mapping tool for IFS practitioners"
               (:subject msg))))
      (testing "magic link replaces the [LINK] placeholder in the plain-text body"
        (is (string? (:body msg)))
        (is (cstr/includes? (:body msg) "https://parts.ifs.tools/invite/tok"))
        (is (not (cstr/includes? (:body msg) "[LINK]"))))
      (testing "the body no longer claims to be sent from the personal address,
                but still promises replies reach the operator"
        (is (not (cstr/includes? (:body msg) "from my personal email address")))
        (is (cstr/includes? (:body msg) "hit reply")))))
  (testing "no Reply-To header at all when :mail/reply-to is unset"
    (with-redefs [conf/mail-reply-to (constantly nil)]
      (is (not (contains? (ops/invite-message invite) :reply-to))))))

(deftest test-invite-pending-waitlist
  (let [invite-for (fn [email] {:email      email
                                :token      "tok"
                                :magic-link (str "https://parts.ifs.tools/invite/" email)})]
    (testing "mints an invitation for every pending email and sends each"
      (let [sent (atom [])]
        (with-redefs [invitations/pending-waitlist!    (fn [] [{:email "a@example.com"}
                                                               {:email "b@example.com"}])
                      invitations/generate-invitation! invite-for
                      ops/send-invitation-email!       (fn [invite]
                                                         (swap! sent conj (:email invite))
                                                         invite)]
          (is (= {:sent ["a@example.com" "b@example.com"] :failed []}
                 (ops/invite-pending-waitlist!)))
          (is (= ["a@example.com" "b@example.com"] @sent)))))
    (testing "a failed send doesn't abort the batch — the rest still go out,
              and the failure is reported for retry"
      (with-redefs [invitations/pending-waitlist!    (fn [] [{:email "a@example.com"}
                                                             {:email "b@example.com"}])
                    invitations/generate-invitation! invite-for
                    ops/send-invitation-email!       (fn [invite]
                                                       (if (= "a@example.com" (:email invite))
                                                         (throw (ex-info "boom" {:type :smtp-error}))
                                                         invite))]
        (is (= {:sent ["b@example.com"] :failed ["a@example.com"]}
               (ops/invite-pending-waitlist!)))))))

(deftest test-send-invitation-email
  (testing "nil invite (already redeemed) is a no-op, so the
            generate-invitation! composition is safe"
    (is (nil? (ops/send-invitation-email! nil))))
  (testing "delegates the send to aps.parts.mail and returns the invite"
    (let [sent (atom nil)]
      (with-redefs [conf/mail-reply-to (constantly "gosha@gosha.net")
                    mail/send!         (fn [msg] (reset! sent msg) msg)]
        (is (= invite (ops/send-invitation-email! invite)))
        (is (= "jane@example.com" (:to @sent))))))
  (testing "a failed send propagates — the operator at the REPL must see it"
    (with-redefs [conf/mail-reply-to (constantly nil)
                  mail/send!         (fn [_]
                                       (throw (ex-info "boom" {:type :smtp-error})))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (ops/send-invitation-email! invite))))))
