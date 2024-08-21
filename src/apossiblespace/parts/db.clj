(ns apossiblespace.parts.db
  (:require
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]
   [migratus.core :as migratus]
   [honey.sql :as sql]
   [com.brunobonacci.mulog :as mulog])
  (:import
   [java.util UUID]))

(def db-spec
  {:dbtype "sqlite"
   :dbname "db/parts.db"})

(def datasource
  (jdbc/get-datasource db-spec))

(def migration-config
  {:store :database
   :migration-dir "migrations"
   :db db-spec})

(defn init-db []
  (mulog/log ::initializing-database)
  (migratus/init migration-config)
  (migratus/migrate migration-config))

(defn query
  [q]
  (jdbc/execute! datasource q {:builder-fn rs/as-unqualified-maps}))

(defn query-one
  [q]
  (first (query q)))

(defn generate-uuid []
  (str (UUID/randomUUID)))

(defn insert!
  [table data]
  (let [data-with-uuid (assoc data :id (generate-uuid))]
    (query (sql/format {:insert-into table
                        :values [data-with-uuid]}))))

(defn udpate!
  [table data where-clause]
  (query (sql/format {:update table
                      :set data
                      :where where-clause})))

(defn delete!
  [table where-clause]
  (query (sql/format {:delete-from table
                      :where where-clause})))
