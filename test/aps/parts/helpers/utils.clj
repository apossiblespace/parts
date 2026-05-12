(ns aps.parts.helpers.utils
  (:require
   [aps.parts.config :as conf]
   [aps.parts.db.erasure :as erasure]
   [aps.parts.entity.system :as system]
   [aps.parts.entity.user :as user]
   [aps.parts.helpers.test-factory :as factory]
   [clojure.tools.logging :as log]
   [migratus.core :as migratus]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]))

(defn setup-test-db
  []
  (let [db-spec          (conf/database-config)
        ds               (jdbc/get-datasource db-spec)
        migration-config {:store                :database
                          :migration-dir        "migrations/"
                          :init-in-transaction? true
                          :db                   db-spec}]

    (migratus/init migration-config)
    (migratus/migrate migration-config)
    ds))

(def ^:private tombstone-user-sql
  [(str "INSERT INTO users (id, email, username, display_name, password_hash, role)
         VALUES ('" erasure/tombstone-id "',
                 'deleted@aps.local', '__deleted__', 'Deleted user', '!', 'therapist')
         ON CONFLICT (id) DO NOTHING")])

(defn truncate-all-tables
  [ds]
  (try
    (jdbc/with-transaction [tx ds]
      (jdbc/execute! tx ["SET session_replication_role = 'replica'"])
      (let [tables (jdbc/execute!
                    tx
                    ["SELECT table_name
                      FROM information_schema.tables
                      WHERE table_schema = 'public'
                      AND table_type = 'BASE TABLE'
                      AND table_name != 'schema_migrations'"]
                    {:builder-fn rs/as-unqualified-lower-maps})]
        (doseq [{:keys [table_name]} tables]
          (jdbc/execute! tx [(str "TRUNCATE TABLE \"" table_name "\" CASCADE")])))
      (jdbc/execute! tx ["SET session_replication_role = 'origin'"])
      ;; Bitemporal + erasure code FKs to the tombstone user; restore it.
      (jdbc/execute! tx tombstone-user-sql))
    (catch Exception e
      (log/error "Failed to truncate tables from test database")
      (throw e))))

(defn with-test-db
  [f]
  (let [ds (setup-test-db)]
    (try
      (f)
      (finally
        (truncate-all-tables ds)))))

(defn create-test-user!
  "Create a user in the database from the factory."
  ([]      (create-test-user! {}))
  ([attrs] (user/create! (factory/build-test-user attrs))))

(defn create-test-system!
  "Create a system owned by `user-id` (also used as actor). Goes through the
   entity layer so the metadata row in `system_metadata` is populated too."
  ([user-id] (create-test-system! user-id "Test System"))
  ([user-id title]
   (system/create! {:owner_id user-id :title title} user-id)))
