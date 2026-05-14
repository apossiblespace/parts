(ns aps.parts.config-test
  (:require
   [aps.parts.config :as config]
   [clojure.test :refer [deftest is testing]]))

(deftest test-parse-port
  (testing "coerces string env-var values (PARTS__HTTP__PORT) to a long"
    (is (= 3001 (#'config/parse-port "3001"))))
  (testing "passes numeric config.edn defaults through unchanged"
    (is (= 3000 (#'config/parse-port 3000)))))
