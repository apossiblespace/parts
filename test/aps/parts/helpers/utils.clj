(ns aps.parts.helpers.utils
  (:require
   [aps.parts.config :as conf]
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

(defn truncate-all-tables
  [ds]
  (try
    (jdbc/with-transaction [tx ds]
      ;; Disable foreign key checks temporarily
      (jdbc/execute! tx ["SET session_replication_role = 'replica'"])

      ;; Get all tables except system tables and migrations
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

      ;; Re-enable foreign key checks
      (jdbc/execute! tx ["SET session_replication_role = 'origin'"]))
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

(defn register-test-user
  "Create a user in the database from the factory"
  ([]
   (register-test-user {}))
  ([attrs]
   (let [user-data (factory/build-test-user attrs)]
     (user/create! user-data))))
