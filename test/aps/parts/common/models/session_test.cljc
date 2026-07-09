(ns aps.parts.common.models.session-test
  (:require
   #?(:clj [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer-macros [deftest is testing]])
   [aps.parts.common.models.session :as session]
   [clojure.spec.alpha :as s]))

(def ^:private valid
  {:map_id          "map-1"
   :ordinal         1
   :anchor_valid_at #inst "2026-07-08T10:00:00Z"})

(deftest session-spec-test
  (testing "map_id, ordinal, and anchor are required"
    (is (s/valid? session/spec valid))
    (is (not (s/valid? session/spec (dissoc valid :map_id))))
    (is (not (s/valid? session/spec (dissoc valid :ordinal))))
    (is (not (s/valid? session/spec (dissoc valid :anchor_valid_at)))))

  (testing "ordinal is a positive integer"
    (is (not (s/valid? session/spec (assoc valid :ordinal 0))))
    (is (not (s/valid? session/spec (assoc valid :ordinal "1")))))

  (testing "trigger is optional and nullable free text"
    (is (s/valid? session/spec (assoc valid :trigger "conflict with mother")))
    (is (s/valid? session/spec (assoc valid :trigger nil)))
    (is (not (s/valid? session/spec (assoc valid :trigger 42))))))
