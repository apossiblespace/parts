(ns aps.parts.password-resets-test
  (:require
   [aps.parts.db :as db]
   [aps.parts.helpers.utils :refer [create-test-user! with-test-db]]
   [aps.parts.password-resets :as resets]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :once with-test-db)

(defn- reset-rows [user-id]
  (db/query (db/sql-format {:select [:*]
                            :from   [:password_resets]
                            :where  [:= :user_id (db/->uuid user-id)]})))

(defn- expire! [token]
  (db/update! :password_resets
              {:expires_at [:- [:now] [:cast "1 hour" :interval]]}
              [:= :token token]))

(deftest create-reset-test
  (testing "an email with no account gets nil — nothing is minted"
    (is (nil? (resets/create-reset! "nobody@example.com"))))

  (testing "an account email gets a token and a /reset/ magic link"
    (let [{:keys [id]}        (create-test-user! {:email "reset-create@example.com"})
          {:keys [token url]} (resets/create-reset! "reset-create@example.com")]
      (is (string? token))
      (is (str/includes? url (str "/reset/" token)))
      (is (= 1 (count (reset-rows id))))))

  (testing "email lookup is normalized like login"
    (create-test-user! {:email "reset-case@example.com"})
    (is (some? (resets/create-reset! "  Reset-Case@Example.com  ")))))

(deftest idempotent-reuse-test
  (testing "a repeat request returns the same live token — the link already
            in the inbox keeps working, and a stranger re-submitting the
            form cannot invalidate it"
    (create-test-user! {:email "reset-again@example.com"})
    (let [first-token  (:token (resets/create-reset! "reset-again@example.com"))
          second-token (:token (resets/create-reset! "reset-again@example.com"))]
      (is (= first-token second-token))
      (is (some? (resets/find-active first-token)))))

  (testing "a fresh token is minted once the previous one has expired"
    (create-test-user! {:email "reset-fresh@example.com"})
    (let [first-token (:token (resets/create-reset! "reset-fresh@example.com"))]
      (expire! first-token)
      (let [second-token (:token (resets/create-reset! "reset-fresh@example.com"))]
        (is (not= first-token second-token))
        (is (some? (resets/find-active second-token)))))))

(deftest delete-expired-test
  (testing "the sweep removes rows a week past expiry and keeps live ones"
    (create-test-user! {:email "reset-sweep@example.com"})
    (let [old-token (:token (resets/create-reset! "reset-sweep@example.com"))]
      (db/update! :password_resets
                  {:expires_at [:- [:now] [:cast "8 days" :interval]]}
                  [:= :token old-token])
      (let [live-token (:token (resets/create-reset! "reset-sweep@example.com"))
            removed    (resets/delete-expired! db/datasource)]
        (is (pos? removed))
        (is (nil? (db/query-one
                   (db/sql-format {:select [:*]
                                   :from   [:password_resets]
                                   :where  [:= :token old-token]})))
            "the long-expired row is gone")
        (is (some? (resets/find-active live-token)) "the live row survives")))))

(deftest find-active-test
  (testing "an unknown token is nil"
    (is (nil? (resets/find-active "no-such-token"))))

  (testing "an expired token is nil"
    (create-test-user! {:email "reset-expired@example.com"})
    (let [{:keys [token]} (resets/create-reset! "reset-expired@example.com")]
      (expire! token)
      (is (nil? (resets/find-active token))))))

(deftest claim-test
  (testing "claiming spends the token exactly once"
    (create-test-user! {:email "reset-claim@example.com"})
    (let [{:keys [token]} (resets/create-reset! "reset-claim@example.com")
          claimed         (db/with-transaction
                            (fn [tx] (resets/claim! token tx)))]
      (is (some? (:user_id claimed)))
      (is (nil? (resets/find-active token)) "spent token is no longer active")
      (is (nil? (db/with-transaction
                  (fn [tx] (resets/claim! token tx))))
          "a second claim gets nothing")))

  (testing "an expired token cannot be claimed"
    (create-test-user! {:email "reset-claim-expired@example.com"})
    (let [{:keys [token]} (resets/create-reset! "reset-claim-expired@example.com")]
      (expire! token)
      (is (nil? (db/with-transaction
                  (fn [tx] (resets/claim! token tx))))))))
