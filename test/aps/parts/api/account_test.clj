(ns aps.parts.api.account-test
  (:require
   [aps.parts.api.account :as account]
   [aps.parts.auth :as auth]
   [aps.parts.db :as db]
   [aps.parts.entity.map :as parts-map]
   [aps.parts.entity.session :as session]
   [aps.parts.helpers.test-factory :as factory]
   [aps.parts.helpers.utils :refer [create-test-user! with-test-db]]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :once with-test-db)

(def ^:private acceptance
  "The onboarding acceptances the server now requires (ADR-0009); merged into a
   registration body so these flows still provision an account."
  {:accepted-legal? true :accepted-medical? true})

(deftest test-get-account
  (testing "returns currently signed in user's information"
    (let [user         (create-test-user!)
          mock-request {:identity {:sub (:id user)}}
          response     (account/get-account mock-request)]
      (is (= 200 (:status response)))
      (is (= {:email             (:email user)
              :display_name      (:display_name user)
              :role              (:role user)
              :id                (:id user)
              :paid_through_date nil
              :map_id            nil
              ;; A fresh account has never paid, so good standing is unset.
              :standing          {:status            :never-paid
                                  :paid_through_date nil
                                  :days_remaining    nil}} (:body response)))
      (is (not (contains? response :password_hash))))))

(deftest test-update-account
  (testing "correctly updates the user data"
    (let [user           (create-test-user!)
          mock-request   {:identity    {:sub (:id user)}
                          :body-params {:email        (str "added" (:email user))
                                        :display_name "Updated"}}
          response       (account/update-account mock-request)
          updated-fields (select-keys (:body response) [:email :display_name])]
      (is (= 200 (:status response)))
      (is (= {:email        (str "added" (:email user))
              :display_name "Updated"}
             updated-fields))
      (is (not (contains? (:body response) :password_hash)))))

  (testing "does not update where no updatable data is passed"
    (let [user         (create-test-user!)
          mock-request {:identity    {:sub (:id user)}
                        :body-params {:role "therapist"}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Nothing to update"
                            (account/update-account mock-request))))))

(deftest test-delete-account
  (testing "does not delete without a confirmation param"
    (let [user         (create-test-user!)
          mock-request {:identity {:sub (:id user)}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Confirmation needed"
                            (account/delete-account mock-request)))))

  (testing "does not delete with a confirmation param that does not match the email"
    (let [user         (create-test-user!)
          mock-request {:identity {:sub (:id user)} :query-params {"confirm" "random"}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Confirmation needed"
                            (account/delete-account mock-request)))))

  (testing "deletes the account with a confirmation param"
    (let [user         (create-test-user!)
          mock-request {:identity {:sub (:id user)} :query-params {"confirm" (:email user)}}
          response     (account/delete-account mock-request)
          db-user      (db/query-one (db/sql-format {:select [:id]
                                                     :from   [:users]
                                                     :where  [:= :id (:id user)]}))]
      (is (= 204 (:status response)))
      (is (= nil db-user)))))

;; TODO: When we allow roles other than "therapist" during registration,
;; update this test to verify the role from user-data is respected
(deftest test-register-account
  (testing "register creates a new user with therapist role and a default map"
    (let [user-data                    (factory/build-test-user)
          {:keys [email display_name]} user-data
          mock-request                 {:body-params (merge user-data acceptance)}
          response                     (account/register-account mock-request)
          user                         (:body response)]
      (is (= 201 (:status response)))
      (is (= email (:email user)))
      (is (= display_name (:display_name user)))
      ;; Role is always hardcoded to "therapist" regardless of input
      (is (= "therapist" (:role user)))
      ;; Registration establishes the auth session for auto-login
      (is (= {:sub (str (:id user))} (get-in response [:session :identity])))
      ;; Registration should create a default map
      (is (some? (:map_id user)))))

  (testing "registration creates a map for the new account, titled after the display name"
    (let [user-data (factory/build-test-user)
          response  (account/register-account {:body-params (merge user-data acceptance)})
          user      (:body response)
          the-map   (parts-map/fetch (:map_id user))]
      (is (some? the-map) "the map exists in the database")
      (is (= (:id user) (:owner_id the-map)) "the map is owned by the new account")))

  (testing "provisioning opens Session 1 before seeding demo content, so
            derived membership is total from the first moment (ADR-0014)"
    (let [user-data   (factory/build-test-user)
          response    (account/register-account
                       {:body-params (merge user-data acceptance)})
          map-id      (get-in response [:body :map_id])
          sessions    (session/index map-id)
          appeared    (session/first-appearances map-id)
          the-map     (parts-map/fetch map-id)
          content-ids (concat (map :id (:parts the-map))
                              (map :id (:relationships the-map)))]
      (is (= [1] (mapv :ordinal sessions)) "exactly one Session, ordinal 1")
      (is (nil? (:trigger (first sessions))) "the provisioned trigger is empty")
      (is (seq content-ids) "the starter Map is seeded")
      (is (every? #(= (:id (first sessions)) (:id (get appeared %))) content-ids)
          "every seeded Part and Relationship buckets into Session 1")))

  (testing "if a write inside the tx throws, the user insert is rolled back"
    (let [user-data    (factory/build-test-user)
          mock-request {:body-params (merge user-data acceptance)}]
      (with-redefs [parts-map/create! (fn [_ _]
                                        (throw (ex-info "Simulated tx error" {})))]
        (is (thrown? Exception
                     (account/register-account mock-request))))
      (let [db-user (db/query-one
                     (db/sql-format {:select [:id]
                                     :from   [:users]
                                     :where  [:= :email (:email user-data)]}))]
        (is (= nil db-user) "User row should have been rolled back")))))

(deftest test-register-rejects-privilege-mass-assignment
  (testing "a registration body cannot self-grant Founding Circle"
    ;; Mass-assignment guard: register-account must not let request-body keys
    ;; reach privilege/billing columns. is_founding_circle is server-controlled
    ;; — the invite path sets it from the trusted invitation row, never a form.
    (let [user-data    (factory/build-test-user)
          mock-request {:body-params (merge user-data acceptance
                                            {:is_founding_circle true})}
          response     (account/register-account mock-request)
          db-user      (db/query-one
                        (db/sql-format {:select [:is_founding_circle]
                                        :from   [:users]
                                        :where  [:= :id (get-in response [:body :id])]}))]
      (is (= 201 (:status response)))
      (is (false? (:is_founding_circle db-user))
          "is_founding_circle must stay at its default, not the injected value"))))

(deftest test-register-then-authenticate
  (testing "a registered user can immediately authenticate with their credentials"
    (let [user-data (factory/build-test-user)
          _         (account/register-account {:body-params (merge user-data acceptance)})
          result    (auth/authenticate
                     (select-keys user-data [:email :password]))]
      (is (some? result) "authenticate returns the user")
      (is (= (:email user-data) (:email result)))))

  (testing "authenticates case-insensitively against the registered email"
    (let [user-data (factory/build-test-user)
          _         (account/register-account {:body-params (merge user-data acceptance)})
          result    (auth/authenticate
                     {:email    (str/upper-case (:email user-data))
                      :password (:password user-data)})]
      (is (some? result)
          "Uppercased email should still find the (lowercased) stored row"))))
