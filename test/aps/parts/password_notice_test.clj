(ns aps.parts.password-notice-test
  (:require
   [aps.parts.config :as conf]
   [aps.parts.mail :as mail]
   [aps.parts.password-notice :as notice]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]])
  (:import
   (java.time Instant)))

(deftest test-message
  (with-redefs [conf/base-url             (constantly "https://parts.example")
                conf/mail-sender-identity (constantly "Example Ltd · Company no. 123")]
    (let [message              (notice/message "jane@example.com" (Instant/parse "2026-10-10T14:05:09Z"))
          [subtype plain html] (:body message)]
      (testing "is a multipart email to the account"
        (is (= "jane@example.com" (:to message)))
        (is (= "Your Parts password was changed" (:subject message)))
        (is (= :alternative subtype)))
      (testing "says when the password changed, in UTC"
        (is (str/includes? (:content plain) "changed on 10 October 2026 at 14:05 UTC."))
        (is (str/includes? (:content html) "changed on 10 October 2026 at 14:05 UTC.")))
      (testing "tells the User to email support if they did not do it"
        (is (str/includes? (:content plain) "please email us at help@ifs.tools"))
        (is (re-find #"href=\"mailto:help(@|&#64;)ifs\.tools\"" (:content html))))
      (testing "ends with the transactional footer"
        (is (str/includes? (:content plain) "This email was sent to jane@example.com about your Parts account."))
        (is (str/includes? (:content html) "Example Ltd · Company no. 123"))))))

(deftest test-send-failure
  (testing "a mail failure is caught, so it cannot fail the password change"
    (with-redefs [mail/send! (fn [_] (throw (ex-info "relay down" {})))]
      (is (nil? @(notice/send! "u1" "jane@example.com"))))))
