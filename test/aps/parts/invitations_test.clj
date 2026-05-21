(ns aps.parts.invitations-test
  (:require
   [aps.parts.db :as db]
   [aps.parts.helpers.utils :refer [with-test-db]]
   [aps.parts.invitations :as inv]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :once with-test-db)

(defn- invitation-row [email]
  (db/query-one (db/sql-format {:select [:*]
                                :from   [:invitations]
                                :where  [:= :email email]})))

(defn- invitations-count []
  (:total (db/query-one (db/sql-format {:select [[:%count.* :total]]
                                        :from   [:invitations]}))))

(defn- silently
  "Run `f`, discarding anything it prints to stdout. Returns f's value."
  [f]
  (binding [*out* (java.io.StringWriter.)]
    (f)))

(deftest generate-invitation!-test
  (testing "minting a fresh invitation returns email, token and magic link"
    (let [result (inv/generate-invitation! "fresh@example.com")]
      (is (= "fresh@example.com" (:email result)))
      (is (string? (:token result)))
      (is (str/ends-with? (:magic-link result) (str "/invite/" (:token result))))))

  (testing "is idempotent for an active invitation — the same token comes back"
    (let [first-call  (inv/generate-invitation! "idem@example.com")
          second-call (inv/generate-invitation! "idem@example.com")]
      (is (= (:token first-call) (:token second-call)))))

  (testing "re-issues a fresh token when the prior invitation was revoked"
    (let [original (inv/generate-invitation! "reissue@example.com")
          _        (silently #(inv/revoke-invitation! "reissue@example.com"))
          reissued (inv/generate-invitation! "reissue@example.com")]
      (is (not= (:token original) (:token reissued)))
      (is (nil? (:revoked_at (invitation-row "reissue@example.com"))))))

  (testing "skips an email that has already redeemed an invitation"
    (inv/generate-invitation! "redeemed@example.com")
    (db/update! :invitations {:redeemed_at [:now]} [:= :email "redeemed@example.com"])
    (is (nil? (silently #(inv/generate-invitation! "redeemed@example.com"))))))

(deftest revoke-invitation!-test
  (testing "revoking an active invitation sets revoked_at"
    (inv/generate-invitation! "revoke-me@example.com")
    (let [revoked (silently #(inv/revoke-invitation! "revoke-me@example.com"))]
      (is (some? revoked))
      (is (some? (:revoked_at (invitation-row "revoke-me@example.com"))))))

  (testing "revoking when there is no active invitation returns nil"
    (is (nil? (silently #(inv/revoke-invitation! "never-invited@example.com"))))))

(deftest find-active-test
  (testing "returns the row for an active token, nil once revoked"
    (let [{:keys [token]} (inv/generate-invitation! "findable@example.com")]
      (is (= "findable@example.com" (:email (inv/find-active token))))
      (silently #(inv/revoke-invitation! "findable@example.com"))
      (is (nil? (inv/find-active token))))))

(deftest pending-waitlist!-test
  (testing "lists waitlist emails with no invitation row, excludes invited ones"
    (db/insert! :waitlist_signups {:email "pending-wl@example.com"})
    (db/insert! :waitlist_signups {:email "invited-wl@example.com"})
    (inv/generate-invitation! "invited-wl@example.com")
    (let [emails (set (map :email (silently inv/pending-waitlist!)))]
      (is (contains? emails "pending-wl@example.com"))
      (is (not (contains? emails "invited-wl@example.com"))))))

(deftest print-invitation-links!-test
  (testing "prints email,magic_link for active invitations and never mints"
    (db/insert! :waitlist_signups {:email "printme@example.com"})
    (inv/generate-invitation! "printme@example.com")
    (let [before (invitations-count)
          output (with-out-str (inv/print-invitation-links!))
          after  (invitations-count)]
      (is (str/includes? output "printme@example.com,"))
      (is (str/includes? output "/invite/"))
      (is (= before after) "print-invitation-links! must not create invitations"))))
