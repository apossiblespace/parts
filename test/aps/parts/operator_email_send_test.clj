(ns aps.parts.operator-email-send-test
  (:require
   [aps.parts.config :as conf]
   [aps.parts.db :as db]
   [aps.parts.db.erasure :as erasure]
   [aps.parts.entity.user :as user]
   [aps.parts.helpers.utils :refer [create-test-user! with-test-db]]
   [aps.parts.mail :as mail]
   [aps.parts.operator-email :as email]
   [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each with-test-db)

(defn- users
  "Creates a subscribed user, an opted-out user and a user pending
   deletion."
  []
  (let [subscribed (create-test-user!)
        opted-out  (create-test-user!)
        pending    (create-test-user!)]
    (user/set-product-updates! (:id opted-out) false)
    (binding [*out* (java.io.StringWriter.)]
      (erasure/request-deletion! db/datasource (:id pending)))
    {:subscribed subscribed :opted-out opted-out :pending pending}))

(defn- store! [kind]
  (:id (db/insert! :operator_emails {:kind kind :subject "News" :body "Hello"})))

(defn- delivery [email-id user-id]
  (db/query-one (db/sql-format {:select [:sent_at :error]
                                :from   [:operator_email_deliveries]
                                :where  [:and [:= :email_id email-id] [:= :user_id user-id]]})))

(defmacro ^:private with-mailer
  "Runs `body` with a mailer that records each message in the atom `sent`
   and throws for the addresses in the set `fail`."
  [sent fail & body]
  `(with-redefs [conf/base-url (constantly "https://parts.example")
                 mail/send!    (fn [message#]
                                 (when (contains? ~fail (:to message#))
                                   (throw (ex-info "Email send failed"
                                                   {:type   :smtp-error
                                                    :result {:message "550 Mailbox unavailable"}})))
                                 (swap! ~sent conj message#)
                                 message#)]
     ~@body))

(deftest test-audience
  (users)
  (testing "a product update skips pending deletion and opted-out users"
    (is (= {:users 3 :pending 1 :opted-out 1 :recipients 1} (email/audience "product-update"))))
  (testing "a service notice skips only pending deletion"
    (is (= {:users 3 :pending 1 :opted-out 0 :recipients 2} (email/audience "service-notice")))))

(deftest test-recipients-per-kind
  (let [{:keys [subscribed opted-out]} (users)
        sent                           (atom [])]
    (with-mailer sent #{}
      (testing "a product update goes to subscribed users only"
        (email/send-email! (store! "product-update"))
        (is (= [(:email subscribed)] (map :to @sent))))
      (testing "a service notice also goes to opted-out users"
        (reset! sent [])
        (email/send-email! (store! "service-notice"))
        (is (= #{(:email subscribed) (:email opted-out)} (set (map :to @sent))))))))

(deftest test-send-records-each-delivery
  (let [ok   (create-test-user!)
        bad  (create-test-user!)
        id   (store! "service-notice")
        sent (atom [])]
    (with-mailer sent #{(:email bad)}
      (email/send-email! id))
    (testing "records a sent row after the relay accepts"
      (is (some? (:sent_at (delivery id (:id ok)))))
      (is (nil? (:error (delivery id (:id ok))))))
    (testing "records the error when the relay refuses"
      (is (nil? (:sent_at (delivery id (:id bad)))))
      (is (= "550 Mailbox unavailable" (:error (delivery id (:id bad))))))
    (testing "marks the email complete"
      (is (some? (:completed_at (db/query-one (db/sql-format {:select [:completed_at]
                                                              :from   [:operator_emails]
                                                              :where  [:= :id id]}))))))))

(deftest test-unsubscribe-link-per-user
  (let [subscribed (create-test-user!)
        token      (:unsubscribe_token (db/query-one (db/sql-format {:select [:unsubscribe_token]
                                                                     :from   [:users]
                                                                     :where  [:= :id (:id subscribed)]})))
        sent       (atom [])]
    (with-mailer sent #{}
      (email/send-email! (store! "product-update")))
    (testing "each product update carries the unsubscribe link of its recipient"
      (is (= (str "<https://parts.example/unsubscribe/" token ">")
             (get (first @sent) "List-Unsubscribe"))))))

(deftest test-resume-skips-delivered-users
  (let [ok   (create-test-user!)
        bad  (create-test-user!)
        id   (store! "service-notice")
        sent (atom [])]
    (with-mailer sent #{(:email bad)}
      (email/send-email! id))
    (with-mailer sent #{}
      (email/send-email! id))
    (testing "a second run sends only to the user whose delivery failed"
      (is (= [(:email ok) (:email bad)] (map :to @sent))))
    (testing "the retry replaces the failed row"
      (is (some? (:sent_at (delivery id (:id bad)))))
      (is (nil? (:error (delivery id (:id bad))))))))

(deftest test-summaries
  (let [ok      (create-test-user!)
        bad     (create-test-user!)
        done-id (store! "service-notice")]
    (with-mailer (atom []) #{(:email bad)}
      (email/send-email! done-id))
    (let [stopped-id (store! "service-notice")
          by-id      (into {} (map (juxt :id identity)) (email/sent-emails))]
      (testing "a finished email counts sent and failed and lists the failures"
        (is (= {:state :done :sent 1 :failed 1 :waiting 0 :total 2}
               (select-keys (by-id done-id) [:state :sent :failed :waiting :total])))
        (is (= [{:email_id done-id :email (:email bad) :error "550 Mailbox unavailable"}]
               (:failures (by-id done-id)))))
      (testing "an unfinished email that is not sending is stopped, with its users waiting"
        (is (= {:state :stopped :sent 0 :failed 0 :waiting 2 :total 2}
               (select-keys (by-id stopped-id) [:state :sent :failed :waiting :total]))))
      (testing "no email is sending"
        (is (nil? (email/current-send))))
      (is (some? ok)))))

(deftest test-one-send-at-a-time
  (let [gate (promise)]
    (with-redefs [email/send-email! (fn [& _] @gate)]
      (let [id (email/send-draft! {:kind "service-notice" :subject "One" :body "Hi"})]
        (testing "a second send or a resume is refused while an email sends"
          (is (uuid? id))
          (is (nil? (email/send-draft! {:kind "service-notice" :subject "Two" :body "Hi"})))
          (is (nil? (email/resume! id))))
        (testing "the email that sends is the current send"
          (is (= id (:id (email/current-send))))
          (is (= :sending (:state (email/current-send)))))
        (deliver gate nil)
        (loop [n 0]
          (when (and (email/current-send) (< n 100))
            (Thread/sleep 20)
            (recur (inc n))))
        (testing "a new send can start when the send ends"
          (is (nil? (email/current-send))))))))

(deftest test-retry-of-a-finished-email
  (let [ok   (create-test-user!)
        bad  (create-test-user!)
        id   (store! "service-notice")
        sent (atom [])]
    (with-mailer sent #{(:email bad)}
      (email/send-email! id))
    (let [later (create-test-user!)]
      (reset! sent [])
      (with-mailer sent #{}
        (email/send-email! id true))
      (testing "a retry sends only to the users whose delivery failed"
        (is (= [(:email bad)] (map :to @sent))))
      (testing "a user who signed up later does not get the old email"
        (is (nil? (delivery id (:id later)))))
      (is (some? ok)))))

(deftest test-resume-mode
  (let [done-id    (:id (db/insert! :operator_emails {:kind         "service-notice" :subject "S" :body "B"
                                                      :completed_at [:now]}))
        stopped-id (store! "service-notice")
        modes      (atom {})]
    (with-redefs [email/send-email! (fn [id only-failed?] (swap! modes assoc id only-failed?))]
      (doseq [id [done-id stopped-id]]
        (email/resume! id)
        (loop [n 0]
          (when (and (not (contains? @modes id)) (< n 100))
            (Thread/sleep 10)
            (recur (inc n))))
        (loop [n 0]
          (when (and (email/current-send) (< n 100))
            (Thread/sleep 10)
            (recur (inc n))))))
    (testing "a finished email retries only failures and a stopped email resumes the audience"
      (is (= {done-id true stopped-id false} @modes)))))

(deftest test-user-who-leaves-during-a-send
  (let [first-user  (create-test-user!)
        second-user (create-test-user!)
        id          (store! "product-update")
        sent        (atom [])]
    (with-redefs [conf/base-url (constantly "https://parts.example")
                  mail/send!    (fn [message]
                                  (user/set-product-updates! (:id second-user) false)
                                  (swap! sent conj message))]
      (email/send-email! id))
    (testing "a user who opted out after the send started is skipped"
      (is (= [(:email first-user)] (map :to @sent)))
      (is (nil? (delivery id (:id second-user)))))))

(deftest test-unknown-email
  (let [sent (atom [])]
    (create-test-user!)
    (with-mailer sent #{}
      (testing "sending an email that does not exist sends nothing"
        (is (nil? (email/send-email! (random-uuid))))
        (is (empty? @sent)))
      (testing "resuming an email that does not exist is refused"
        (is (nil? (email/resume! (random-uuid))))))))

(deftest test-send-stops-on-a-broken-relay
  (testing "a configuration error stops the send and leaves the email stopped"
    (create-test-user!)
    (create-test-user!)
    (let [id    (store! "service-notice")
          tries (atom 0)]
      (with-redefs [mail/send! (fn [_]
                                 (swap! tries inc)
                                 (throw (ex-info "SMTP relay is not configured" {:type :config-error})))]
        (is (thrown? clojure.lang.ExceptionInfo (email/send-email! id))))
      (is (= 1 @tries))
      (is (= :stopped (:state (first (email/sent-emails)))))))
  (testing "five failures in a row stop the send"
    (dotimes [_ 5] (create-test-user!))
    (let [id    (store! "service-notice")
          tries (atom 0)]
      (with-redefs [mail/send! (fn [_]
                                 (swap! tries inc)
                                 (throw (ex-info "Email send failed" {:type :smtp-error})))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"in a row" (email/send-email! id))))
      (is (= 5 @tries))
      (is (nil? (:completed_at (db/query-one (db/sql-format {:select [:completed_at]
                                                             :from   [:operator_emails]
                                                             :where  [:= :id id]}))))))))

(defn- fail-row! [email-id user-id]
  (db/insert! :operator_email_deliveries {:email_id email-id                  :user_id user-id
                                          :error    "550 Mailbox unavailable"}))

(deftest test-failed-users-go-last
  (let [dead (doall (repeatedly 5 create-test-user!))
        late (create-test-user!)
        id   (store! "service-notice")
        sent (atom [])]
    (doseq [u dead] (fail-row! id (:id u)))
    (with-mailer sent (set (map :email dead))
      (testing "a resume reaches new recipients before the five that failed before"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"in a row" (email/send-email! id)))
        (is (= [(:email late)] (map :to @sent)))))))

(deftest test-retry-does-not-stop-on-failures
  (let [users (doall (repeatedly 6 create-test-user!))
        id    (:id (db/insert! :operator_emails {:kind         "service-notice" :subject "S" :body "B"
                                                 :completed_at [:now]}))
        tries (atom 0)]
    (doseq [u users] (fail-row! id (:id u)))
    (with-redefs [mail/send! (fn [_]
                               (swap! tries inc)
                               (throw (ex-info "Email send failed" {:type :smtp-error})))]
      (testing "a retry of failures tries every failed user"
        (email/send-email! id true)
        (is (= 6 @tries))))))

(deftest test-first-finish-is-kept
  (create-test-user!)
  (let [id       (store! "service-notice")
        finished #(:completed_at (db/query-one (db/sql-format {:select [:completed_at]
                                                               :from   [:operator_emails]
                                                               :where  [:= :id id]})))]
    (with-mailer (atom []) #{}
      (email/send-email! id)
      (let [first-finish (finished)]
        (Thread/sleep 10)
        (email/send-email! id true)
        (testing "a retry keeps the time of the first finish"
          (is (= first-finish (finished))))))))

(deftest test-retryable-failures
  (let [subscribed (create-test-user!)
        leaving    (create-test-user!)
        id         (:id (db/insert! :operator_emails {:kind         "product-update" :subject "S" :body "B"
                                                      :completed_at [:now]}))]
    (fail-row! id (:id subscribed))
    (fail-row! id (:id leaving))
    (user/set-product-updates! (:id leaving) false)
    (testing "counts only failed users who are still in the audience"
      (let [summary (first (email/sent-emails))]
        (is (= 2 (:failed summary)))
        (is (= 1 (:retryable summary)))))))
