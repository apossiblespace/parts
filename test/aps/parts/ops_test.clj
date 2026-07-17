(ns aps.parts.ops-test
  (:require
   [aps.parts.billing :as billing]
   [aps.parts.config :as conf]
   [aps.parts.invitations :as invitations]
   [aps.parts.ops :as ops]
   [aps.parts.stats :as stats]
   [clojure.string :as cstr]
   [clojure.test :refer [deftest is testing]]
   [postal.core :as postal]))

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
  (let [msg (ops/invite-message invite)]
    (testing "from the operator's personal address, to the invitee"
      (is (= "gosha@gosha.net" (:from msg)))
      (is (= "jane@example.com" (:to msg))))
    (testing "fixed subject"
      (is (= "Your invite to Parts, the mapping tool for IFS practitioners"
             (:subject msg))))
    (testing "magic link replaces the [LINK] placeholder in the plain-text body"
      (is (string? (:body msg)))
      (is (cstr/includes? (:body msg) "https://parts.ifs.tools/invite/tok"))
      (is (not (cstr/includes? (:body msg) "[LINK]"))))))

(deftest test-send-invitation-email
  (testing "nil invite (already redeemed) is a no-op, so the
            generate-invitation! composition is safe"
    (is (nil? (ops/send-invitation-email! nil))))
  (testing "throws rather than silently dropping when SMTP is unconfigured"
    (with-redefs [conf/smtp-config (constantly nil)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (ops/send-invitation-email! invite)))))
  (testing "sends one message over the configured SMTP and returns the invite"
    (let [sent (atom nil)]
      (with-redefs [conf/smtp-config    (constantly {:host "smtp.example.com"
                                                     :port 465
                                                     :user "op"
                                                     :pass "pw"
                                                     :to   "alerts@example.com"
                                                     :from "alerts@example.com"})
                    postal/send-message (fn [conn msg]
                                          (reset! sent {:conn conn :msg msg})
                                          {:code 0 :error :SUCCESS})]
        (is (= invite (ops/send-invitation-email! invite)))
        (is (= "jane@example.com" (get-in @sent [:msg :to])))
        (testing "465 means implicit SSL on the connection"
          (is (true? (get-in @sent [:conn :ssl]))))))))
