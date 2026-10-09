(ns aps.parts.operator-email-test
  (:require
   [aps.parts.config :as conf]
   [aps.parts.mail :as mail]
   [aps.parts.operator-email :as email]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [postal.message :as postal]))

(def ^:private url "https://parts.example/unsubscribe/abc")

(defn- draft [kind]
  {:kind kind :subject "News" :body "Hello **there**.\n\n<script>alert(1)</script>"})

(defn- parts [message]
  (let [[subtype plain html] (:body message)]
    {:subtype subtype :plain plain :html html}))

(deftest test-product-update-message
  (let [message                      (email/message (draft "product-update") {:to "a@example.com" :unsubscribe-url url})
        {:keys [subtype plain html]} (parts message)]
    (testing "is multipart alternative with a plain and an html part"
      (is (= :alternative subtype))
      (is (str/starts-with? (:type plain) "text/plain"))
      (is (str/starts-with? (:type html) "text/html")))
    (testing "renders the markdown as plain text with the unsubscribe footer"
      (is (str/includes? (:content plain) "Hello there."))
      (is (str/includes? (:content plain) (str "Unsubscribe: " url))))
    (testing "renders markdown and links the unsubscribe url in the html part"
      (is (str/includes? (:content html) "<strong>there</strong>"))
      (is (str/includes? (:content html) url)))
    (testing "removes script from the html part"
      (is (not (str/includes? (str/lower-case (:content html)) "<script"))))
    (testing "carries the one-click unsubscribe headers"
      (is (= (str "<" url ">") (get message "List-Unsubscribe")))
      (is (= "List-Unsubscribe=One-Click" (get message "List-Unsubscribe-Post"))))
    (testing "postal turns the map into a multipart message with the headers"
      (let [raw (postal/message->str (assoc message :from "op@example.com"))]
        (is (str/includes? raw "multipart/alternative"))
        (is (str/includes? raw "List-Unsubscribe-Post: List-Unsubscribe=One-Click"))))))

(deftest test-service-notice-message
  (let [message              (email/message (draft "service-notice") {:to "a@example.com" :unsubscribe-url url})
        {:keys [plain html]} (parts message)]
    (testing "carries the service notice footer and no unsubscribe"
      (is (str/includes? (:content plain) "This is a service notice about your Parts account."))
      (is (not (str/includes? (:content plain) url)))
      (is (not (str/includes? (:content html) url)))
      (is (not (contains? message "List-Unsubscribe"))))))

(deftest test-draft-hash
  (let [base (draft "product-update")]
    (testing "is equal for equal drafts"
      (is (= (email/draft-hash base) (email/draft-hash (into {} base)))))
    (testing "changes when the kind, the subject or the body changes"
      (doseq [k [:kind :subject :body]]
        (is (not= (email/draft-hash base)
                  (email/draft-hash (update base k str "x")))
            (name k))))))

(deftest test-validate
  (testing "accepts a complete draft"
    (is (nil? (email/validate (draft "service-notice")))))
  (testing "names the missing field"
    (is (= "Write a subject." (email/validate (assoc (draft "product-update") :subject " "))))
    (is (= "Write the email." (email/validate (assoc (draft "product-update") :body ""))))))

(deftest test-send-test
  (let [sent (atom nil)]
    (with-redefs [conf/mail-reply-to (constantly "op@example.com")
                  conf/base-url      (constantly "https://parts.example")
                  mail/send!         (fn [message] (reset! sent message))]
      (testing "sends the draft to the operator with a test prefix and a dummy link"
        (is (= "op@example.com" (email/send-test! (draft "product-update"))))
        (is (= "op@example.com" (:to @sent)))
        (is (= "[Test] News" (:subject @sent)))
        (is (= "<https://parts.example/unsubscribe/test>" (get @sent "List-Unsubscribe")))))
    (with-redefs [conf/mail-reply-to (constantly nil)]
      (testing "refuses when no operator address is configured"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No operator address"
                              (email/send-test! (draft "product-update"))))))))
