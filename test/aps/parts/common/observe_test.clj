(ns aps.parts.common.observe-test
  (:require
   [aps.parts.common.observe :as observe]
   [clojure.test :refer [deftest is testing]]))

(deftest redact-event-test
  (testing "values at sensitive keys are replaced, keys preserved"
    (let [e (observe/redact-event {:mulog/event-name ::login
                                   :password         "hunter2"
                                   :token            "a-credential"
                                   :status           :failure})]
      (is (= "[REDACTED]" (:password e)))
      (is (= "[REDACTED]" (:token e)))
      (is (= :failure (:status e)))
      (is (contains? e :password))))

  (testing "email is kept (disclosed loggable; useful for forensics)"
    (let [e (observe/redact-event {:mulog/event-name ::login
                                   :email            "person@example.com"
                                   :status           :failure})]
      (is (= "person@example.com" (:email e)))))

  (testing "the invite token in a URI path is scrubbed (not reachable by key)"
    (let [e (observe/redact-event
             {:mulog/event-name ::request
              :info             {:uri "/invite/3b1f-secret-token-9c2a"}})]
      (is (= "/invite/[REDACTED]" (get-in e [:info :uri])))))

  (testing "technical fields pass through"
    (let [e (observe/redact-event
             {:mulog/event-name ::request
              :info             {:uri "/api/account" :remote-addr "203.0.113.7"}
              :user-id          "a-uuid"})]
      (is (= "203.0.113.7" (get-in e [:info :remote-addr])))
      (is (= "a-uuid" (:user-id e)))))

  (testing "an ordinary event is unchanged"
    (let [e {:mulog/event-name ::process-changes :map-id "m1" :change-count 3}]
      (is (= e (observe/redact-event e))))))

(deftest mulog-transform-test
  (testing "applies redact-event across a sequence of events"
    (let [events [{:password "p"}
                  {:info {:uri "/invite/tok"}}
                  {:change-count 2}]
          out    (observe/mulog-transform events)]
      (is (= ["[REDACTED]" "/invite/[REDACTED]" 2]
             [(:password (nth out 0))
              (get-in (nth out 1) [:info :uri])
              (:change-count (nth out 2))])))))
