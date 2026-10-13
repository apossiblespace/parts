(ns aps.parts.api.account-test
  (:require
   [aps.parts.api.account :as account]
   [aps.parts.auth :as auth]
   [aps.parts.auth.session-store :as session-store]
   [aps.parts.config :as conf]
   [aps.parts.db :as db]
   [aps.parts.email-changes :as email-changes]
   [aps.parts.entity.map :as parts-map]
   [aps.parts.entity.session :as session]
   [aps.parts.entity.user :as user]
   [aps.parts.helpers.browser :refer [anonymous-session api browse csrf-token decode sign-in]]
   [aps.parts.helpers.test-factory :as factory]
   [aps.parts.helpers.utils :refer [create-test-user! stripe-test-config
                                    with-test-db without-stripe]]
   [aps.parts.mail :as mail]
   [aps.parts.password-notice :as password-notice]
   [aps.parts.server :as server]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.middleware.session.store :as store]
   [ring.mock.request :as mock]))

(use-fixtures :once with-test-db)
(def ^:private real-send-welcome! @#'account/send-welcome!)

(defn- without-account-emails
  "Stops these tests from sending a real welcome email or password-changed
   notice."
  [f]
  (with-redefs-fn {#'account/send-welcome!         (constantly nil)
                   #'account/send-deletion-notice! (constantly nil)
                   #'password-notice/send!         (constantly nil)}
    f))

(use-fixtures :each without-stripe without-account-emails)

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
      (is (= {:email                        (:email user)
              :display_name                 (:display_name user)
              :role                         (:role user)
              :id                           (:id user)
              :paid_through_date            nil
              :map_id                       nil
              :product_updates_opted_out_at nil
              :product_updates_chosen_at    nil
              ;; A fresh account has never paid, so good standing is unset.
              :standing                     {:status            :never-paid
                                             :paid_through_date nil
                                             :days_remaining    nil}
              ;; Test env has no Stripe config and no linked customer.
              :billing                      {:self_serve_enabled      false
                                             :subscription_active     false
                                             :subscription_cancelled  false
                                             :subscription_cancelling false
                                             :subscription_plan       nil}} (:body response)))
      (is (not (contains? response :password_hash)))))

  (testing "reports the portal usable and the subscription live once Stripe
            is configured and the account is linked with an active status"
    (let [user (create-test-user!)]
      (db/update! :users {:stripe_customer_id         "cus_account_test"
                          :stripe_subscription_status "active"
                          :stripe_plan                "monthly"}
                  [:= :id (:id user)])
      (with-redefs [conf/stripe-config (constantly stripe-test-config)]
        (let [response (account/get-account {:identity {:sub (str (:id user))}})]
          (is (= {:self_serve_enabled      true
                  :subscription_active     true
                  :subscription_cancelled  false
                  :subscription_cancelling false
                  :subscription_plan       "monthly"}
                 (:billing (:body response))))))))

  (testing "a cancelled subscription reads linked but not active"
    (let [user (create-test-user!)]
      (db/update! :users {:stripe_customer_id         "cus_cancelled_acct"
                          :stripe_subscription_status "canceled"}
                  [:= :id (:id user)])
      (with-redefs [conf/stripe-config (constantly stripe-test-config)]
        (let [response (account/get-account {:identity {:sub (str (:id user))}})]
          (is (= {:self_serve_enabled      true
                  :subscription_active     false
                  :subscription_cancelled  true
                  :subscription_cancelling false
                  :subscription_plan       nil}
                 (:billing (:body response))))))))

  (testing "a linked customer on a host without Stripe config gets no portal
            (the button's backend could only fail)"
    (let [user (create-test-user!)]
      (db/update! :users {:stripe_customer_id         "cus_unconfigured"
                          :stripe_subscription_status "active"}
                  [:= :id (:id user)])
      (let [response (account/get-account {:identity {:sub (str (:id user))}})]
        (is (= {:self_serve_enabled      false
                :subscription_active     false
                :subscription_cancelled  false
                :subscription_cancelling false
                :subscription_plan       nil}
               (:billing (:body response))))))))

(deftest test-update-account
  (testing "correctly updates the user data"
    (let [pw             "correct horse battery"
          user           (create-test-user! {:password              pw
                                             :password_confirmation pw})
          mock-request   {:identity    {:sub (:id user)}
                          :body-params {:display_name     "Updated"
                                        :current_password pw}}
          response       (account/update-account mock-request)
          updated-fields (select-keys (:body response) [:email :display_name])]
      (is (= 200 (:status response)))
      (is (= {:email        (:email user)
              :display_name "Updated"}
             updated-fields))
      (is (not (contains? (:body response) :password_hash)))))

  (testing "does not update where no updatable data is passed"
    (let [user         (create-test-user!)
          mock-request {:identity    {:sub (:id user)}
                        :body-params {:role "therapist"}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Nothing to update"
                            (account/update-account mock-request))))))

(deftest test-update-account-credential-reauth
  (let [pw      "correct horse battery"
        mk-user #(create-test-user! {:password pw :password_confirmation pw})
        req     (fn [user body] {:identity {:sub (:id user)} :body-params body})]

    (testing "password change without the current password is rejected"
      (let [user (mk-user)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Current password is incorrect"
                              (account/update-account
                               (req user {:password              "new-password-1"
                                          :password_confirmation "new-password-1"}))))))

    (testing "password change with a wrong current password is rejected"
      (let [user (mk-user)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Current password is incorrect"
                              (account/update-account
                               (req user {:password              "new-password-1"
                                          :password_confirmation "new-password-1"
                                          :current_password      "not the password"}))))))

    (testing "password change with the correct current password rotates the credential"
      (let [user     (mk-user)
            new-pw   "brand new password 9"
            response (account/update-account
                      (req user {:password              new-pw
                                 :password_confirmation new-pw
                                 :current_password      pw}))]
        (is (= 200 (:status response)))
        (is (some? (auth/authenticate {:email (:email user) :password new-pw}))
            "the new password authenticates")
        (is (nil? (auth/authenticate {:email (:email user) :password pw}))
            "the old password no longer authenticates")))

    (testing "an email change is rejected, even with the current password"
      (let [user (mk-user)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"use Change… next to Email"
                              (account/update-account
                               (req user {:email            (str "changed" (:email user))
                                          :current_password pw}))))
        (is (= (:email user) (:email (user/fetch (:id user)))) "the address is unchanged")))

    (testing "display-name-only change needs no current password"
      (let [user     (mk-user)
            response (account/update-account
                      (req user {:display_name "Just A Rename"}))]
        (is (= 200 (:status response)))
        (is (= "Just A Rename" (:display_name (:body response))))))))

(defn- internal-keys
  "Keys of an account response that must never leave the server."
  [body]
  (filter #(re-find #"^(stripe_|deletion_|password|unsubscribe)" (name %)) (keys body)))

(deftest test-account-responses-are-allowlisted
  (let [pw   "correct horse battery"
        user (create-test-user! {:password pw :password_confirmation pw})]
    (db/update! :users {:stripe_customer_id         "cus_allowlist"
                        :stripe_subscription_status "active"
                        :stripe_plan                "monthly"}
                [:= :id (:id user)])
    (testing "GET, PATCH and a product-updates PATCH have one shape"
      (let [get-body (:body (account/get-account {:identity {:sub (:id user)}}))]
        (is (empty? (internal-keys get-body)))
        (doseq [body [{:display_name "Renamed"}
                      {:product_updates true}
                      {:password         "a new long password" :password_confirmation "a new long password"
                       :current_password pw}]]
          (let [patched (:body (account/update-account {:identity {:sub (:id user)} :body-params body}))]
            (is (empty? (internal-keys patched)) (pr-str body))
            (is (= (set (keys get-body)) (set (keys patched))) (pr-str body))))))
    (testing "registration responds with the same shape"
      (let [body (:body (account/register-account
                         {:body-params (merge (factory/build-test-user) acceptance)}))]
        (is (empty? (internal-keys body)))
        (is (= #{:id :email :display_name :role :paid_through_date :map_id
                 :product_updates_opted_out_at :product_updates_chosen_at
                 :standing :billing}
               (set (keys body))))))))

(defn- user-row [id]
  (db/query-one (db/sql-format {:select [:id] :from [:users] :where [:= :id id]})))

(deftest test-delete-account
  (let [pw     "correct horse battery"
        user   (create-test-user! {:password pw :password_confirmation pw})
        delete (fn [body] (account/delete-account {:identity {:sub (:id user)} :body-params body}))]
    (testing "refuses without the account's email address, and deletes nothing"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Type the email address"
                            (delete {:current_password pw})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Type the email address"
                            (delete {:email "someone@else.test" :current_password pw})))
      (is (some? (user-row (:id user)))))
    (testing "refuses a wrong or missing password, and deletes nothing"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"That password is incorrect"
                            (delete {:email (:email user) :current_password "wrong password"})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"That password is incorrect"
                            (delete {:email (:email user)})))
      (is (some? (user-row (:id user)))))
    (testing "deletes with the email (any case) and the password, and tells the
              deleted address"
      (let [notified (atom nil)
            response (with-redefs-fn {#'account/send-deletion-notice! #(reset! notified [%1 %2])}
                       #(delete {:email            (str " " (str/upper-case (:email user)) " ")
                                 :current_password pw}))]
        (is (= 204 (:status response)))
        (is (nil? (user-row (:id user))))
        (is (= [(:email user) false] @notified) "no subscription to mention")))))

(deftest test-deletion-message
  (let [message @#'account/deletion-message]
    (testing "names the subscription only when one was cancelled"
      (is (str/includes? (pr-str (message "a@b.test" true)) "Your subscription has been cancelled"))
      (is (not (str/includes? (pr-str (message "a@b.test" false)) "Your subscription"))))))

(deftest test-delete-account-through-the-app
  (let [app               (server/app)
        pw                "correct horse battery"
        user              (create-test-user! {:password pw :password_confirmation pw})
        [cookie token]    (sign-in app user pw)
        [refused]         (browse app (api :post "/api/account/delete" token
                                           {:email (:email user) :current_password "wrong"})
                                  cookie)
        [response cookie] (browse app (api :post "/api/account/delete" token
                                           {:email (:email user) :current_password pw})
                                  cookie)]
    (testing "a wrong password says so, Transit-encoded so the SPA can read it,
              and the account stays (TASK-107)"
      (is (= 400 (:status refused)))
      (is (str/starts-with? (get-in refused [:headers "Content-Type"]) "application/transit+json"))
      (is (= "That password is incorrect. Your account was not deleted."
             (:error (decode refused)))))
    (testing "a POST with the credentials in the body deletes the account"
      (is (= 204 (:status response)))
      (is (nil? (user-row (:id user)))))
    (testing "the session is gone afterwards"
      (is (= 401 (:status (first (browse app (api :get "/api/account" token) cookie))))))
    (testing "the confirmation page is public"
      (let [[page] (browse app (mock/request :get "/account-deleted") nil)]
        (is (= 200 (:status page)))
        (is (str/includes? (str (:body page)) "Your account has been deleted"))))))

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
    ;; reach privilege/billing columns. is_founding_circle is set only by
    ;; the operator, never by a form.
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

;;; Product updates

(defn- opt-out-row [user-id]
  (db/query-one (db/sql-format {:select [:product_updates_opted_out_at :product_updates_chosen_at
                                         :unsubscribe_token]
                                :from   [:users]
                                :where  [:= :id user-id]})))

(defn- patch! [user-id body]
  (account/update-account {:identity {:sub user-id} :body-params body}))

(deftest test-product-updates
  (let [user (create-test-user!)
        id   (:id user)]
    (testing "a user created without a choice is not opted out, and has an unsubscribe token"
      (is (nil? (:product_updates_opted_out_at (opt-out-row id))))
      (is (uuid? (:unsubscribe_token (opt-out-row id)))))

    (testing "an account that never chose has no choice time, and the API says so"
      (is (nil? (:product_updates_chosen_at (opt-out-row id))))
      (is (contains? (:body (account/get-account {:identity {:sub id}}))
                     :product_updates_chosen_at)))

    (testing "patching product_updates alone opts the user out and records the choice"
      (let [response (patch! id {:product_updates false})]
        (is (= 200 (:status response)))
        (is (some? (-> response :body :product_updates_opted_out_at)))
        (is (some? (-> response :body :product_updates_chosen_at)))
        (is (some? (:product_updates_opted_out_at (opt-out-row id))))))

    (testing "opting out again keeps the first opt-out time"
      (let [first-time (:product_updates_opted_out_at (opt-out-row id))]
        (patch! id {:product_updates false})
        (is (= first-time (:product_updates_opted_out_at (opt-out-row id))))))

    (testing "patching product_updates true opts the user back in, at a new choice time"
      (let [before (:product_updates_chosen_at (opt-out-row id))]
        (Thread/sleep 5)
        (patch! id {:product_updates true})
        (is (nil? (:product_updates_opted_out_at (opt-out-row id))))
        (is (pos? (compare (:product_updates_chosen_at (opt-out-row id)) before))
            "the choice time is the time of consent")))

    (testing "patches product_updates together with other fields"
      (let [response (patch! id {:product_updates false :display_name "Both"})]
        (is (= "Both" (-> response :body :display_name)))
        (is (some? (:product_updates_opted_out_at (opt-out-row id))))))

    (testing "rejects a value that is not a boolean"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"true or false"
                            (patch! id {:product_updates "no"}))))

    (testing "never returns the unsubscribe token"
      (is (not (contains? (:body (patch! id {:product_updates true})) :unsubscribe_token)))
      (is (not (contains? (:body (account/get-account {:identity {:sub id}}))
                          :unsubscribe_token))))))

(deftest test-register-product-updates-opt-out
  (testing "registers an opted out account when the opt-out is ticked"
    (let [response (account/register-account
                    {:body-params (merge (factory/build-test-user) acceptance
                                         {:product-updates-opt-out? true})})]
      (is (= 201 (:status response)))
      (is (some? (:product_updates_opted_out_at (opt-out-row (-> response :body :id)))))
      (is (some? (:product_updates_chosen_at (opt-out-row (-> response :body :id))))
          "the sign-up box is a choice, so a new account is never asked in the app")
      (is (not (contains? (:body response) :unsubscribe_token)))))

  (testing "records no choice when the request has no sign-up box value"
    (let [response (account/register-account
                    {:body-params (merge (factory/build-test-user) acceptance)})]
      (is (nil? (:product_updates_chosen_at (opt-out-row (-> response :body :id)))))))

  (testing "registers a subscribed account when the box is left ticked"
    (let [response (account/register-account
                    {:body-params (merge (factory/build-test-user) acceptance
                                         {:product-updates-opt-out? false})})
          row      (opt-out-row (-> response :body :id))]
      (is (nil? (:product_updates_opted_out_at row)))
      (is (some? (:product_updates_chosen_at row))))))

(defn- register-capturing-welcome
  "Calls `register` with the welcome send replaced by a stub. Returns the
   accounts that would get a welcome email."
  [register]
  (let [welcomed (atom [])]
    (with-redefs-fn {#'account/send-welcome! #(swap! welcomed conj %)}
      #(try (register) (catch Exception _)))
    @welcomed))

(deftest test-register-sends-welcome-email
  (testing "a new account gets one welcome email"
    (let [user-data (factory/build-test-user)
          welcomed  (register-capturing-welcome
                     #(account/register-account {:body-params (merge user-data acceptance)}))]
      (is (= [(:email user-data)] (map :email welcomed)))))

  (testing "a failed signup sends no welcome email"
    (let [user-data (factory/build-test-user)]
      (create-test-user! {:email (:email user-data)})
      (is (empty? (register-capturing-welcome
                   #(account/register-account {:body-params (merge user-data acceptance)})))))))

(deftest test-welcome-email-send-failure
  (testing "a mail failure is caught, so it cannot fail the signup"
    (with-redefs [mail/send! (fn [_] (throw (ex-info "relay down" {})))]
      (is (nil? @(real-send-welcome! {:id "u1" :email "a@example.com" :display_name "A"}))))))

(deftest test-welcome-message
  (with-redefs [conf/base-url             (constantly "https://parts.example")
                conf/walkthrough-video-id (constantly "abcdefghijk")
                conf/mail-sender-identity (constantly "Example Ltd · Company no. 123")]
    (let [message              (#'account/welcome-message {:email "jane@example.com" :display_name "Jane"})
          [subtype plain html] (:body message)]
      (testing "is multipart, to the new account"
        (is (= "jane@example.com" (:to message)))
        (is (= "Welcome to Parts" (:subject message)))
        (is (= :alternative subtype)))
      (testing "links the configured walkthrough video and the app"
        (is (str/includes? (:content plain)
                           "video walkthrough of Parts (https://www.youtube.com/watch?v=abcdefghijk)"))
        (is (str/includes? (:content html) "href=\"https://www.youtube.com/watch?v&#61;abcdefghijk\""))
        (is (str/includes? (:content plain) "open Parts (https://parts.example/app)")))
      (testing "ends with the transactional footer"
        (is (str/includes? (:content plain) "This email was sent to jane@example.com about your Parts account."))
        (is (str/includes? (:content html) "Example Ltd · Company no. 123")))))
  (testing "does not show the display name, which the signup does not check"
    (let [[_ plain html] (:body (#'account/welcome-message
                                 {:email "jane@example.com" :display_name "Visit evil.example now"}))]
      (is (str/starts-with? (:content plain) "Hello,\n"))
      (is (not (str/includes? (:content plain) "evil.example")))
      (is (not (str/includes? (:content html) "evil.example"))))))

(deftest test-update-account-password-notice
  (let [pw      "correct horse battery"
        notices (fn [user body]
                  (let [sent (atom [])]
                    (with-redefs [password-notice/send! (fn [_ email] (swap! sent conj email))]
                      (try
                        (account/update-account {:identity    {:sub (:id user)}
                                                 :body-params (merge {:current_password pw} body)})
                        (catch clojure.lang.ExceptionInfo _)))
                    @sent))
        mk-user #(create-test-user! {:password pw :password_confirmation pw})]
    (testing "a password change sends one notice to the account's address"
      (let [user (mk-user)]
        (is (= [(:email user)]
               (notices user {:password "a new password 9" :password_confirmation "a new password 9"})))))
    (testing "no notice when the password does not change"
      (let [user (mk-user)]
        (is (empty? (notices user {:display_name "Renamed"})) "no password in the request")
        (is (empty? (notices user {:display_name "Renamed" :password nil})) "a nil password")
        (is (empty? (notices user {:password              "a new password 9"
                                   :password_confirmation "a new password 9"
                                   :current_password      "not the password"}))
            "a wrong current password")
        (is (empty? (notices user {:password "short" :password_confirmation "short"}))
            "a password that fails validation")))))

(deftest test-update-account-password-logs-out-other-sessions
  (let [pw       "correct horse battery"
        user     (create-test-user! {:password pw :password_confirmation pw})
        other    (store/write-session (session-store/db-store db/datasource 3600) nil
                                      {:identity {:sub (str (:id user))}})
        response (account/update-account
                  {:identity    {:sub (:id user)}
                   :session     {:identity {:sub (str (:id user))}}
                   :body-params {:password              "a new password 9"
                                 :password_confirmation "a new password 9"
                                 :current_password      pw}})]
    (testing "the other session is deleted"
      (is (nil? (:identity (store/read-session (session-store/db-store db/datasource 3600) other)))))
    (testing "this request gets a new session id, still signed in"
      (is (= {:sub (str (:id user))} (get-in response [:session :identity])))
      (is (:recreate (meta (:session response)))))))

(deftest test-export-zip
  ;; The Account page fetches it as a plain GET: no Transit, no CSRF header.
  (let [app      (server/app)
        pw       "correct horse battery"
        user     (create-test-user! {:password pw :password_confirmation pw})
        download (fn [cookie] (first (browse app (mock/request :get "/api/account/export.zip") cookie)))]
    (testing "needs a signed-in user"
      (let [[cookie _] (anonymous-session app)]
        (is (= 401 (:status (download cookie))))))
    (let [[cookie _] (sign-in app user pw)]
      (testing "serves the signed-in user's ZIP as a download"
        (let [response (download cookie)]
          (is (= 200 (:status response)))
          (is (= "application/zip" (get-in response [:headers "Content-Type"])))
          (is (= "no-store" (get-in response [:headers "Cache-Control"])))
          (is (re-find #"attachment; filename=\"parts-data-\d{4}-\d{2}-\d{2}\.zip\""
                       (get-in response [:headers "Content-Disposition"])))))
      (testing "a few downloads a minute, then 429"
        (download cookie)
        (download cookie)
        (is (= 429 (:status (download cookie))))))))

(deftest test-request-email-change
  (let [pw      "correct horse battery"
        user    (create-test-user! {:password pw :password_confirmation pw})
        sent    (atom [])
        request (fn [body]
                  (with-redefs-fn {#'mail/send-async! (fn [purpose _ message _] (swap! sent conj [purpose message]))}
                    #(account/request-email-change {:identity {:sub (:id user)} :body-params body})))]
    (testing "needs the current password, and sends nothing without it"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Current password is incorrect"
                            (request {:new_email "new@example.test" :current_password "wrong"})))
      (is (empty? @sent)))
    (testing "sends the link to the new address; the account keeps the old one"
      (let [response          (request {:new_email "New@Example.test" :current_password pw})
            [purpose message] (first @sent)]
        (is (= 200 (:status response)))
        (is (= {:new_email "new@example.test"} (:body response)))
        (is (= :email-change-link purpose))
        (is (= "new@example.test" (:to message)))
        (is (str/includes? (pr-str (:body message)) "/email-change/"))
        (is (= (:email user) (:email (user/fetch (:id user)))))))
    (testing "an address another account uses gets the same answer; only that
              address is told, and no link is sent"
      (reset! sent [])
      (let [other             (create-test-user!)
            response          (request {:new_email (:email other) :current_password pw})
            [purpose message] (first @sent)]
        (is (= 200 (:status response)))
        (is (= {:new_email (:email other)} (:body response)))
        (is (= :email-in-use purpose))
        (is (= (:email other) (:to message)))
        (is (not (str/includes? (pr-str (:body message)) "/email-change/")))))))

(deftest test-email-change-link-through-the-app
  (let [app    (server/app)
        user   (create-test-user!)
        change (email-changes/request! (:id user) "linked@example.test")
        path   (str "/email-change/" (:token change))
        notice (atom nil)]
    (with-redefs-fn {#'mail/send-async! (fn [purpose _ message _] (reset! notice [purpose (:to message)]))}
      (fn []
        (let [[page cookie] (browse app (mock/request :get path) nil)]
          (testing "opening the link changes nothing: it asks to confirm"
            (is (= 200 (:status page)))
            (is (str/includes? (str (:body page)) "linked@example.test"))
            (is (= (:email user) (:email (user/fetch (:id user))))))
          (testing "confirming moves the account and tells the old address"
            (let [[done] (browse app (-> (mock/request :post path)
                                         (mock/body {"__anti-forgery-token" (csrf-token (str (:body page)))}))
                                 cookie)]
              (is (= 200 (:status done)))
              (is (str/includes? (str (:body done)) "Your email address has been changed"))
              (is (= "linked@example.test" (:email (user/fetch (:id user)))))
              (is (= [:email-changed (:email user)] @notice))))
          (testing "the link works once"
            (is (= 404 (:status (first (browse app (mock/request :get path) nil)))))))))))
