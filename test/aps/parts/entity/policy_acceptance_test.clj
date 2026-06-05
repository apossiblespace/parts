(ns aps.parts.entity.policy-acceptance-test
  "Onboarding writes write-once acceptance evidence (ADR-0009): a row per legal
   document, stamped with the server-side version, in the account-creation
   transaction; erasure removes them."
  (:require
   [aps.parts.api.account :as account]
   [aps.parts.common.constants :as c]
   [aps.parts.db :as db]
   [aps.parts.db.erasure :as erasure]
   [aps.parts.helpers.test-factory :as factory]
   [aps.parts.helpers.utils :refer [with-test-db]]
   [aps.parts.legal :as legal]
   [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :once with-test-db)

(defn- acceptances-for [user-id]
  (db/query (db/sql-format
             {:select [:document :version]
              :from   [:policy_acceptances]
              :where  [:= :user_id (db/->uuid user-id)]})))

(defn- provision! [extra]
  (db/with-transaction
    #(account/provision-account! (merge (factory/build-test-user) extra) %)))

(deftest records-a-versioned-row-per-document
  (testing "a completed signup writes one row per legal document, stamped with the current server-side version"
    (let [{:keys [account]} (provision! {:accepted-legal? true :accepted-medical? true})
          by-doc            (into {} (map (juxt :document :version))
                                  (acceptances-for (:id account)))]
      (is (= #{"privacy" "terms" "dpa"} (set (keys by-doc)))
          "one row per legal document")
      (doseq [{:keys [slug]} c/legal-documents]
        (is (= (:version (legal/document slug)) (get by-doc slug))
            (str slug " is stamped with the loader's current version"))))))

(deftest rejects-signup-without-both-acceptances
  (testing "a missing checkbox is a validation error — no account, no acceptance rows"
    (doseq [missing [{:accepted-legal? true}    ; medical unchecked
                     {:accepted-medical? true}  ; legal unchecked
                     {}]]                        ; neither
      (let [user-data (factory/build-test-user)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"accept"
                              (provision! (merge user-data missing))))
        (is (nil? (db/query-one (db/sql-format {:select [:id]
                                                :from   [:users]
                                                :where  [:= :email (:email user-data)]})))
            "no account was created")))))

(deftest erasure-deletes-acceptances
  (testing "purging an account removes its acceptance rows"
    (let [{:keys [account]} (provision! {:accepted-legal? true :accepted-medical? true})]
      (is (seq (acceptances-for (:id account))) "rows exist before erasure")
      (erasure/purge-account! db/datasource (:id account))
      (is (empty? (acceptances-for (:id account))) "rows are gone after erasure"))))
