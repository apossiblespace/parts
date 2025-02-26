(ns repl
  (:require
   [migratus.core :as migratus]
   [parts.config :as conf]
   [parts.db :as db]
   [parts.server :as server]
   [shadow.cljs.devtools.api :as shadow]
   [shadow.cljs.devtools.server :as shadow-server]))

(defonce server-ref (atom nil))

(defn db-migrate [env]
  (let [db-spec {:dbtype "sqlite" :dbname (conf/database-file (conf/config env))}
        migration-config (assoc db/migration-config :db db-spec)]
    (migratus/migrate migration-config)))

(defn db-rollback [env]
  (let [db-spec {:dbtype "sqlite" :dbname (conf/database-file (conf/config env))}
        migration-config (assoc db/migration-config :db db-spec)]
    (migratus/rollback migration-config)))

;; TODO: Do we also want to open Inspector automatically?
(defn start []
  (shadow-server/start!)
  (shadow/watch :frontend)

  (reset! server-ref
          (server/-main))
  ::started)

(defn stop []
  (when-some [stop-server @server-ref]
    (reset! server-ref nil)
    (stop-server))
  ::stopped)

(defn go []
  (stop)
  (start))

(defn cljs-repl []
  (shadow.cljs.devtools.api/repl :frontend))
