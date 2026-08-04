(ns aps.parts.mail-test
  (:require
   [aps.parts.config :as conf]
   [aps.parts.mail :as mail]
   [clojure.test :refer [deftest is testing]]
   [postal.core :as postal]))

(def ^:private smtp
  {:host "smtp.tem.scw.cloud" :port 465 :user "project-id" :pass "api-key"})

(def ^:private message
  {:to "jane@example.com" :subject "Hello" :body "Hi Jane"})

(defn- ex-type
  "The :type key of an ExceptionInfo thrown by `f`, or nil if nothing threw."
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))

(deftest test-send-refuses-without-relay
  (testing "throws :config-error when the SMTP relay is unconfigured —
            a silent drop would strand the user waiting for mail"
    (with-redefs [conf/smtp-config (constantly nil)]
      (is (= :config-error (ex-type #(mail/send! message)))))))

(deftest test-send-refuses-without-sender
  (testing "throws :config-error when no :from is given and :mail/from is unset"
    (with-redefs [conf/smtp-config (constantly smtp)
                  conf/mail-from   (constantly nil)]
      (is (= :config-error (ex-type #(mail/send! message)))))))

(deftest test-send
  (let [sent (atom nil)]
    (with-redefs [conf/smtp-config    (constantly smtp)
                  conf/mail-from      (constantly "Gosha <gosha@ifs.tools>")
                  postal/send-message (fn [conn msg]
                                        (reset! sent {:conn conn :msg msg})
                                        {:code 0 :error :SUCCESS})]
      (testing "fills :from from config and returns the message as sent"
        (let [msg (mail/send! message)]
          (is (= "Gosha <gosha@ifs.tools>" (:from msg)))
          (is (= msg (:msg @sent)))
          (is (= "jane@example.com" (get-in @sent [:msg :to])))))
      (testing "an explicit :from wins over the configured default"
        (is (= "other@ifs.tools"
               (:from (mail/send! (assoc message :from "other@ifs.tools"))))))
      (testing "port 465 connects with implicit SSL"
        (mail/send! message)
        (is (true? (get-in @sent [:conn :ssl])))))))

(deftest test-send-transport-flag-follows-port
  (let [sent (atom nil)]
    (with-redefs [conf/smtp-config    (constantly (assoc smtp :port 587))
                  conf/mail-from      (constantly "Gosha <gosha@ifs.tools>")
                  postal/send-message (fn [conn msg]
                                        (reset! sent {:conn conn :msg msg})
                                        {:code 0 :error :SUCCESS})]
      (testing "port 587 connects with STARTTLS"
        (mail/send! message)
        (is (true? (get-in @sent [:conn :tls])))
        (is (nil? (get-in @sent [:conn :ssl])))))))

(deftest test-send-surfaces-relay-failure
  (testing "a non-SUCCESS postal result throws :smtp-error rather than
            returning normally — callers must see a failed send"
    (with-redefs [conf/smtp-config    (constantly smtp)
                  conf/mail-from      (constantly "Gosha <gosha@ifs.tools>")
                  postal/send-message (constantly {:code 99 :error :FAILURE})]
      (is (= :smtp-error (ex-type #(mail/send! message)))))))

(deftest test-sender-identity-wrappers
  (let [sent (atom nil)]
    (with-redefs [conf/smtp-config    (constantly smtp)
                  conf/mail-from      (constantly "Gosha <gosha@ifs.tools>")
                  postal/send-message (fn [_conn msg] (reset! sent msg) {:error :SUCCESS})]

      (testing "send-personal! keeps the default From and stamps the operator Reply-To"
        (with-redefs [conf/mail-reply-to (constantly "gosha@gosha.net")]
          (mail/send-personal! message)
          (is (= "Gosha <gosha@ifs.tools>" (:from @sent)))
          (is (= "gosha@gosha.net" (:reply-to @sent)))))

      (testing "send-system! sends from the system identity with no Reply-To"
        (with-redefs [conf/mail-system-from (constantly "Parts <help@ifs.tools>")]
          (mail/send-system! message)
          (is (= "Parts <help@ifs.tools>" (:from @sent)))
          (is (not (contains? @sent :reply-to)))))

      (testing "both fall back to the default sender when their config is unset"
        (with-redefs [conf/mail-reply-to    (constantly nil)
                      conf/mail-system-from (constantly nil)]
          (mail/send-personal! message)
          (is (= "Gosha <gosha@ifs.tools>" (:from @sent)))
          (is (not (contains? @sent :reply-to)))
          (mail/send-system! message)
          (is (= "Gosha <gosha@ifs.tools>" (:from @sent))))))))
