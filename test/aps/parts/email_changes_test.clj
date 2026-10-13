(ns aps.parts.email-changes-test
  (:require
   [aps.parts.db :as db]
   [aps.parts.email-changes :as email-changes]
   [aps.parts.helpers.utils :refer [create-test-user! with-test-db]]
   [aps.parts.password-resets :as resets]
   [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each with-test-db)

(defn- email-of [user]
  (:email (db/query-one (db/sql-format {:select [:email] :from [:users] :where [:= :id (:id user)]}))))

(defn- refused [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-message e))))

(deftest test-request
  (let [user  (create-test-user!)
        other (create-test-user!)]
    (testing "refuses what is not an email address, or the account's own"
      (is (= "Enter a valid email address." (refused #(email-changes/request! (:id user) "not-an-address"))))
      (is (= "That is already the email address of your account."
             (refused #(email-changes/request! (:id user) (str " " (:email user) " "))))))
    (testing "another account's address is not refused (that would tell whether it
              has an account): it is marked taken, and no link is made"
      (is (= {:new-email (:email other) :taken? true}
             (email-changes/request! (:id user) (:email other))))
      (is (empty? (db/query (db/sql-format {:select [:id]                          :from [:email_changes]
                                            :where  [:= :new_email (:email other)]})))))
    (testing "a newer request replaces the older link"
      (let [first-change  (email-changes/request! (:id user) "New.Address@Example.test")
            second-change (email-changes/request! (:id user) "newer@example.test")]
        (is (= "new.address@example.test" (:new-email first-change)) "normalised")
        (is (nil? (email-changes/find-active (:token first-change))))
        (is (some? (email-changes/find-active (:token second-change))))
        (is (= (email-changes/confirm-url (:token second-change)) (:url second-change)))))))

(deftest test-confirm
  (testing "moves the account, once, and cancels open reset links"
    (let [user   (create-test-user!)
          reset  (resets/create-reset! (:email user))
          change (email-changes/request! (:id user) "moved@example.test")
          result (email-changes/confirm! (:token change))]
      (is (= {:user-id (:id user) :old-email (:email user) :new-email "moved@example.test"} result))
      (is (= "moved@example.test" (email-of user)))
      (is (= (:email user)
             (:old_email (db/query-one (db/sql-format {:select [:old_email]                :from [:email_changes]
                                                       :where  [:= :token (:token change)]}))))
          "the previous address is kept with the change")
      (is (nil? (resets/find-active (:token reset))) "the reset link went to the old address")
      (is (= :link-unavailable
             (try (email-changes/confirm! (:token change)) nil
                  (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))
          "single-use")))
  (testing "an expired link does nothing"
    (let [user   (create-test-user!)
          change (email-changes/request! (:id user) "late@example.test")]
      (db/update! :email_changes {:expires_at [:- [:now] [:cast "1 minute" :interval]]}
                  [:= :token (:token change)])
      (is (= :link-unavailable
             (try (email-changes/confirm! (:token change)) nil
                  (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
      (is (= (:email user) (email-of user)))))
  (testing "an address taken after the request is refused, and nothing changes"
    (let [user   (create-test-user!)
          change (email-changes/request! (:id user) "contested@example.test")
          _      (create-test-user! {:email "contested@example.test"})]
      (is (= :email-taken
             (try (email-changes/confirm! (:token change)) nil
                  (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
      (is (= (:email user) (email-of user))))))

(deftest test-confirm-moves-email-keyed-rows
  (testing "the invitation and waiting-list rows follow the account, so erasure finds them"
    (let [user   (create-test-user!)
          _      (db/insert! :invitations {:email (:email user) :token (str (random-uuid))})
          _      (db/insert! :waitlist_signups {:email (:email user)})
          change (email-changes/request! (:id user) "follow@example.test")
          rows   (fn [table email] (db/query (db/sql-format {:select [:id]             :from [table]
                                                             :where  [:= :email email]})))]
      (email-changes/confirm! (:token change))
      (is (seq (rows :invitations "follow@example.test")))
      (is (seq (rows :waitlist_signups "follow@example.test")))
      (is (empty? (rows :invitations (:email user))))
      (is (empty? (rows :waitlist_signups (:email user)))))))

(deftest test-confirm-race
  (testing "an address taken at the same moment is the :email-taken refusal, not a
            database error"
    (let [user   (create-test-user!)
          change (email-changes/request! (:id user) "race@example.test")]
      (create-test-user! {:email "race@example.test"})
      ;; Skip the early check, as a concurrent writer would.
      (with-redefs-fn {#'email-changes/email-taken? (constantly nil)}
        #(is (= :email-taken
                (try (email-changes/confirm! (:token change)) nil
                     (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))))))

(deftest test-sweep
  (let [user      (create-test-user!)
        confirmed (email-changes/request! (:id user) "swept@example.test")
        _         (email-changes/confirm! (:token confirmed))
        unused    (email-changes/request! (:id user) "never@example.test")
        exists?   #(some? (db/query-one (db/sql-format {:select [:id]                  :from [:email_changes]
                                                        :where  [:= :token (:token %)]})))
        age!      (fn [change col interval]
                    (db/update! :email_changes {col [:- [:now] [:cast interval :interval]]}
                                [:= :token (:token change)]))]
    (testing "an unused link goes a week after it expired; a confirmed change stays"
      (age! unused :expires_at "8 days")
      (age! confirmed :expires_at "8 days")
      (age! confirmed :used_at "8 days")
      (email-changes/delete-expired! db/datasource)
      (is (not (exists? unused)))
      (is (exists? confirmed) "kept 30 days after the change"))
    (testing "a confirmed change goes 30 days after it was used"
      (age! confirmed :used_at "31 days")
      (email-changes/delete-expired! db/datasource)
      (is (not (exists? confirmed))))))

(deftest test-erased-with-the-account
  (let [user (create-test-user!)]
    (email-changes/request! (:id user) "gone@example.test")
    (db/delete! :users [:= :id (:id user)])
    (is (empty? (db/query (db/sql-format {:select [:id]                    :from [:email_changes]
                                          :where  [:= :user_id (:id user)]}))))))
