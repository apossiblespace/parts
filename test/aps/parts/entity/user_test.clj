(ns aps.parts.entity.user-test
  (:require
   [aps.parts.db :as db]
   [aps.parts.entity.map :as parts-map]
   [aps.parts.entity.user :as user]
   [aps.parts.helpers.test-factory :as factory]
   [aps.parts.helpers.utils :refer [create-test-user! with-test-db]]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]])
  (:import
   (java.time LocalDate)))

(use-fixtures :once with-test-db)

(deftest test-fetch
  (testing "returns a user entity when a valid ID is passed"
    (let [db-user      (create-test-user!)
          fetched-user (user/fetch (:id db-user))]
      (is (= (:id db-user) (:id fetched-user)))))

  (testing "throws when a non-existent user ID is passed"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"User not found"
                          (user/fetch "deadbeef-dead-beef-dead-beefdeadbeef"))))

  (testing "throws when an invalid UUID string is passed"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid UUID format"
                          (user/fetch "random")))))

(deftest test-update!
  (testing "saves the user entity to the database"
    (let [db-user      (create-test-user!)
          updated-user (user/update! (:id db-user) {:display_name "Bobby"})]
      (is (= (:display_name updated-user) "Bobby"))))

  (testing "throws when an empty params map is passed"
    (let [db-user (create-test-user!)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Nothing to update"
                            (user/update! (:id db-user) {})))))

  (testing "throws when passed a field that does not allow updates"
    (let [db-user (create-test-user!)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Nothing to update"
                            (user/update! (:id db-user) {:role "therapist"})))))

  (testing "throws when a password is passed without confirmation"
    (let [db-user (create-test-user!)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Password and confirmation do not match"
                            (user/update! (:id db-user) {:password "password12345"})))))

  (testing "throws when the passed password and confirmation do not match"
    (let [db-user (create-test-user!)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Password and confirmation do not match"
                            (user/update!
                             (:id db-user)
                             {:password              "password12345"
                              :password_confirmation "wordpass54321"})))))

  (testing "throws when no ID is passed"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing User ID"
                          (user/update!
                           nil
                           {:password              "password12345"
                            :password_confirmation "password12345"})))))

(deftest test-create!
  (testing "creates the user entity in the database"
    (let [attrs                             (factory/build-test-user)
          {:keys [email display_name role]} attrs
          created-user                      (user/create! attrs)]
      (is (contains? created-user :id))
      (is (= email (:email created-user)))
      (is (= display_name (:display_name created-user)))
      (is (= role (:role created-user)))))

  (testing "throws when an empty params map is passed"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Nothing to update"
                          (user/create! {}))))

  (testing "ignores attrs outside the create allowlist (no mass-assignment)"
    ;; A caller that spreads untrusted input must not be able to set columns
    ;; like paid_through_date. Allowlisted fields persist; the rest are dropped.
    (let [attrs   (assoc (factory/build-test-user)
                         :paid_through_date (LocalDate/parse "2999-01-01"))
          created (user/create! attrs)
          db-user (db/query-one
                   (db/sql-format {:select [:paid_through_date]
                                   :from   [:users]
                                   :where  [:= :id (:id created)]}))]
      (is (nil? (:paid_through_date db-user))
          "a non-allowlisted column must not be writable through create!")))

  (testing "throws when a password is passed without confirmation"
    (let [attrs (factory/build-test-user)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Password and confirmation do not match"
                            (user/create! (dissoc attrs :password_confirmation))))))

  (testing "throws when the passed password and confirmation do not match"
    (let [attrs (factory/build-test-user)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Password and confirmation do not match"
                            (user/create!
                             (assoc attrs
                                    :password "password12345"
                                    :password_confirmation "wordpass54321"))))))

  (testing "throws when the password is shorter than the minimum length"
    (let [attrs (factory/build-test-user)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Password must be between"
                            (user/create!
                             (assoc attrs
                                    :password              "short"
                                    :password_confirmation "short"))))))

  (testing "normalizes the email (lowercases + trims) before saving"
    (let [base    (factory/build-test-user)
          raw     (str "  " (str/upper-case (:email base)) "  ")
          created (user/create! (assoc base :email raw))]
      (is (= (:email base) (:email created))))))

(deftest test-delete!
  (testing "deletes the user entity from the database"
    (let [user (create-test-user!)
          id   (:id user)]
      (user/delete! id)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"User not found"
                            (user/fetch id)))))

  (testing "deletes the map entity owned by the user from the database"
    (let [user        (create-test-user!)
          id          (:id user)
          map-data    {:title    "Map To Delete"
                       :owner_id id}
          map-created (parts-map/create! map-data id)]
      (user/delete! id)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Map not found"
                            (parts-map/fetch (:id map-created))))))

  (testing "returns {:id id :deleted true} when a user is successfully deleted"
    (let [user   (create-test-user!)
          result (user/delete! (:id user))]
      (is (= (:id user) (:id result)))
      (is (true? (:deleted result)))))

  (testing "returns {:id id :deleted false} for a non-existent user (does not throw)"
    (let [bogus-id "deadbeef-dead-beef-dead-beefdeadbeef"
          result   (user/delete! bogus-id)]
      (is (= {:id bogus-id :deleted false} result)))))
