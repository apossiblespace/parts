(ns aps.parts.handlers.waitlist-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [aps.parts.db :as db]
   [aps.parts.handlers.waitlist :as waitlist]
   [aps.parts.helpers.utils :refer [with-test-db]]
   [aps.parts.middleware :as middleware]))

(use-fixtures :once with-test-db)

(deftest test-signup
  (testing "saves the submitted email address in the database"
    (let [mock-request {:form-params {"email" "someone@somewhere.com"}}
          wrapped-handler (middleware/wrap-html-response waitlist/signup)
          response (wrapped-handler mock-request)
          record (db/query-one (db/sql-format {:select [:*]
                                               :from [:waitlist_signups]
                                               :where [:= :email "someone@somewhere.com"]}))]
      (is (= 201 (:status response)))
      (is (= "someone@somewhere.com" (:email record)))
      (is (str/includes? (:body response) "Thank you for your interest in Parts!"))))

  (testing "returns a reminder to not forget the email when email is missing"
    (let [mock-request {:form-params {"email" ""}}
          wrapped-handler (middleware/wrap-html-response waitlist/signup)
          response (wrapped-handler mock-request)]
      (is (= 400 (:status response)))
      (is (str/includes? (:body response) "Please don&apos;t forget your email address!"))))

  (testing "returns an error when email is not valid"
    (let [mock-request {:form-params {"email" "invalid-email"}}
          wrapped-handler (middleware/wrap-html-response waitlist/signup)
          response (wrapped-handler mock-request)]
      (is (= 400 (:status response)))
      (is (str/includes? (:body response) "Sorry, that&apos;s not a valid email address."))))

  (testing "returns a notice that the person is already on list when email is a duplicate"
    (let [email "duplicate@somewhere.com"
          _ (db/insert! :waitlist_signups {:email email})
          mock-request {:form-params {"email" email}}
          wrapped-handler (middleware/wrap-html-response waitlist/signup)
          response (wrapped-handler mock-request)]
      (is (= 409 (:status response)))
      (is (str/includes? (:body response) "You&apos;re already on the list! We&apos;ll be in touch soon.")))))
