(ns tools.ifs.parts.waitlist-test
  (:require [tools.ifs.parts.waitlist :as waitlist]
            [tools.ifs.helpers.test-helpers :refer [with-test-db]]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [tools.ifs.parts.api.middleware :as middleware]
            [tools.ifs.parts.db :as db]))

(use-fixtures :once with-test-db)

(deftest test-signup
  (testing "saves the submitted email address in the database"
    (let [attrs {:email "someone@somewhere.com"}
          mock-request {:body attrs}
          wrapped-handler (middleware/wrap-html-response waitlist/signup)
          response (wrapped-handler mock-request)
          record (db/query-one (db/sql-format {:select [:*]
                                               :from [:waitlist_signups]
                                               :where [:= :email (:email attrs)]}))]
      (is (= 201 (:status response)))
      (is (= "someone@somewhere.com" (:email record)))
      (is (= "<div id=\"thankyou\"><p>Thank you for your interest!</p></div>" (:body response))))))
