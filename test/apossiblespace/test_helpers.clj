(ns apossiblespace.test-helpers
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [migratus.core :as migratus]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [apossiblespace.parts.db]
            [apossiblespace.parts.auth]))

(def test-db-file "db/parts_test.db")

(def test-db-spec
  {:dbtype "sqlite" :dbname test-db-file})

(def test-secret "test-secret-key-for-jwt-in-unit-tests-only")

(defn setup-test-db []
  (let [ds (jdbc/get-datasource test-db-spec)]
    (let [migration-config {:store :database
                            :migration-dir "migrations"
                            :db test-db-spec}]
      (migratus/init migration-config)
      (migratus/migrate migration-config))
    ds))

(defn clear-test-db [ds]
  (let [tables (jdbc/execute! ds ["SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"])]
    ;; FIXME: Remove debug print
    (println "Tables to clear:" tables)
    (when (seq tables)
      (jdbc/with-transaction [tx ds]
        (jdbc/execute! tx ["PRAGMA foreign_keys = OFF"])
        (doseq [{:keys [name]} tables]
          (when (not (str/blank? name))
            ;; FIXME: Remove debug print
            (println "Clearing table:" name)
            (jdbc/execute! tx [(str "DELETE FROM " name)])))
        (jdbc/execute! tx ["PRAGMA foreign_keys = ON"])))))

(defn sqlite-friendly-sql [sql params]
  (if (string? sql)
    [sql params]
    (let [sql-map (apossiblespace.parts.db/sql-format sql)
          parameterized-sql (str/replace (:sql sql-map) #":(\w+)" "?")
          ordered-params (map #(get params (keyword %)) (re-seq #":(\w+)" (:sql sql-map)))]
      [parameterized-sql ordered-params])))

(defn with-test-db [f]
  (let [ds (setup-test-db)]
    (try
      (with-redefs [apossiblespace.parts.db/query-one (fn [query]
                                                        (let [[sql params] (sqlite-friendly-sql query {})]
                                                          (jdbc/execute-one! ds query {:parameters params
                                                                                       :return-keys true
                                                                                       :builder-fn rs/as-unqualified-maps})))
                    apossiblespace.parts.db/insert! (fn [table data]
                                                      (let [columns (keys data)
                                                            values (vals data)
                                                            sql (str "INSERT INTO " (name table) " ("
                                                                     (str/join ", " (map name columns))
                                                                     ") VALUES ("
                                                                     (str/join ", " (repeat (count values) "?"))
                                                                     ")")]
                                                        (jdbc/execute-one! ds [sql values]
                                                                           {:return-keys true
                                                                            :builder-fn rs/as-unqualified-maps})))
                    apossiblespace.parts.auth/get-secret (constantly test-secret)]
        (f))
      (finally
        (clear-test-db ds)))))
