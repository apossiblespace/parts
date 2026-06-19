(ns aps.parts.ops-test
  (:require
   [aps.parts.billing :as billing]
   [aps.parts.invitations :as invitations]
   [aps.parts.ops :as ops]
   [aps.parts.stats :as stats]
   [clojure.test :refer [deftest is testing]]))

(deftest test-reexports-point-at-sources
  (testing "facade vars are the very same functions as their sources"
    (is (identical? @#'stats/fleet-stats!     @#'ops/fleet-stats!))
    (is (identical? @#'stats/user-stats!      @#'ops/user-stats!))
    (is (identical? @#'billing/billing-status! @#'ops/billing-status!))
    (is (identical? @#'invitations/print-invitation-links!
                    @#'ops/print-invitation-links!))))

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
