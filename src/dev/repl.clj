(ns repl
  (:require
   [kaocha.repl :as k]
   [kaocha.watch :as watch]
   [migratus.core :as migratus]
   [portal.api :as portal]
   [mulog-events]
   [parts.config :as conf]
   [parts.db :as db]
   [parts.server :as server]
   [shadow.cljs.devtools.api :as shadow]
   [shadow.cljs.devtools.server :as shadow-server]))

(defn cljs-repl []
  (shadow.cljs.devtools.api/repl :frontend))

(def portal-instance
  "Open portal window if no portal sessions have been created.
   A portal session is created when opening a portal window.

   Opens in the default system browser."
  (or (seq (portal/sessions))
      (portal/open {:app false})))

(add-tap #'portal.api/submit)

;; App server management

(defonce server-ref (atom nil))

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

(defn db-migrate [env]
  (let [db-spec {:dbtype "sqlite" :dbname (conf/database-file (conf/config env))}
        migration-config (assoc db/migration-config :db db-spec)]
    (migratus/migrate migration-config)))

(defn db-rollback [env]
  (let [db-spec {:dbtype "sqlite" :dbname (conf/database-file (conf/config env))}
        migration-config (assoc db/migration-config :db db-spec)]
    (migratus/rollback migration-config)))

;; Test running
(defn with-env [env-map f]
  (let [old-env (into {} (System/getenv))]
    (try
      (doseq [[k v] env-map]
        (System/setProperty k v))
      (f)
      (finally
        ;; Restore original env vars
        (doseq [[k _] env-map]
          (if-let [old-val (get old-env k)]
            (System/setProperty k old-val)
            (System/clearProperty k)))))))

(defn run-tests
  "Run all tests or specific test(s).
   Examples:
   (run-tests)                    ;; runs current namespace
   (run-tests :unit)              ;; runs unit test suite
   (run-tests 'parts.auth-test)   ;; runs specified namespace
   (run-tests #'parts.auth-test/test-authenticate) ;; runs specific test"
  ([]
   (with-env {"PARTS_ENV" "test"}
     #(k/run)))
  ([& args]
   (with-env {"PARTS_ENV" "test"}
     #(apply k/run args))))

(defn run-all-tests
  "Run all test suites"
  []
  (k/run-all))

(defn watch-tests
  "Start test watcher - tests will re-run when files change"
  []
  (watch/run (k/config)))

(defn cljs-tests
  "Run ClojureScript tests"
  []
  (k/run :cljs))

(defn show-config
  "Show current test configuration"
  []
  (k/config))

(defn show-test-plan
  "Show current test plan"
  []
  (k/test-plan))
