(ns parts.db
  (:require
   [clojure.string :as str]
   [com.brunobonacci.mulog :as mulog]
   [honey.sql :as sql]
   [migratus.core :as migratus]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]
   [parts.config :as conf])
  (:import
   (java.util UUID)))

(def db-spec
  {:dbtype "sqlite"
   :dbname (conf/database-file (conf/config))})

;; NOTE: Optimisations in this file are following this tweet:
;; https://x.com/meln1k/status/1813314113705062774
(def pragmas
  ["PRAGMA journal_mode = WAL;"
   "PRAGMA busy_timeout = 5000;"
   "PRAGMA synchronous = NORMAL;"
   "PRAGMA cache_size = -20000;"
   "PRAGMA foreign_keys = true;"
   "PRAGMA temp_store = memory;"])

(defn- create-datasource
  [read-only?]
  (let [url (str "jdbc:sqlite:" (:dbname db-spec))
        ds-opts (cond-> {:jdbcUrl url
                         :connectionInitSql (str/join " " pragmas)
                         :foreign_keys true}
                  read-only? (assoc :mode "ro")
                  (not read-only?) (assoc :mode "rwc" :_txlock "immediate"))]
    (jdbc/get-datasource ds-opts)))

(def read-datasource (create-datasource true))
(def write-datasource (create-datasource false))

(def migration-config
  {:store :database
   :migration-dir "migrations/"
   :init-in-transaction? false
   :db db-spec})

(defn init-db
  []
  (mulog/log ::initializing-database)
  (migratus/init migration-config)
  (migratus/migrate migration-config))

(defn sql-format
  "Convert a HoneySQL map to a vector of [sql & params]"
  [sql-map]
  (sql/format sql-map))

(defn query
  [q]
  (jdbc/execute! read-datasource q {:builder-fn rs/as-unqualified-maps}))

(defn query-one
  [q]
  (first (query q)))

(defn generate-uuid
  []
  (str (UUID/randomUUID)))

(defn insert!
  [table data]
  (let [data-with-uuid (assoc data :id (generate-uuid))]
    (first (jdbc/execute! write-datasource
                          (sql/format {:insert-into (keyword table)
                                       :values [data-with-uuid]
                                       :returning :*})
                          {:builder-fn rs/as-unqualified-maps}))))

(defn update!
  [table data where-clause]
  (jdbc/execute! write-datasource
                 (sql/format {:update (keyword table)
                              :set data
                              :where where-clause
                              :returning :*})
                 {:builder-fn rs/as-unqualified-maps}))

(defn delete!
  [table where-clause]
  (jdbc/execute! write-datasource
                 (sql/format {:delete-from (keyword table)
                              :where where-clause})
                 {:builder-fn rs/as-unqualified-maps}))

;; Connection pool configuration
(def read-pool-spec
  {:datasource read-datasource
   :maximum-pool-size (max 4 (.availableProcessors (Runtime/getRuntime)))})

(def write-pool-spec
  {:datasource write-datasource
   :maximum-pool-size 1})

(def read-pool (jdbc/get-datasource read-pool-spec))
(def write-pool (jdbc/get-datasource write-pool-spec))
