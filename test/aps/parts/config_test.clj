(ns aps.parts.config-test
  (:require
   [aps.parts.config :as config]
   [clojure.test :refer [deftest is testing]]))

(deftest test-parse-port
  (testing "coerces string env-var values (PARTS__HTTP__PORT) to a long"
    (is (= 3001 (#'config/parse-port "3001"))))
  (testing "passes numeric config.edn defaults through unchanged"
    (is (= 3000 (#'config/parse-port 3000)))))

(deftest test-parse-bool
  (testing "coerces string env-var values to a boolean"
    (is (true?  (config/parse-bool "true")))
    (is (false? (config/parse-bool "false"))))
  (testing "passes actual booleans from config.edn defaults through unchanged"
    (is (true?  (config/parse-bool true)))
    (is (false? (config/parse-bool false)))))

(deftest test-assert-db-topology
  (testing "prod + no TLS + remote host is refused"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Refusing cleartext"
                          (config/assert-db-topology!
                           {:host "db.example.com" :ssl false :prod? true}))))
  (testing "prod + loopback without TLS is the deliberate shape — allowed"
    (is (true? (config/assert-db-topology!
                {:host "localhost" :ssl false :prod? true})))
    (is (true? (config/assert-db-topology!
                {:host "127.0.0.1" :ssl false :prod? true}))))
  (testing "prod + remote host with TLS is allowed"
    (is (true? (config/assert-db-topology!
                {:host "db.example.com" :ssl true :prod? true}))))
  (testing "outside prod the guard does not apply"
    (is (true? (config/assert-db-topology!
                {:host "db.example.com" :ssl false :prod? false})))))

(deftest test-smtp-config
  (testing "returns nil when SMTP env is unconfigured — alerting stays off by default"
    (is (nil? (config/smtp-config)))))
