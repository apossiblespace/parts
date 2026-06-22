(ns aps.parts.ops-test
  (:require
   [aps.parts.billing :as billing]
   [aps.parts.invitations :as invitations]
   [aps.parts.ops :as ops]
   [aps.parts.stats :as stats]
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
