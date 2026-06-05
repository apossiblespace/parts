(ns aps.parts.alerts-test
  (:require
   [aps.parts.alerts :as alerts]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def ^:private cooldown
  "15 minutes in milliseconds — the production throttle window."
  (* 15 60 1000))

(defn- ev
  "A minimal mulog-shaped event: an event name, a timestamp (ms), and any
   extra fields the signature cares about (`:error`, `:sql-state`)."
  [name ts & {:as extra}]
  (merge {:mulog/event-name name :mulog/timestamp ts} extra))

(deftest postal-connection-test
  (testing "port 587 selects STARTTLS (:tls) — connect plaintext, then upgrade"
    (let [conn (#'alerts/postal-connection {:host "h" :port 587 :user "u" :pass "p"})]
      (is (true? (:tls conn)))
      (is (nil? (:ssl conn)))))
  (testing "port 465 (and any other) selects implicit SSL (:ssl)"
    (is (true? (:ssl (#'alerts/postal-connection {:host "h" :port 465 :user "u" :pass "p"}))))
    (is (true? (:ssl (#'alerts/postal-connection {:host "h" :port 25 :user "u" :pass "p"}))))))

(deftest alert-decision-allowlist-test
  (testing "an allowlisted event with empty state sends and records its signature"
    (let [e                     (ev :aps.parts.errors/unhandled-exception 1000 :error "boom")
          {:keys [send? state]} (alerts/alert-decision {} e cooldown)]
      (is send?)
      (is (= 1 (count state)))))
  (testing "a non-allowlisted event never sends and leaves state untouched"
    (let [e                     (ev :aps.parts.other/heartbeat 1000 :error "noise")
          {:keys [send? state]} (alerts/alert-decision {:x 1} e cooldown)]
      (is (not send?))
      (is (= {:x 1} state)))))

(deftest alert-decision-cooldown-test
  (testing "the same signature within the cooldown window is suppressed"
    (let [e1              (ev :aps.parts.errors/unhandled-exception 1000 :error "boom")
          s1              (:state (alerts/alert-decision {} e1 cooldown))
          e2              (ev :aps.parts.errors/unhandled-exception (+ 1000 60000) :error "boom")
          {:keys [send?]} (alerts/alert-decision s1 e2 cooldown)]
      (is (not send?))))
  (testing "the same signature after the cooldown elapses sends again"
    (let [e1              (ev :aps.parts.errors/unhandled-exception 1000 :error "boom")
          s1              (:state (alerts/alert-decision {} e1 cooldown))
          e2              (ev :aps.parts.errors/unhandled-exception (+ 1000 cooldown 1) :error "boom")
          {:keys [send?]} (alerts/alert-decision s1 e2 cooldown)]
      (is send?))))

(deftest alert-decision-signature-test
  (testing "postgres errors collapse on sql-state, not the row-specific message"
    (let [e1              (ev :aps.parts.errors/postgres-exception 1000
                              :error "Key (email)=(a@x.com) already exists" :sql-state "23505")
          s1              (:state (alerts/alert-decision {} e1 cooldown))
          e2              (ev :aps.parts.errors/postgres-exception (+ 1000 60000)
                              :error "Key (email)=(b@x.com) already exists" :sql-state "23505")
          {:keys [send?]} (alerts/alert-decision s1 e2 cooldown)]
      (is (not send?) "same sql-state within cooldown is one alert despite different messages")))
  (testing "distinct signatures each send"
    (let [e1              (ev :aps.parts.errors/unhandled-exception 1000 :error "boom-a")
          s1              (:state (alerts/alert-decision {} e1 cooldown))
          e2              (ev :aps.parts.errors/unhandled-exception 1001 :error "boom-b")
          {:keys [send?]} (alerts/alert-decision s1 e2 cooldown)]
      (is send?))))

(deftest alert-body-whitelist-test
  (testing "the email body includes only allowlisted structural fields — never a
            full-event dump that could carry clinical content"
    (let [event {:mulog/event-name :aps.parts.errors/batch-failure
                 :mulog/timestamp  123
                 :sql-state        "23514"
                 :failing-change   {:entity :part :type :update :id "p1"}
                 ;; stray fields that must NOT reach the operator's inbox:
                 :client-notes     "CLINICAL SECRET"
                 :data             {:notes "ALSO SECRET"}}
          body  (#'alerts/alert-body event)]
      (is (str/includes? body "batch-failure") "keeps the event name")
      (is (str/includes? body "23514") "keeps the sql-state")
      (is (str/includes? body "p1") "keeps the redacted change id")
      (is (not (str/includes? body "CLINICAL SECRET")) "drops unknown fields")
      (is (not (str/includes? body "ALSO SECRET")) "drops :data"))))

(deftest alert-decision-error-class-signature-test
  (testing "events without a sql-state collapse on :error-class, not the message"
    (let [e1              (ev :aps.parts.errors/batch-failure 1000 :error-class "PSQLException")
          s1              (:state (alerts/alert-decision {} e1 cooldown))
          e2              (ev :aps.parts.errors/batch-failure (+ 1000 60000) :error-class "PSQLException")
          {:keys [send?]} (alerts/alert-decision s1 e2 cooldown)]
      (is (not send?) "same error-class within cooldown is one alert")))
  (testing "distinct error-classes each send"
    (let [e1              (ev :aps.parts.errors/batch-failure 1000 :error-class "PSQLException")
          s1              (:state (alerts/alert-decision {} e1 cooldown))
          e2              (ev :aps.parts.errors/batch-failure 1001 :error-class "ExceptionInfo")
          {:keys [send?]} (alerts/alert-decision s1 e2 cooldown)]
      (is send?))))

(deftest alert-decision-prune-test
  (testing "entries last sent before the window are pruned from the returned state"
    (let [stale           {[:aps.parts.errors/unhandled-exception "old"] 1000}
          e               (ev :aps.parts.errors/unhandled-exception (+ 1000 cooldown 1) :error "fresh")
          {:keys [state]} (alerts/alert-decision stale e cooldown)]
      (is (= #{[:aps.parts.errors/unhandled-exception "fresh"]}
             (set (keys state)))))))
