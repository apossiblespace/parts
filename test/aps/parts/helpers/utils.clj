(ns aps.parts.helpers.utils
  (:require
   [aps.parts.config :as conf]
   [aps.parts.entity.user :as user]
   [aps.parts.helpers.test-factory :as factory]
   [clojure.tools.logging :as log]
   [migratus.core :as migratus]
   [next.jdbc :as jdbc]))

(defn setup-test-db
  []
  (let [db-spec          {:dbtype "sqlite" :dbname (conf/database-file (conf/config))}
        ds               (jdbc/get-datasource db-spec)
        migration-config {:store         :database
                          :migration-dir "migrations/"
                          :db            db-spec}]

    (migratus/init migration-config)
    (migratus/migrate migration-config)
    ds))

(defn truncate-all-tables
  [ds]
  (try
    (jdbc/with-transaction [tx ds]
      (jdbc/execute! tx ["PRAGMA foreign_keys = OFF"])
      (let [tables (jdbc/execute! tx ["SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'schema_migrations'"])]
        (doseq [{name :sqlite_master/name} tables]
          (jdbc/execute! tx [(str "DELETE FROM  " name)])))
      (jdbc/execute! tx ["PRAGMA foreign_keys = ON"]))
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
