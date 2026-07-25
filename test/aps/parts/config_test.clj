(ns aps.parts.config-test
  (:require
   [aps.parts.config :as config]
   [clojure.test :refer [deftest is testing]]
   [lambdaisland.config :as l-config]))

(defn- fake-config
  "A stand-in for `l-config/get` that resolves keys from the map `m`, so a
   test can describe an environment without touching real env vars."
  [m]
  (fn [_config k] (get m k)))

(def ^:private relay-env
  {:smtp/host     "smtp.tem.scw.cloud"
   :smtp/user     "project-id"
   :smtp/password "api-key"})

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
  (testing "returns nil when SMTP env is unconfigured — mail and alerting stay off by default"
    (is (nil? (config/smtp-config))))
  (testing "relay credentials only — no alert recipient required (TASK-014 split)"
    (with-redefs [l-config/get (fake-config relay-env)]
      (is (= {:host "smtp.tem.scw.cloud"
              :port 465
              :user "project-id"
              :pass "api-key"}
             (config/smtp-config)))))
  (testing "string port from the env is coerced; 587 selects the submission port"
    (with-redefs [l-config/get (fake-config (assoc relay-env :smtp/port "587"))]
      (is (= 587 (:port (config/smtp-config)))))))

(deftest test-alert-config
  (testing "nil without relay credentials"
    (is (nil? (config/alert-config))))
  (testing "nil without a recipient — creds alone don't switch alerting on"
    (with-redefs [l-config/get (fake-config relay-env)]
      (is (nil? (config/alert-config)))))
  (testing "relay creds plus recipient; :from defaults to the SMTP user"
    (with-redefs [l-config/get (fake-config (assoc relay-env :alert/to "op@example.com"))]
      (is (= {:host "smtp.tem.scw.cloud"
              :port 465
              :user "project-id"
              :pass "api-key"
              :to   "op@example.com"
              :from "project-id"}
             (config/alert-config)))))
  (testing ":alert/from overrides the default sender"
    (with-redefs [l-config/get (fake-config (assoc relay-env
                                                   :alert/to "op@example.com"
                                                   :alert/from "alerts@ifs.tools"))]
      (is (= "alerts@ifs.tools" (:from (config/alert-config))))))
  (testing ":mail/from beats the SMTP-user fallback — the user is a bare
            project id on Scaleway, not a sendable address"
    (with-redefs [l-config/get (fake-config (assoc relay-env
                                                   :alert/to "op@example.com"
                                                   :mail/from "Gosha <gosha@ifs.tools>"))]
      (is (= "Gosha <gosha@ifs.tools>" (:from (config/alert-config))))))
  (testing ":alert/from still wins over :mail/from"
    (with-redefs [l-config/get (fake-config (assoc relay-env
                                                   :alert/to "op@example.com"
                                                   :mail/from "Gosha <gosha@ifs.tools>"
                                                   :alert/from "alerts@ifs.tools"))]
      (is (= "alerts@ifs.tools" (:from (config/alert-config)))))))

(deftest test-mail-from-and-reply-to
  (testing "nil when unconfigured — the mail layer refuses to send without a sender"
    (is (nil? (config/mail-from)))
    (is (nil? (config/mail-reply-to))))
  (testing "read from :mail/from and :mail/reply-to (PARTS__MAIL__*)"
    (with-redefs [l-config/get (fake-config {:mail/from     "Gosha <gosha@ifs.tools>"
                                             :mail/reply-to "gosha@gosha.net"})]
      (is (= "Gosha <gosha@ifs.tools>" (config/mail-from)))
      (is (= "gosha@gosha.net" (config/mail-reply-to))))))
