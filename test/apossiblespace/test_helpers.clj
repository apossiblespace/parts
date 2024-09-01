(ns apossiblespace.test-helpers
  (:require [next.jdbc :as jdbc]
            [migratus.core :as migratus]
            [apossiblespace.parts.config :as conf]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io]))

(defn setup-test-db []
  (let [db-spec {:dbtype "sqlite" :dbname (conf/database-file (conf/config))}
        ds (jdbc/get-datasource db-spec)]
    (let [migration-config {:store :database
                            :migration-dir "migrations"
                            :db db-spec}]
      (migratus/init migration-config)
      (migratus/migrate migration-config))
    ds))

(defn drop-all-tables [ds]
  (try
    (jdbc/with-transaction [tx ds]
      (jdbc/execute! tx ["PRAGMA foreign_keys = OFF"])
      (let [tables (jdbc/execute! tx ["SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"])]
        (doseq [{name :sqlite_master/name} tables]
          (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " name)])))
      (jdbc/execute! tx ["PRAGMA foreign_keys = ON"]))
    (catch Exception e
      (log/error "Failed to drop tables from test database")
      (throw e))))

(defn with-test-db [f]
  (let [ds (setup-test-db)]
    (try
      (f)
      (finally
        (drop-all-tables ds)))))