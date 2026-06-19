(ns aps.parts.helpers.utils
  (:require
   [aps.parts.config :as conf]
   [aps.parts.db.erasure :as erasure]
   [aps.parts.entity.map :as parts-map]
   [aps.parts.entity.user :as user]
   [aps.parts.helpers.test-factory :as factory]
   [clojure.tools.logging :as log]
   [migratus.core :as migratus]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]))

(def ^:private test-db-name
  "The only database the test fixtures are permitted to touch. The suite's
   destructive TRUNCATE must never reach parts_dev or any other database."
  "parts_test")

(defn- assert-test-database!
  "Throw unless `dbname` is the designated test database. Destructive
   fixtures call this before doing anything, so a misconfigured environment
   fails loudly instead of silently wiping the wrong database."
  [dbname]
  (when-not (= test-db-name dbname)
    (throw (ex-info
            (str "Refusing to run test fixtures against database "
                 (pr-str dbname) " — expected " (pr-str test-db-name) ". "
                 "Check that -Dparts.env=test is set (deps.edn :test/env alias).")
            {:type :unsafe-test-database :dbname dbname}))))

(defn setup-test-db
  []
  (let [db-spec (conf/database-config)]
    (assert-test-database! (:dbname db-spec))
    (let [ds               (jdbc/get-datasource db-spec)
          migration-config {:store                :database
                            :migration-dir        "migrations/"
                            :init-in-transaction? true
                            :db                   db-spec}]
      (migratus/init migration-config)
      (migratus/migrate migration-config)
      ds)))

(def ^:private tombstone-user-sql
  [(str "INSERT INTO users (id, email, display_name, password_hash, role)
         VALUES ('" erasure/tombstone-id "',
                 'deleted@aps.local', 'Deleted user', '!', 'therapist')
         ON CONFLICT (id) DO NOTHING")])

(defn truncate-all-tables
  [ds]
  (try
    (jdbc/with-transaction [tx ds]
      ;; Last-line guard: ask the live connection which database it is —
      ;; config is exactly the thing that can be wrong.
      (assert-test-database!
       (:current_database
        (jdbc/execute-one! tx ["SELECT current_database()"]
                           {:builder-fn rs/as-unqualified-lower-maps})))
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

(defn create-test-map!
  "Create a map owned by `user-id` (also used as actor). Goes through the
   entity layer so the metadata row in `map_metadata` is populated too."
  ([user-id] (create-test-map! user-id "Test Map"))
  ([user-id title]
   (parts-map/create! {:owner_id user-id :title title} user-id)))

(defn silently
  "Run `f`, discarding anything it prints to stdout. Returns f's value.
   For exercising the operator helpers (billing, stats) whose reports print."
  [f]
  (binding [*out* (java.io.StringWriter.)]
    (f)))
