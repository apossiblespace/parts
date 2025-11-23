(ns aps.parts.db
  (:require
   [aps.parts.config :as conf]
   [com.brunobonacci.mulog :as mulog]
   [honey.sql :as sql]
   [migratus.core :as migratus]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]))

(defn db-spec
  "Get database specification from config."
  []
  (conf/database-config))

(defn make-datasource
  "Create a connection pool for PostgreSQL."
  []
  (jdbc/get-datasource (db-spec)))

(def datasource
  "Single connection pool for all database operations."
  (make-datasource))

;; Aliases for compatibility during transition
(def read-datasource datasource)
(def write-datasource datasource)

(def migration-config
  {:store                :database
   :migration-dir        "migrations/"
   :init-in-transaction? true ; PostgreSQL supports transactional DDL
   :db                   (db-spec)})

(defn init-db
  []
  (mulog/log ::initializing-database)
  (migratus/init migration-config)
  (migratus/migrate migration-config))

(defn ->uuid
  "Converts a string UUID to a java.util.UUID object if needed.
   If already a UUID object, returns it unchanged.
   If nil, returns nil.
   Throws ex-info with :type :invalid-uuid if the string is not a valid UUID."
  [id]
  (cond
    (nil? id) nil
    (uuid? id) id
    (string? id) (try
                   (java.util.UUID/fromString id)
                   (catch IllegalArgumentException _
                     (throw (ex-info "Invalid UUID format" {:type :invalid-uuid :value id}))))
    :else (throw (ex-info "Invalid UUID type" {:type :invalid-uuid :value id}))))

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

(defn insert!
  "Inserts `data` into `table`. Returns the inserted record with all fields including
   the database-generated UUID."
  ([table data]
   (insert! table data write-datasource))
  ([table data datasource]
   (first (jdbc/execute! datasource
                         (sql/format {:insert-into (keyword table)
                                      :values      [data]
                                      :returning   :*})
                         {:builder-fn rs/as-unqualified-maps}))))

(defn update!
  ([table data where-clause]
   (update! table data where-clause write-datasource))
  ([table data where-clause datasource]
   (jdbc/execute! datasource
                  (sql/format {:update    (keyword table)
                               :set       data
                               :where     where-clause
                               :returning :*})
                  {:builder-fn rs/as-unqualified-maps})))

(defn delete!
  ([table where-clause]
   (delete! table where-clause write-datasource))
  ([table where-clause datasource]
   (jdbc/execute! datasource
                  (sql/format {:delete-from (keyword table)
                               :where       where-clause})
                  {:builder-fn rs/as-unqualified-maps})))

(defn with-transaction
  "Execute a function f within a transaction on the write datasource.
  f should accept a transaction connection as its argument"
  [f]
  (jdbc/with-transaction [tx write-datasource]
    (f tx)))

(defn affected-row-count
  "Extract the number of rows affected by a DML operation.
   Works with both single result maps and collections of result maps.
   Always returns an integer ≥ 0."
  [result]
  (cond
    (nil? result) 0
    (map? result) (or (:next.jdbc/update-count result) 0)
    (sequential? result) (if (empty? result)
                           0
                           (reduce + (map affected-row-count result)))
    :else 0))

;; Connection pool aliases for compatibility
(def read-pool datasource)
(def write-pool datasource)
