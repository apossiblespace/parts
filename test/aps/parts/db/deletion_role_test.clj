(ns aps.parts.db.deletion-role-test
  "Fitness tests for the erasure least-privilege wiring.

   The test connection is typically a superuser (CI: postgres), which
   bypasses table ACLs — so 'the app role cannot DELETE' can't be asserted
   against the real app role here; that holds on prod/staging where the app
   role is ordinary (see docs/runbook.md for the live-box check). What these
   tests pin down instead:
     - deletion_role holds every privilege purge-account! uses, so assuming
       the role can never make the purge fail (the erasure tests exercise
       the real SET LOCAL ROLE path end-to-end);
     - a role WITHOUT those grants is denied DELETE on the temporal tables,
       proving no PUBLIC grant quietly undermines the revoke."
  (:require
   [aps.parts.db :as db]
   [aps.parts.helpers.utils :refer [with-test-db]]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]))

(use-fixtures :once with-test-db)

(def ^:private purge-privileges
  "Every (table, privilege) pair purge-account! relies on while running as
   deletion_role. Extend this WHEN extending the purge — the coverage test
   fails when a grant is missing, before a live purge can."
  {"parts"               ["SELECT" "DELETE"]
   "relationships"       ["SELECT" "DELETE"]
   "maps"                ["SELECT" "DELETE"]
   "map_metadata"        ["SELECT" "DELETE"]
   "sessions"            ["SELECT" "DELETE"]
   "session_activations" ["SELECT" "DELETE"]
   "invitations"         ["SELECT" "DELETE"]
   "waitlist_signups"    ["SELECT" "DELETE"]
   "policy_acceptances"  ["SELECT" "DELETE"]
   "users"               ["SELECT" "UPDATE" "DELETE"]
   "audit_log"           ["SELECT" "INSERT" "UPDATE"]})

(deftest test-deletion-role-grants-cover-the-purge
  (doseq [[table privs] purge-privileges
          priv          privs]
    (testing (str "deletion_role can " priv " " table)
      (is (true? (:ok (jdbc/execute-one!
                       db/datasource
                       ["SELECT has_table_privilege('deletion_role', ?, ?) AS ok"
                        table priv]
                       {:builder-fn rs/as-unqualified-maps}))))))
  (testing "the audit trigger's id sequence is usable under the role"
    (is (true? (:ok (jdbc/execute-one!
                     db/datasource
                     ["SELECT has_sequence_privilege('deletion_role',
                                                     'audit_log_id_seq',
                                                     'USAGE') AS ok"]
                     {:builder-fn rs/as-unqualified-maps}))))))

(deftest test-membership-confers-nothing-without-set-role
  ;; Caught live on staging: a default (INHERIT TRUE) membership hands the
  ;; member every deletion_role privilege passively, silently undoing the
  ;; DELETE revoke. Provisioning grants WITH INHERIT FALSE; this probe
  ;; mirrors that grant and pins both halves of the wanted semantics.
  (jdbc/execute! db/datasource
                 ["DO $$ BEGIN
                     IF NOT EXISTS (SELECT 1 FROM pg_roles
                                    WHERE rolname = 'app_shaped_probe') THEN
                       CREATE ROLE app_shaped_probe NOLOGIN;
                     END IF;
                   END $$"])
  (jdbc/execute! db/datasource
                 ["GRANT deletion_role TO app_shaped_probe WITH INHERIT FALSE"])
  (testing "membership alone does not confer DELETE"
    (is (thrown-with-msg?
         org.postgresql.util.PSQLException #"permission denied"
         (jdbc/with-transaction [tx db/datasource]
           (jdbc/execute! tx ["SET LOCAL ROLE app_shaped_probe"])
           (jdbc/execute! tx ["DELETE FROM parts WHERE false"])))))
  (testing "explicitly assuming deletion_role does — the purge's one path
            (membership admitting SET ROLE for a non-superuser session is a
            server-side property; verified on a live box, not provable from
            this superuser test connection)"
    (jdbc/with-transaction [tx db/datasource]
      (jdbc/execute! tx ["SET LOCAL ROLE deletion_role"])
      (is (zero? (::jdbc/update-count
                  (jdbc/execute-one! tx ["DELETE FROM parts WHERE false"])))
          "deletion_role's own grants carry the purge"))))

(deftest test-delete-is-denied-without-the-role
  ;; A role holding no grants stands in for the post-revoke app role (the
  ;; test connection itself is a superuser and can't be denied anything).
  (jdbc/execute! db/datasource
                 ["DO $$ BEGIN
                     IF NOT EXISTS (SELECT 1 FROM pg_roles
                                    WHERE rolname = 'no_priv_probe') THEN
                       CREATE ROLE no_priv_probe NOLOGIN;
                     END IF;
                   END $$"])
  (doseq [table ["users" "maps" "map_metadata" "parts" "relationships"]]
    (testing (str "an ungranted role cannot DELETE FROM " table)
      (is (thrown-with-msg?
           org.postgresql.util.PSQLException #"permission denied"
           (jdbc/with-transaction [tx db/datasource]
             (jdbc/execute! tx ["SET LOCAL ROLE no_priv_probe"])
             (jdbc/execute! tx [(str "DELETE FROM " table)])))))))
